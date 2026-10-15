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
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

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
        val client=RecognitionProvider.ocrClient.newBuilder().eventListener(SafeTiming()).build()
        val text=RecognitionProvider(configured(ProviderKind.OCR),client).recognize(image,"image/png").lowercase().replace(Regex("[^a-z0-9]"),"")
        assertTrue("OCR must recognize the synthetic test text",text.contains("campusnotesocrtest")&&text.contains("rooma203"))
    }
    /** Metadata only: never log URLs, headers, addresses, credentials, request/response content. */
    private class SafeTiming:EventListener(){
        private val start=android.os.SystemClock.elapsedRealtime()
        private fun mark(stage:String){
            val message="OCR_TIMING ${android.os.SystemClock.elapsedRealtime()-start}ms $stage\n"
            InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply{putString("stream",message)})
        }
        override fun callStart(call:Call)=mark("call_start")
        override fun dnsStart(call:Call,domainName:String)=mark("dns_start")
        override fun dnsEnd(call:Call,domainName:String,inetAddressList:List<InetAddress>)=mark("dns_end")
        override fun connectStart(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy)=mark("connect_start")
        override fun secureConnectStart(call:Call)=mark("tls_start")
        override fun secureConnectEnd(call:Call,handshake:Handshake?)=mark("tls_end")
        override fun connectEnd(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy,protocol:Protocol?)=mark("connect_end")
        override fun requestBodyEnd(call:Call,byteCount:Long)=mark("upload_complete")
        override fun responseHeadersEnd(call:Call,response:Response)=mark("response_headers_http_${response.code}")
        override fun responseBodyEnd(call:Call,byteCount:Long)=mark("response_body_complete")
        override fun callEnd(call:Call)=mark("call_end")
        override fun callFailed(call:Call,ioe:IOException)=mark("call_failed_${ioe.javaClass.simpleName}")
    }
}
