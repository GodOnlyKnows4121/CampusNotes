package cn.campus.notes

import android.media.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

/** Synthetic local files only: no microphone, network calls or writes to the personal database. */
@RunWith(AndroidJUnit4::class)
class ContentLocalDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualPdfTextExtractsOnAndroidWithoutNetwork() {
        val file=File.createTempFile("synthetic-document-", ".pdf",context.cacheDir)
        try {
            PDFBoxResourceLoader.init(context)
            PDDocument().use { doc ->
                val page=PDPage();doc.addPage(page)
                PDPageContentStream(doc,page).use { out -> out.beginText();out.setFont(PDType1Font.HELVETICA,12f);out.newLineAtOffset(50f,700f);out.showText("Synthetic campus PDF");out.endText() }
                doc.save(file)
            }
            val before=MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val text=DocumentParser(context).extract(file,LibraryNode(kind=NodeKind.ORIGINAL,title="test.pdf",mime="application/pdf"))
            assertTrue(text.contains("Synthetic campus PDF"))
            assertArrayEquals(before,MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { file.delete() }
    }
    @Test fun localExtractionRequiresConfirmationAndPreservesOriginalOnAndroid() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context,CampusDatabase::class.java).build()
        val repo = LocalRepository(context,db)
        val processor = ContentProcessor(context,repo,SettingsStore(context))
        try {
            repo.importStream(repo.inboxTopic(),"合成设备测试.txt","text/plain",ByteArrayInputStream("合成解析内容".toByteArray()))
            val original = db.dao().nodes().single { it.localName != null }
            processor.start(original.id,"parse")
            val result = withTimeout(10_000) {
                while(true) {
                    val row=db.content().forSource(original.id).firstOrNull()
                    if(row?.status == "review") return@withTimeout row
                    if(row?.status == "failed") error("Local parsing failed")
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            assertTrue(db.content().confirmed().isEmpty())
            processor.confirm(result.id,"合成核对内容")
            processor.saveAsNote(result.id)
            assertEquals("合成核对内容",db.dao().notes().single().body)
            assertEquals("合成解析内容",repo.document(original.localName!!).readText())
        } finally {
            processor.shutdown()
            db.dao().nodes().mapNotNull { it.localName }.forEach { repo.document(it).delete() }
            db.close()
        }
    }
    @Test fun syntheticAacSplitsWithoutLostSamplesAndPreservesSource() = runBlocking {
        val source = File.createTempFile("synthetic-audio-", ".m4a",context.cacheDir)
        try {
            createSilence(source)
            val before=MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            val originalCount=countSamples(source)
            var count=0; var parts=0; var lastOffset = -1L
            AudioSegments(context,1).transcribe(source,"audio/mp4") { bytes,mime,index,offset ->
                assertEquals("audio/mp4",mime); assertTrue(bytes.size <= RecognitionProvider.MAX_INPUT)
                assertEquals(parts+1,index); assertTrue(offset > lastOffset)
                val part=File.createTempFile("synthetic-part-", ".m4a",context.cacheDir)
                try { part.writeBytes(bytes); count += countSamples(part) } finally { part.delete() }
                parts++; lastOffset=offset
            }
            assertTrue(parts >= 3); assertEquals(originalCount,count)
            assertArrayEquals(before,MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
        } finally { source.delete() }
    }
    private fun countSamples(file: File): Int {
        val ex=MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath); ex.selectTrack(0)
            var count=0; var previous = -1L
            while(ex.sampleTime >= 0) { assertTrue(ex.sampleTime >= previous); previous=ex.sampleTime;count++;ex.advance() }
            return count
        } finally { ex.release() }
    }
    private fun createSilence(file: File) {
        val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,16000,1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,32000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4096)
        }
        val codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted=false;var track=-1
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            var samples=0;var inputDone=false;var outputDone=false
            val deadline=android.os.SystemClock.elapsedRealtime()+15_000
            val info=MediaCodec.BufferInfo()
            while(!outputDone) {
                check(android.os.SystemClock.elapsedRealtime()<deadline) { "Synthetic AAC encoding timed out" }
                if(!inputDone) {
                    val input=codec.dequeueInputBuffer(10_000)
                    if(input>=0) {
                        val buffer=codec.getInputBuffer(input)!!;buffer.clear()
                        val frames=minOf(buffer.remaining()/2,16000*4-samples,1024)
                        if(frames>0) {
                            buffer.put(ByteArray(frames*2));codec.queueInputBuffer(input,0,frames*2,samples*1_000_000L/16000,0);samples+=frames
                        } else { codec.queueInputBuffer(input,0,0,samples*1_000_000L/16000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true }
                    }
                }
                val output=codec.dequeueOutputBuffer(info,10_000)
                if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track=muxer.addTrack(codec.outputFormat);muxer.start();muxerStarted=true }
                else if(output>=0) {
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) muxer.writeSampleData(track,codec.getOutputBuffer(output)!!,info)
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;codec.releaseOutputBuffer(output,false)
                }
            }
        } finally {
            try { codec.stop() } finally { codec.release() }
            try { if(muxerStarted)muxer.stop() } finally { muxer.release() }
        }
    }
}
