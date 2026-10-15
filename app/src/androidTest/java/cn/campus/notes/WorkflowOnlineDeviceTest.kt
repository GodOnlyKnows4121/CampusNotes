package cn.campus.notes

import android.graphics.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opt-in live chain, isolated DB and synthetic originals only. Never logs credentials or bodies. */
@RunWith(AndroidJUnit4::class)
class WorkflowOnlineDeviceTest {
    private fun mark(stage:String)=InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply{putString("stream","WORKFLOW_TEST $stage\n")})
    @Test fun deepSeekPlansAsrOcrAndParseThenAnswersAfterReview()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit approval required for paid synthetic workflow",args.getString("workflowOnline")=="approved")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val audio=File(context.cacheDir,"campus-recognition-synthetic.wav").readBytes()
        val digest=MessageDigest.getInstance("SHA-256").digest(audio).joinToString(""){"%02x".format(it)}
        require(digest==args.getString("audioSha256")){"Synthetic audio digest mismatch"}
        val bitmap=Bitmap.createBitmap(960,360,Bitmap.Config.ARGB_8888)
        val image=try{val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=48f}
            canvas.drawText("CampusNotes OCR Test",40f,80f,paint);canvas.drawText("Course: Mathematics",40f,160f,paint);canvas.drawText("Room: A203",40f,240f,paint)
            ByteArrayOutputStream().use{out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray()}
        }finally{bitmap.recycle()}
        val document=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{zip->zip.putNextEntry(ZipEntry("word/document.xml"));zip.write("<document><p><t>Teaching topic: Limits. Preserve original files.</t></p></document>".toByteArray());zip.closeEntry()}}.toByteArray()
        val db=Room.inMemoryDatabaseBuilder(context,CampusDatabase::class.java).build();val repo=LocalRepository(context,db)
        val settings=SettingsStore(context);val processor=ContentProcessor(context,repo,settings)
        var ticker:Job?=null
        try{
            val session=ChatSessionStore(db).create()
            val topic=repo.inboxTopic()
            repo.importStream(topic,"合成课堂音频.wav","audio/wav",ByteArrayInputStream(audio))
            repo.importStream(topic,"合成板书.png","image/png",ByteArrayInputStream(image))
            repo.importStream(topic,"合成讲义.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",ByteArrayInputStream(document))
            val originals=db.dao().nodes().filter{it.kind==NodeKind.ORIGINAL}
            val before=originals.associate{it.id to repo.sourceStamp(it)}
            val engine=WorkflowService(repo,settings,processor)
            val approval=engine.prepare(session.id,"chat","请综合所有三份资料，说明课程、教室，并列出学习要点。所有来源都需要使用，并标注资料编号。",originals.map{it.id},LEGACY_CHAT,"light")
            mark("prepared_three_synthetic_sources")
            ticker=launch(Dispatchers.IO){while(isActive){delay(15_000);val state=db.workflows().get(approval.id);if(state!=null)mark("stage_${state.status}_completed_${WorkflowResult.read(state.results).size}")}}
            val waiting=engine.start(approval)
            assertEquals("review",waiting.status)
            val processed=WorkflowResult.read(waiting.results)
            assertEquals(setOf("asr","ocr","parse"),processed.map{it.tool}.toSet())
            assertTrue(db.dao().chat().isEmpty())
            for(item in processed){
                val result=db.content().result(item.resultId!!)!!
                assertEquals("review",result.status)
                if(item.tool in setOf("asr","ocr")){
                    val trace=org.json.JSONObject(result.diagnostics)
                    assertEquals("complete",trace.getString("outcome"));assertEquals(200,trace.getInt("httpStatus"))
                    mark("${item.tool}_http_200_elapsed_ms_${trace.getLong("elapsedMs")}")
                }
                when(item.tool){
                    "asr"->assertTrue("Synthetic speech must be recognized",result.text.contains("campus",true)&&result.text.contains("original",true))
                    "ocr"->assertTrue("Synthetic classroom number must be recognized",result.text.replace(" ","").contains("A203",true))
                    "parse"->assertTrue(result.text.contains("Limits"))
                }
                processor.confirm(result.id,result.text) // Known synthetic fixtures, never personal recognition results.
            }
            mark("all_synthetic_results_verified_and_confirmed")
            val completed=engine.resume(approval.id)
            assertEquals("complete",completed.status)
            val reply=db.sessions().messages(session.id).last()
            assertEquals("complete",reply.status)
            assertTrue("Final answer must use OCR evidence",reply.content.replace(Regex("\\s+"),"").contains("A203",true))
            assertTrue(db.sessions().messages(session.id).first().referenceText.contains("Teaching topic"))
            originals.forEach{assertTrue("Synthetic original must remain unchanged",before[it.id]==repo.sourceStamp(it))}
            mark("live_deepseek_asr_ocr_parse_review_answer_chain_passed")
        }finally{
            ticker?.cancelAndJoin();processor.shutdown()
            db.dao().nodes().mapNotNull{it.localName}.forEach{repo.document(it).delete()}
            db.close()
        }
    }
}
