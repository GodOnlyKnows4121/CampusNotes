package cn.campus.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class WorkflowServiceTest {
    private lateinit var context:Context;private lateinit var db:CampusDatabase;private lateinit var repo:LocalRepository
    private lateinit var settings:SettingsStore;private lateinit var processor:ContentProcessor
    private val calls=mutableListOf<List<PromptMessage>>();private val tools=mutableListOf<String>();private var plan=""
    @Before fun setup()=runBlocking {
        context=ApplicationProvider.getApplicationContext();db=Room.inMemoryDatabaseBuilder(context,CampusDatabase::class.java).build();repo=LocalRepository(context,db);settings=SettingsStore(context)
        ChatSessionStore(db).initialize()
        settings.save(ProviderKind.DEEPSEEK,"https://chat.example.invalid/v1","test-chat","",false)
        settings.save(ProviderKind.ASR,"https://asr.example.invalid/v1","test-asr","",false)
        settings.save(ProviderKind.OCR,"https://ocr.example.invalid/v1","test-ocr","",false)
        settings.sandbox(false)
        repo.saveNode(LibraryNode("g",kind=NodeKind.GROUP,title="学习",rule="列出定义和来源"));repo.saveNode(LibraryNode("s","g",NodeKind.SUBJECT,"数学"));repo.saveNode(LibraryNode("t","s",NodeKind.TOPIC,"极限"))
        processor=ContentProcessor(context,repo,settings)
    }
    @After fun close()=runBlocking {processor.shutdown();db.close()}
    private suspend fun source(title:String,mime:String,text:String):LibraryNode {
        repo.importStream("t",title,mime,ByteArrayInputStream(text.toByteArray()))
        return db.dao().nodes().last{it.kind==NodeKind.ORIGINAL}
    }
    private fun language():(ProviderCredentials,String)->AiProvider={_,_->object:AiProvider{
        override fun streamReply(messages:List<PromptMessage>,json:Boolean):Flow<String>{calls+=messages
            return flowOf(if(messages.first().content.contains("工具规划器"))plan else if(json)"{\"title\":\"定义提纲\",\"body\":\"核对后的课程要点\"}" else "根据[资料1]的已确认文字回答")
        }
    }}
    private fun service(mockTools:Boolean=true)=WorkflowService(repo,settings,processor,language(),{m->ProviderCredentials(m.endpoint,m.model,"synthetic-key-never-persisted")},if(!mockTools)null else {s,tool,config,credentials,file->
        tools+=tool
        assertTrue(file.isFile);assertFalse(file.canonicalPath.startsWith(repo.documents.canonicalPath))
        if(tool=="parse")processor.processForWorkflow(s,tool,config,credentials,file)
        else ContentResult(sourceId=s.id,sourceStamp=s.stamp,kind=tool,status="review",text="合成${tool}识别文字").also{db.content().save(it)}
    })
    private fun setPlan(a:WorkflowApproval){plan=JSONObject().put("steps",JSONArray().apply{a.sources.forEach{put(JSONObject().put("sourceId",it.id).put("tool",if(it.confirmedId!=null)"read_confirmed" else it.tool))}}).toString()}
    private suspend fun approve(a:WorkflowApproval){WorkflowResult.read(db.workflows().get(a.id)!!.results).filter{it.resultId!=null}.forEach{processor.confirm(it.resultId!!,"人工核对的${it.tool}重点")}}

    @Test fun modelRoutesThreeTypesThenWaitsForReviewBeforeAnswering()=runBlocking {
        val audio=source("课堂.wav","audio/wav","PRIVATE_AUDIO_RAW");val image=source("板书.png","image/png","PRIVATE_IMAGE_RAW");val text=source("讲义.txt","text/plain","讲义原文")
        val engine=service();val a=engine.prepare(LEGACY_CHAT,"chat","解释三份资料",listOf(audio.id,image.id,text.id),LEGACY_CHAT,"default");setPlan(a)
        assertFalse(a.json().toString().contains("synthetic-key"))
        val waiting=engine.start(a);assertEquals("review",waiting.status);assertEquals(listOf("asr","ocr","parse"),tools)
        assertEquals(1,calls.size);assertFalse(calls.first().joinToString{it.content}.contains("PRIVATE_AUDIO_RAW"))
        try{engine.resume(a.id);fail()}catch(_:IllegalArgumentException){}
        assertEquals(1,calls.size);approve(a);assertEquals("complete",engine.resume(a.id).status)
        assertEquals(2,calls.size);assertTrue(calls.last().last().content.contains("人工核对的asr重点"))
        assertTrue(db.sessions().messages(LEGACY_CHAT).first().referenceText.contains("人工核对的ocr重点"))
        assertEquals("PRIVATE_IMAGE_RAW",repo.document(image.localName!!).readText())
        assertEquals(0,File(context.cacheDir,"workflow-sources").listFiles().orEmpty().size)
        try{engine.resume(a.id);fail()}catch(_:IllegalArgumentException){}
        assertEquals(2,calls.size)
    }
    @Test fun maliciousToolsCannotReadUnselectedSourcesOrChoosePaths()=runBlocking {
        val one=source("音频.wav","audio/wav","one");val other=source("其他.png","image/png","other")
        val engine=service();val a=engine.prepare(LEGACY_CHAT,"chat","整理",listOf(one.id),LEGACY_CHAT,"default")
        for(value in listOf(
            "{\"steps\":[{\"sourceId\":\"${other.id}\",\"tool\":\"ocr\"}]}",
            "{\"steps\":[{\"sourceId\":\"${one.id}\",\"tool\":\"delete\"}]}",
            "{\"steps\":[{\"sourceId\":\"${one.id}\",\"tool\":\"asr\",\"path\":\"/sdcard/private\"}]}")){
            try{WorkflowPlan.parse(value,a);fail()}catch(_:IllegalArgumentException){}
        }
        plan="{\"steps\":[{\"sourceId\":\"${one.id}\",\"tool\":\"ocr\"}]}"
        try{engine.start(a);fail()}catch(_:IllegalArgumentException){}
        assertTrue(tools.isEmpty());assertEquals("error",db.workflows().get(a.id)!!.status)
    }
    @Test fun sourceAndConfigurationChangesBlockFurtherPaidRequests()=runBlocking {
        val image=source("图.png","image/png","one");val engine=service();val a=engine.prepare(LEGACY_CHAT,"chat","解释",listOf(image.id),LEGACY_CHAT,"default");setPlan(a)
        engine.start(a);approve(a)
        settings.save(ProviderKind.OCR,"https://changed.example.invalid/v1","test-ocr","",false)
        try{engine.resume(a.id);fail()}catch(_:IllegalArgumentException){}
        assertEquals(1,calls.size)
        settings.save(ProviderKind.OCR,"https://ocr.example.invalid/v1","test-ocr","",false)
        repo.document(image.localName!!).writeText("changed")
        try{engine.resume(a.id);fail()}catch(_:IllegalArgumentException){}
        assertEquals(1,calls.size)
    }
    @Test fun existingConfirmedTextSkipsRecognitionAndKeepsProvenance()=runBlocking {
        val image=source("图.png","image/png","one");db.content().save(ContentResult(sourceId=image.id,sourceStamp=repo.sourceStamp(image),kind="ocr",status="confirmed",text="已核对的旧文字"))
        val engine=service();val a=engine.prepare(LEGACY_CHAT,"chat","解释",listOf(image.id),LEGACY_CHAT,"default");setPlan(a)
        assertEquals("complete",engine.start(a).status);assertTrue(tools.isEmpty());assertEquals(2,calls.size)
        assertTrue(calls.last().last().content.contains("已核对的旧文字"))
    }
    @Test fun organizeUsesGroupRuleAndStillRequiresWritePermission()=runBlocking {
        val image=source("板书.png","image/png","one");val engine=service();val a=engine.prepare(LEGACY_CHAT,"organize","提取定义",listOf(image.id),LEGACY_CHAT,"default","t");setPlan(a)
        engine.start(a);approve(a);val finished=engine.resume(a.id)
        assertTrue(calls.last().last().content.contains("列出定义和来源"));assertTrue(calls.last().last().content.contains("提取定义"))
        val task=db.dao().task(finished.organizeTaskId!!)!!;assertEquals("preview",task.status)
        assertTrue(db.dao().nodes().none{it.kind==NodeKind.OUTPUT})
        try{repo.executeOrganize(task.id,false,false);fail()}catch(_:IllegalArgumentException){}
        repo.executeOrganize(task.id,false,true)
        assertEquals(1,db.dao().nodes().count{it.kind==NodeKind.OUTPUT});assertEquals("one",repo.document(image.localName!!).readText())
    }
    @Test fun enablingSandboxAfterStartDoesNotGrantTheOldTaskAutoWrite()=runBlocking {
        val text=source("讲义.txt","text/plain","原文");val engine=service();val a=engine.prepare(LEGACY_CHAT,"organize","整理",listOf(text.id),LEGACY_CHAT,"default","t");setPlan(a)
        engine.start(a);approve(a);settings.sandbox(true);val result=engine.resume(a.id)
        assertEquals("preview",db.dao().task(result.organizeTaskId!!)!!.status)
        assertTrue(db.dao().nodes().none{it.kind==NodeKind.OUTPUT})
    }
    @Test fun permanentDeletionClearsHistoricalInjectedTextWithoutResurrection()=runBlocking {
        val image=source("图.png","image/png","one");val engine=service();val a=engine.prepare(LEGACY_CHAT,"chat","解释",listOf(image.id),LEGACY_CHAT,"default");setPlan(a)
        engine.start(a);approve(a);engine.resume(a.id)
        assertTrue(db.dao().chat().first().referenceText.isNotBlank())
        repo.deleteNode(image.id);repo.purgeTrash(db.features().allTrash().single().id)
        assertTrue(db.dao().chat().all{it.referenceText.isBlank()});assertEquals("[]",db.workflows().get(a.id)!!.inputs)
        assertEquals("invalidated",db.workflows().get(a.id)!!.status);assertTrue(db.content().forSource(image.id).isEmpty())
        try{engine.resume(a.id);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun processRestartInterruptsActiveWorkButKeepsReviewPending()=runBlocking {
        val text=source("文.txt","text/plain","原文");val engine=service();val a=engine.prepare(LEGACY_CHAT,"chat","解释",listOf(text.id),LEGACY_CHAT,"default");setPlan(a)
        engine.start(a);db.workflows().insert(AiWorkflow(newId(),LEGACY_CHAT,a.json().toString(),status="processing"))
        db.workflows().interrupt()
        assertEquals("review",db.workflows().get(a.id)!!.status);assertEquals(1,db.workflows().all().count{it.status=="interrupted"});assertEquals(1,calls.size)
    }
    @Test fun changesAfterPreviewBlockEvenThePlannerRequest()=runBlocking {
        val text=source("文.txt","text/plain","原文");val engine=service();val a=engine.prepare(LEGACY_CHAT,"chat","解释",listOf(text.id),LEGACY_CHAT,"default");setPlan(a)
        repo.document(text.localName!!).writeText("新版本")
        try{engine.start(a);fail()}catch(_:IllegalArgumentException){}
        assertTrue(calls.isEmpty());assertTrue(db.workflows().all().isEmpty())
        repo.document(text.localName!!).writeText("原文")
        val organize=engine.prepare(LEGACY_CHAT,"organize","整理",listOf(text.id),LEGACY_CHAT,"default","t");setPlan(organize)
        repo.saveNode(db.dao().nodes().single{it.id=="g"}.copy(rule="新的大组规则"))
        try{engine.start(organize);fail()}catch(_:IllegalArgumentException){}
        assertTrue(calls.isEmpty());assertTrue(db.workflows().all().isEmpty())
    }
    @Test fun cancellationKeepsOriginalAndRemovesItsImmutableCopy()=runBlocking {
        val image=source("图.png","image/png","原件");val entered=CompletableDeferred<Unit>()
        val engine=WorkflowService(repo,settings,processor,language(),{m->ProviderCredentials(m.endpoint,m.model,"synthetic-key")},{_,_,_,_,_->entered.complete(Unit);awaitCancellation()})
        val a=engine.prepare(LEGACY_CHAT,"chat","解释",listOf(image.id),LEGACY_CHAT,"default");setPlan(a)
        val job=launch{engine.start(a)};withTimeout(5000){entered.await()};job.cancelAndJoin()
        assertEquals("cancelled",db.workflows().get(a.id)!!.status);assertTrue(db.dao().chat().isEmpty())
        assertEquals("原件",repo.document(image.localName!!).readText());assertEquals(0,File(context.cacheDir,"workflow-sources").listFiles().orEmpty().size)
    }
    @Test fun permanentPurgeDuringRealLocalToolCannotResurrectItsResults()=runBlocking {
        val text=source("文.txt","text/plain","原文");val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val delayed=ContentProcessor(context,repo,settings,parser={_,_->entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS));"迟到解析文字"})
        val engine=WorkflowService(repo,settings,delayed,language(),{m->ProviderCredentials(m.endpoint,m.model,"synthetic-key")})
        val a=engine.prepare(LEGACY_CHAT,"chat","解释",listOf(text.id),LEGACY_CHAT,"default");setPlan(a)
        val job=async(Dispatchers.Default){runCatching{engine.start(a)}}
        try{
            assertTrue(withContext(Dispatchers.IO){entered.await(5,java.util.concurrent.TimeUnit.SECONDS)})
            repo.deleteNode(text.id);repo.purgeTrash(db.features().allTrash().single().id);release.countDown()
            assertTrue(withTimeout(5000){job.await()}.isFailure)
            assertEquals("invalidated",db.workflows().get(a.id)!!.status);assertTrue(db.content().forSource(text.id).isEmpty());assertTrue(db.dao().chat().isEmpty())
        }finally{release.countDown();delayed.shutdown()}
    }
    @Test fun schemaFiveMigrationPreservesExistingMessagesResultsAndSessions()=runBlocking {
        val file=context.getDatabasePath("workflow-upgrade-v5.db");file.parentFile!!.mkdirs()
        val entities=JSONObject(File("schemas/cn.campus.notes.data.CampusDatabase/5.json").readText()).getJSONObject("database").getJSONArray("entities")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use{raw->
            for(i in 0 until entities.length()){val e=entities.getJSONObject(i);raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")));val indices=e.optJSONArray("indices")?:JSONArray();for(j in 0 until indices.length())raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))}
            raw.execSQL("INSERT INTO chat_sessions VALUES ('legacy','旧会话','legacy','default','旧草稿',1,2)")
            raw.execSQL("INSERT INTO chat_messages(role,content,status,detail,createdAt) VALUES ('assistant','旧回答','complete','',3)")
            raw.execSQL("INSERT INTO content_results VALUES ('r','original','stamp','ocr','confirmed','旧识别文字','','旧服务',NULL,1,2)");raw.version=5
        }
        val upgraded=Room.databaseBuilder(context,CampusDatabase::class.java,file.name).addMigrations(CampusDatabase.MIGRATION_5_6).build()
        try{assertEquals("旧草稿",upgraded.sessions().session(LEGACY_CHAT)!!.draft);assertEquals("旧回答",upgraded.dao().chat().single().content);assertEquals("",upgraded.dao().chat().single().referenceText);assertEquals("旧识别文字",upgraded.content().result("r")!!.text);assertEquals("",upgraded.content().result("r")!!.diagnostics);assertTrue(upgraded.workflows().all().isEmpty())}finally{upgraded.close()}
    }
}
