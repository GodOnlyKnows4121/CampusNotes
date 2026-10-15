package cn.campus.notes

import cn.campus.notes.data.ProviderCredentials
import cn.campus.notes.integration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiProtocolTest {
    private val ending = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
    private fun chunk(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n"
    private fun parse(value: String): String { val output = StringBuilder(); SseReader.read(StringReader(value).buffered()) { output.append(it) }; return output.toString() }
    @Test fun sseHandlesCommentsCrLfReasoningAndUtf8Content() {
        val source = ": keep alive\r\n\r\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"ignored\"}}]}\r\n\r\n" + chunk("校园") + chunk("笔记") + ending
        assertEquals("校园笔记", parse(source))
    }
    @Test fun incompleteMalformedAndOversizedResponsesFail() {
        for (value in listOf(chunk("partial"), chunk("partial") + ending.replace("stop", "length"), "data: not json\n\n", "data: " + "x".repeat(64_001), "data: [DONE]\n\n")) {
            try { parse(value); fail("Response should be rejected") } catch (_: AiFailure) { }
        }
    }
    @Test fun unsupportedAgentOperationsAndPathsFailClosed() {
        assertEquals("课堂摘要", OrganizePlan.parse("{\"title\":\"课堂摘要\",\"body\":\"要点\"}").title)
        for (value in listOf("{\"title\":\"../x\",\"body\":\"x\"}", "{\"title\":\"ok\",\"body\":\"x\",\"delete\":\"original\"}", "{\"title\":false,\"body\":\"x\"}")) {
            try { OrganizePlan.parse(value); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun completionUrlAcceptsBaseOrFullEndpointAndRejectsHttp() {
        assertEquals("https://example.com/v1/chat/completions", DeepSeekProvider.completionUrl("https://example.com/v1/").toString())
        assertEquals("https://example.com/v1/chat/completions", DeepSeekProvider.completionUrl("https://example.com/v1/chat/completions").toString())
        try { DeepSeekProvider.completionUrl("http://example.com"); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(ProviderCredentials("x", "x", "unit-test-token").toString().contains("unit-test-token"))
    }
    private fun tlsServer(protocols: List<okhttp3.Protocol> = listOf(okhttp3.Protocol.HTTP_1_1), block: (MockWebServer, okhttp3.OkHttpClient) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.protocols = protocols
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val client = DeepSeekProvider.defaultClient.newBuilder()
            .protocols(protocols)
            .dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool())
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
        try { block(server, client) } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.shutdown() }
    }
    @Test fun realHttpsTransportStreamsAndBuildsCorrectRequest() = tlsServer { server, client ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8")
            .setChunkedBody(chunk("真实传输测试") + ending, 5))
        val provider = DeepSeekProvider(ProviderCredentials(server.url("/v1").toString(), "test-model", "unit-test-token"), client)
        val text = runBlocking { withTimeout(10_000) { provider.streamReply(listOf(PromptMessage("user", "问题")), json = true).toList().joinToString("") } }
        assertEquals("真实传输测试", text)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer unit-test-token", request.getHeader("Authorization"))
        val json = org.json.JSONObject(request.body.readUtf8())
        assertTrue(json.getBoolean("stream")); assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
        assertEquals("问题", json.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals(1, server.requestCount)
    }
    @Test fun http2DataFramesAlsoStreamUtf8Correctly() = tlsServer(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1)) { server, client ->
        // HTTP/2 uses DATA frames, not HTTP/1.1 Transfer-Encoding chunk framing.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(chunk("中文跨帧") + ending)
            .throttleBody(5, 1, TimeUnit.MILLISECONDS))
        val result = runBlocking { withTimeout(10_000) { DeepSeekProvider(ProviderCredentials(server.url("/").toString(), "test", "unit-test-token"), client)
            .streamReply(listOf(PromptMessage("user", "问题"))).toList().joinToString("") } }
        assertEquals("中文跨帧", result)
    }
    @Test fun officialThinkingLevelsReachHttpsPayloadWithoutChangingAnswerBudget() = tlsServer { server, client ->
        // Rewrite only in this test: exercise official capability matching without contacting a vendor.
        val redirected=client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build())
        }.build()
        for(level in ThinkingLevel.entries){
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(chunk("合成回复")+ending))
            runBlocking{withTimeout(10_000){DeepSeekProvider(ProviderCredentials("https://api.deepseek.com/v1","deepseek-flash","unit-test-token"),redirected,level.id)
                .streamReply(listOf(PromptMessage("user","合成问题"))).toList()}}
            val request=server.takeRequest(2,TimeUnit.SECONDS)!!;assertEquals("POST",request.method)
            val payload=org.json.JSONObject(request.body.readUtf8())
            if(level==ThinkingLevel.DEFAULT){assertFalse(payload.has("thinking"));assertFalse(payload.has("reasoning_effort"))}
            else{assertEquals("enabled",payload.getJSONObject("thinking").getString("type"));assertEquals(level.effort,payload.getString("reasoning_effort"))}
            assertFalse(payload.has("temperature"));assertFalse(payload.has("max_tokens"))
        }
        assertEquals(4,server.requestCount)
    }
    @Test fun httpFailureAndRedirectDoNotLeakBodyOrRetry() = tlsServer { server, client ->
        for (code in listOf(401, 429, 302)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", server.url("/leak"))
                .setBody("unit-test-token sensitive server response"))
            try { runBlocking { DeepSeekProvider(ProviderCredentials(server.url("/").toString(), "test", "unit-test-token"), client)
                .streamReply(listOf(PromptMessage("user", "x"))).toList() }; fail() }
            catch (e: AiFailure) { assertTrue(e.message!!.contains(code.toString())); assertFalse(e.message!!.contains("unit-test-token")) }
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun cancellingRequestClosesPendingHttpsCallPromptly() = tlsServer { server, client ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        runBlocking {
            val job = launch(Dispatchers.Default) { DeepSeekProvider(ProviderCredentials(server.url("/").toString(), "test", "unit-test-token"), client)
                .streamReply(listOf(PromptMessage("user", "x"))).collect() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(2000) { job.cancelAndJoin() }
            withTimeout(2000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
        }
        assertEquals(1, server.requestCount)
    }
}
