package cn.campus.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Explicit opt-in only. Two paid requests, synthetic inputs; secrets remain on the device. */
@RunWith(AndroidJUnit4::class)
class RecognitionOnlineDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun approved()=assumeTrue("Online recognition requires explicit user approval",InstrumentationRegistry.getArguments().getString("recognitionOnline")=="approved")
    private suspend fun configured(kind:ProviderKind):ProviderCredentials {
        val settings=SettingsStore(instrumentation.targetContext)
        val example=RecognitionConfiguration.qwenExample(kind)
        RecognitionConfiguration.validate(kind,example.endpoint,example.model)
        val old=settings.credentials(kind) // Check the existing encrypted key before saving metadata.
        settings.save(kind,example.endpoint,example.model,"",false)
        val next=settings.credentials(kind)
        assertTrue("Existing key must remain unchanged",old.key==next.key)
        return next
    }
    @Test fun asrRecognizesApprovedSyntheticSpeech()=runBlocking {
        approved()
        val fixture=File(instrumentation.targetContext.cacheDir,"campus-recognition-synthetic.wav")
        require(fixture.isFile&&fixture.length() in 44..300_000){"Synthetic WAV fixture missing or invalid"}
        val bytes=fixture.readBytes()
        val expected=InstrumentationRegistry.getArguments().getString("audioSha256").orEmpty().lowercase()
        require(Regex("[a-f0-9]{64}").matches(expected)){"Synthetic audio digest required"}
        val actual=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        require(expected==actual){"Synthetic audio digest mismatch"}
        val text=RecognitionProvider(configured(ProviderKind.ASR)).transcribe(bytes,"audio/wav").lowercase()
        assertTrue("ASR must recognize the synthetic test speech",text.contains("campus")&&text.contains("original"))
    }
    @Test fun ocrRecognizesApprovedSyntheticTextImage()=runBlocking {
        approved()
        val bitmap=Bitmap.createBitmap(960,360,Bitmap.Config.ARGB_8888)
        val image=try{
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=48f}
            canvas.drawText("CampusNotes OCR Test",40f,80f,paint)
            canvas.drawText("Course: Mathematics",40f,160f,paint)
            canvas.drawText("Room: A203",40f,240f,paint)
            ByteArrayOutputStream().use{out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray()}
        }finally{bitmap.recycle()}
        val text=RecognitionProvider(configured(ProviderKind.OCR)).recognize(image,"image/png").lowercase().replace(Regex("[^a-z0-9]"),"")
        assertTrue("OCR must recognize the synthetic test text",text.contains("campusnotesocrtest")&&text.contains("rooma203"))
    }
}
