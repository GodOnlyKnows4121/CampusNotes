package cn.campus.notes

import cn.campus.notes.data.ProviderCredentials
import cn.campus.notes.integration.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecognitionProtocolTest {
    private fun tls(block: (MockWebServer, OkHttpClient) -> Unit) {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false); start() }
        val client = DeepSeekProvider.defaultClient.newBuilder().dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool())
            .sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build()
        try { block(server,client) } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.shutdown() }
    }
    private fun adapter(server: MockWebServer, client: OkHttpClient) = RecognitionProvider(ProviderCredentials(server.url("/compatible-mode/v1").toString(), "configured-model", "test-only-token"),client)
    private fun reply(text: String = "识别文字", finish: String = "stop") = MockResponse().setHeader("Content-Type","application/json")
        .setBody(JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", finish).put("message", JSONObject().put("content",text)))).toString())
    @Test fun audioAndImageUseIndependentCompatiblePayloads() = tls { server, client -> runBlocking {
        server.enqueue(reply()); assertEquals("识别文字", adapter(server,client).transcribe(byteArrayOf(1,2,3),"audio/mp4"))
        val asr = server.takeRequest(2,TimeUnit.SECONDS)!!
        assertEquals("/compatible-mode/v1/chat/completions",asr.path)
        assertEquals("Bearer test-only-token", asr.getHeader("Authorization"))
        val audio = JSONObject(asr.body.readUtf8())
        assertFalse(audio.getBoolean("stream")); assertEquals("configured-model", audio.getString("model"))
        val item = audio.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("input_audio",item.getString("type")); assertEquals("data:audio/mp4;base64,AQID", item.getJSONObject("input_audio").getString("data"))
        server.enqueue(reply()); adapter(server,client).recognize(byteArrayOf(4,5,6),"image/png")
        val image = JSONObject(server.takeRequest(2,TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image_url",image.getJSONObject(0).getString("type"))
        assertEquals("data:image/png;base64,BAUG",image.getJSONObject(0).getJSONObject("image_url").getString("url"))
        assertEquals("text",image.getJSONObject(1).getString("type"))
    } }
    @Test fun failedAndRedirectedRequestsNeverLeakServerBodiesOrRetry() = tls { server, client -> runBlocking {
        for(code in listOf(401,405,429,302,500)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location",server.url("/other")).setBody("test-only-token PRIVATE_CONTENT"))
            try { adapter(server,client).recognize(byteArrayOf(1),"image/jpeg"); fail() } catch(e: AiFailure) {
                assertFalse(e.message!!.contains("PRIVATE_CONTENT")); assertFalse(e.message!!.contains("test-only-token"))
            }
        }
        assertEquals(5,server.requestCount)
    } }
    @Test fun invalidProtocolIsRejectedBeforeAnyNetworkUpload() = tls { server, client -> runBlocking {
        for(path in listOf("/apps/anthropic","/apps/anthropic/v1/messages","/v1/responses","/v1/audio/transcriptions","/api/v1/services/audio/asr/transcription")) {
            val adapter=RecognitionProvider(ProviderCredentials(server.url(path).toString(),"configured-model","test-only-token"),client)
            try{adapter.transcribe(byteArrayOf(1),"audio/wav");fail("Wrong protocol should fail locally")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("协议"))}
            try{adapter.recognize(byteArrayOf(1),"image/png");fail("Wrong protocol should fail locally")}catch(_:IllegalArgumentException){}
        }
        assertEquals(0,server.requestCount)
    } }
    @Test fun officialExamplesAndRecognitionCapabilitiesAreValidatedWithoutNetwork() {
        for(kind in listOf(cn.campus.notes.data.ProviderKind.ASR,cn.campus.notes.data.ProviderKind.OCR)) {
            val preset=RecognitionConfiguration.qwenExample(kind)
            assertNull(RecognitionConfiguration.problem(kind,preset))
            assertNull(RecognitionConfiguration.problem(kind,preset.copy(endpoint=preset.endpoint+"/chat/completions")))
            assertNotNull(RecognitionConfiguration.problem(kind,preset.copy(model="qwen")))
            assertNotNull(RecognitionConfiguration.problem(kind,preset.copy(endpoint="https://maas.qianwenaiapi.com/apps/anthropic")))
        }
        val asr=cn.campus.notes.data.ProviderKind.ASR;val ocr=cn.campus.notes.data.ProviderKind.OCR
        assertNotNull(RecognitionConfiguration.problem(asr,RecognitionConfiguration.qwenExample(ocr)))
        assertNotNull(RecognitionConfiguration.problem(ocr,RecognitionConfiguration.qwenExample(asr)))
        assertNotNull(RecognitionConfiguration.problem(asr,RecognitionConfiguration.qwenExample(asr).copy(model="qwen3-asr-flash-filetrans")))
        assertNull(RecognitionConfiguration.problem(asr,cn.campus.notes.data.ProviderConfig("https://custom.example.invalid/v1","custom-asr")))
        assertTrue(DeepSeekProvider.httpFailure(405).message!!.contains("POST"))
    }
    @Test fun incompleteMalformedAndEmptyResultsNeverBecomeReviewableText() = tls { server, client -> runBlocking {
        for(response in listOf(reply("partial","length"), reply(""), MockResponse().setHeader("Content-Type","application/json").setBody("{broken"))) {
            server.enqueue(response)
            try { adapter(server,client).transcribe(byteArrayOf(1),"audio/wav"); fail() } catch(_: AiFailure) { }
        }
    } }
    @Test fun inputLimitsAndUnsupportedMimeRejectBeforeUpload() = tls { server, client -> runBlocking {
        try { adapter(server,client).transcribe(ByteArray(RecognitionProvider.MAX_INPUT + 1),"audio/mp4"); fail() } catch(_: IllegalArgumentException) { }
        try { adapter(server,client).recognize(byteArrayOf(1),"application/pdf"); fail() } catch(_: IllegalArgumentException) { }
        assertEquals(0,server.requestCount)
    } }
    @Test fun cancellingSlowRequestCancelsTransportWithoutRetry() = tls { server, client -> runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { adapter(server,client).transcribe(byteArrayOf(1),"audio/mp4") }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) })
        job.cancelAndJoin(); assertTrue(job.isCancelled)
        assertEquals(1,server.requestCount)
    } }
    @Test fun timeoutNeverClaimsAnUnreturnedRecognitionResultWasSaved() = tls { server, client -> runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val bounded=client.newBuilder().readTimeout(150,TimeUnit.MILLISECONDS).callTimeout(500,TimeUnit.MILLISECONDS).build()
        try{adapter(server,bounded).recognize(byteArrayOf(1),"image/png");fail()}catch(e:AiFailure){
            assertTrue(e.message!!.contains("超时"));assertTrue(e.message!!.contains("原件保留"))
            assertFalse(e.message!!.contains("已保留部分回复"))
        }
        assertEquals(1,server.requestCount)
    } }
    @Test fun servicePartialResponseCannotBecomeACompleteOcrResult() = tls { server,client -> runBlocking {
        server.enqueue(reply("未完成文字").setHeader("x-dashscope-partialresponse","true"))
        try{adapter(server,client).recognize(byteArrayOf(1),"image/png");fail()}catch(e:AiFailure){assertTrue(e.message!!.contains("未完成"))}
        assertEquals(1,server.requestCount)
    } }
    @Test fun ocrWaitPolicyDoesNotChangeChatAsrOrTransportSafety() {
        assertEquals(300_000,RecognitionProvider.ocrClient.readTimeoutMillis)
        assertEquals(330_000,RecognitionProvider.ocrClient.callTimeoutMillis)
        assertEquals(60_000,DeepSeekProvider.defaultClient.readTimeoutMillis)
        assertEquals(180_000,DeepSeekProvider.defaultClient.callTimeoutMillis)
        assertFalse(RecognitionProvider.ocrClient.retryOnConnectionFailure)
        assertFalse(RecognitionProvider.ocrClient.followRedirects);assertFalse(RecognitionProvider.ocrClient.followSslRedirects)
    }
    @Test fun cancellationStillInterruptsLongOcrWaitPromptly() = tls { server,client -> runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val waiting=client.newBuilder().readTimeout(300,TimeUnit.SECONDS).callTimeout(330,TimeUnit.SECONDS).build()
        val job=launch(Dispatchers.IO){adapter(server,waiting).recognize(byteArrayOf(1),"image/png")}
        assertNotNull(withContext(Dispatchers.IO){server.takeRequest(3,TimeUnit.SECONDS)})
        withTimeout(2000){job.cancelAndJoin()}
        withTimeout(2000){while(waiting.dispatcher.runningCallsCount()!=0)delay(10)}
        assertEquals(1,server.requestCount)
    } }
}
