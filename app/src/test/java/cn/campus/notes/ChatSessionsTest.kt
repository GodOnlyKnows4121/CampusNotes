package cn.campus.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ChatSessionsTest {
    private lateinit var db:CampusDatabase
    private lateinit var context:Context
    private lateinit var sessions:ChatSessionStore
    @Before fun setup(){context=ApplicationProvider.getApplicationContext();db=Room.inMemoryDatabaseBuilder(context,CampusDatabase::class.java).build();sessions=ChatSessionStore(db)}
    @After fun close(){db.close()}
    private fun fake(block:(List<PromptMessage>)->Flow<String>)=object:AiProvider{override fun streamReply(messages:List<PromptMessage>,json:Boolean)=block(messages)}
    private fun credentials(model:String="test-model")=ProviderCredentials("https://example.invalid/v1",model,"test-only-token")
    @Test fun independentHistoriesNeverEnterAnotherSessionContext()=runBlocking {
        sessions.initialize();val a=sessions.create();val b=sessions.create()
        val contexts=mutableListOf<List<PromptMessage>>()
        val service=ChatService(db,{credentials()}){fake{messages->contexts+=messages;flowOf("合成回答")}}
        service.send("A的问题",sessionId=a.id);service.send("B的问题",sessionId=b.id);service.send("A的追问",sessionId=a.id)
        assertEquals(listOf("B的问题"),contexts[1].drop(1).map{it.content})
        assertEquals(listOf("A的问题","合成回答","A的追问"),contexts[2].drop(1).map{it.content})
        assertEquals(4,db.sessions().messages(a.id).size);assertEquals(2,db.sessions().messages(b.id).size)
    }
    @Test fun draftsSelectionRenameAndDeletionAreScoped()=runBlocking {
        val a=sessions.create("model-a");val b=sessions.create("model-b")
        db.sessions().draft(a.id,"草稿A");db.sessions().draft(b.id,"草稿B")
        db.sessions().selection(a.id,"new-model","default");sessions.rename(a.id,"数学复习")
        assertEquals("草稿B",db.sessions().session(b.id)!!.draft)
        assertEquals("model-b",db.sessions().session(b.id)!!.profileId)
        assertEquals("数学复习",db.sessions().session(a.id)!!.title)
        sessions.delete(a.id);assertNull(db.sessions().session(a.id));assertEquals("草稿B",db.sessions().session(b.id)!!.draft)
        val next=sessions.delete(b.id);assertEquals(next,db.sessions().all().single().id);assertEquals("",db.sessions().session(next)!!.draft)
    }
    @Test fun clearingOnlyOneSessionKeepsOtherHistoryAndDraft()=runBlocking {
        val a=sessions.create();val b=sessions.create();db.sessions().draft(a.id,"保留草稿")
        val service=ChatService(db,{credentials()}){fake{flowOf("回答")}}
        service.send("A",sessionId=a.id);service.send("B",sessionId=b.id)
        sessions.clear(a.id)
        assertTrue(db.sessions().messages(a.id).isEmpty());assertEquals(2,db.sessions().messages(b.id).size)
        assertEquals("保留草稿",db.sessions().session(a.id)!!.draft)
    }
    @Test fun changingSessionSelectionDuringStreamDoesNotChangeRequestSnapshot()=runBlocking {
        val a=sessions.create("first");val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var model="first-model";var reads=0
        val service=ChatService(db,{reads++;credentials(model)}){frozen->fake{flow{assertEquals("first-model",frozen.model);emit("第一段");started.complete(Unit);release.await();emit("第二段")}}}
        val job=launch{service.send("问题",sessionId=a.id,profileId="first")}
        withTimeout(5000){started.await()};model="second-model";db.sessions().selection(a.id,"second","default")
        try{sessions.delete(a.id);fail()}catch(_:IllegalArgumentException){}
        release.complete(Unit);job.join()
        assertEquals(1,reads);assertTrue(db.sessions().messages(a.id).all{it.modelName=="first-model"&&it.profileId=="first"})
        assertEquals("second",db.sessions().session(a.id)!!.profileId)
    }
    @Test fun cancellationRetainsPartialOnlyInOriginalSession()=runBlocking {
        val a=sessions.create();val b=sessions.create();val started=CompletableDeferred<Unit>()
        val service=ChatService(db,{credentials()}){fake{flow{emit("部分文字");started.complete(Unit);awaitCancellation()}}}
        val job=launch{service.send("问题",sessionId=a.id)};withTimeout(5000){started.await()};job.cancelAndJoin()
        assertEquals("cancelled",db.sessions().messages(a.id).last().status)
        assertTrue(db.sessions().messages(b.id).isEmpty());assertEquals(2,ChatService.context(db.sessions().messages(a.id),"下一问").size)
    }
    @Test fun profileMetadataDoesNotRewriteLegacyOrRecognitionSettings()=runBlocking {
        val settings=SettingsStore(context)
        settings.save(ProviderKind.ASR,"https://asr.example.invalid/v1","asr-model","",false)
        val id=newId();settings.saveChatProfile(id,"语言模型A","https://chat.example.invalid/v1","chat-model","",false)
        assertEquals("asr-model",settings.data.first().providers[ProviderKind.ASR]!!.model)
        assertEquals("chat-model",settings.data.first().chatProfiles.single{it.id==id}.model)
        settings.saveChatProfile(id,"语言模型B","https://chat.example.invalid/v1","next-model","",false)
        assertEquals(1,settings.data.first().chatProfiles.count{it.id==id})
        settings.deleteChatProfile(id);assertFalse(settings.data.first().chatProfiles.any{it.id==id})
        assertEquals("asr-model",settings.data.first().providers[ProviderKind.ASR]!!.model)
    }
    @Test fun restartRestoresDraftSelectionAndActiveSession()=runBlocking {
        val name="sessions-restart.db";val stored=Room.databaseBuilder(context,CampusDatabase::class.java,name).build()
        val settings=SettingsStore(context)
        val id=try{val s=ChatSessionStore(stored).create("saved-profile");stored.sessions().draft(s.id,"未发送的草稿");stored.sessions().selection(s.id,"saved-profile","deep");settings.activeChatSession(s.id);s.id}finally{stored.close()}
        val reopened=Room.databaseBuilder(context,CampusDatabase::class.java,name).build()
        try{assertEquals(id,settings.data.first().activeChatSession);val session=reopened.sessions().session(id)!!;assertEquals("未发送的草稿",session.draft);assertEquals("saved-profile",session.profileId);assertEquals("deep",session.reasoning)}finally{reopened.close()}
    }
    @Test fun lateStreamCannotResurrectRemovedSessionOrMessage()=runBlocking {
        val a=sessions.create();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val service=ChatService(db,{credentials()}){fake{flow{emit("第一段");started.complete(Unit);release.await();emit("迟到内容")}}}
        val job=launch{service.send("合成问题",sessionId=a.id)};withTimeout(5000){started.await()}
        db.sessions().clear(a.id);db.sessions().delete(a.id);release.complete(Unit);withTimeout(5000){job.join()}
        assertNull(db.sessions().session(a.id));assertTrue(db.dao().chat().isEmpty())
    }
    @Test fun actualReasoningParametersAreSupportedOnlyForKnownModelAndHost() {
        for(level in ThinkingLevel.entries){val payload=JSONObject();ModelCapabilities.apply(payload,"https://api.deepseek.com/v1","deepseek-flash",level.id)
            if(level==ThinkingLevel.DEFAULT){assertFalse(payload.has("thinking"));assertFalse(payload.has("reasoning_effort"))}
            else{assertEquals("enabled",payload.getJSONObject("thinking").getString("type"));assertEquals(level.effort,payload.getString("reasoning_effort"))}
            assertFalse(payload.has("temperature"));assertFalse(payload.has("max_tokens"))
        }
        assertEquals(listOf(ThinkingLevel.DEFAULT),ModelCapabilities.levels("https://api.deepseek.com.evil.invalid","deepseek-flash"))
        assertEquals(listOf(ThinkingLevel.DEFAULT),ModelCapabilities.levels("https://api.deepseek.com","unknown"))
        try{ModelCapabilities.apply(JSONObject(),"https://other.example.invalid","qwen","deep");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun schemaFourMigratesOldMessagesAndTaskMetadataWithoutGuessingModels()=runBlocking {
        val file=context.getDatabasePath("sessions-upgrade-v4.db");file.parentFile!!.mkdirs()
        val entities=JSONObject(File("schemas/cn.campus.notes.data.CampusDatabase/4.json").readText()).getJSONObject("database").getJSONArray("entities")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use{raw->
            for(i in 0 until entities.length()){val e=entities.getJSONObject(i);raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")));val indices=e.optJSONArray("indices")?:org.json.JSONArray();for(j in 0 until indices.length())raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))}
            raw.execSQL("INSERT INTO chat_messages(role,content,status,detail,createdAt) VALUES ('user','旧问题','complete','',10)")
            raw.execSQL("INSERT INTO chat_messages(role,content,status,detail,createdAt) VALUES ('assistant','旧回答','complete','',20)")
            raw.execSQL("INSERT INTO organize_tasks VALUES ('old-task','topic','旧主题','保留原件','旧来源','stamp','旧成果','成果正文','complete','已完成','output',30)")
            raw.execSQL("INSERT INTO content_results VALUES ('r','original','hash','parse','confirmed','已核对文字','','本机解析',NULL,1,1)")
            raw.version=4
        }
        val upgraded=Room.databaseBuilder(context,CampusDatabase::class.java,file.name).addMigrations(CampusDatabase.MIGRATION_4_5, CampusDatabase.MIGRATION_5_6).build()
        try{assertEquals("历史会话",upgraded.sessions().all().single().title);val messages=upgraded.sessions().messages(LEGACY_CHAT);assertEquals(listOf("旧问题","旧回答"),messages.map{it.content});assertTrue(messages.all{it.modelName.isEmpty()});assertEquals("已核对文字",upgraded.content().result("r")!!.text)
            val task=upgraded.dao().task("old-task")!!;assertEquals("成果正文",task.body);assertEquals("output",task.outputId);assertEquals("complete",task.status);assertEquals("",task.modelName)
        }finally{upgraded.close()}
    }
}
