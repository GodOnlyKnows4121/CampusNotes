package cn.campus.notes.integration

import cn.campus.notes.data.ProviderCredentials
import cn.campus.notes.data.SettingsStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class AiFailure(message: String) : IOException(message)

/** HTTPS only, no redirects or automatic retries, and no body/credential logging. */
class DeepSeekProvider(private val credentials: ProviderCredentials,
    private val client: OkHttpClient = defaultClient) : AiProvider {
    override fun streamReply(messages: List<PromptMessage>, json: Boolean): Flow<String> = callbackFlow {
        val url = completionUrl(credentials.endpoint)
        require(credentials.model.isNotBlank()) { "请填写模型名称" }
        require(credentials.key.isNotBlank() && credentials.key.all { it in '!'..'~' }) { "API 密钥格式无效" }
        val payload = JSONObject().put("model", credentials.model).put("stream", true).put("max_tokens", 4096)
            .put("messages", JSONArray().apply { messages.forEach {
                put(JSONObject().put("role", it.role).put("content", it.content))
            } })
        if (url.host == "api.deepseek.com") payload.put("thinking", JSONObject().put("type", "disabled"))
        if (json) payload.put("response_format", JSONObject().put("type", "json_object"))
        val call = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${credentials.key}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(networkFailure(e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw httpFailure(response.code)
                        if (response.header("Content-Type")?.substringBefore(';')?.trim() != "text/event-stream")
                            throw AiFailure("服务未返回流式数据，请确认 API 地址与模型支持 Chat Completions")
                        val body = response.body ?: throw AiFailure("服务返回空响应")
                        SseReader.read(body.charStream().buffered()) { delta ->
                            if (trySendBlocking(delta).isFailure) throw IOException("cancelled")
                        }
                        close()
                    } catch (e: Exception) { close(if (e is AiFailure) e else networkFailure(e)) }
                }
            }
        })
        awaitClose { call.cancel() }
    }
    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        fun completionUrl(endpoint: String): HttpUrl {
            SettingsStore.validateEndpoint(endpoint)
            require(endpoint.isNotBlank()) { "请填写 HTTPS API 地址" }
            val base = endpoint.trim().trimEnd('/')
            return (if (base.endsWith("/chat/completions")) base else "$base/chat/completions").toHttpUrl()
        }
        fun httpFailure(code: Int) = AiFailure(when (code) {
            400, 422 -> "请求参数不受支持（HTTP $code），请检查模型与接口配置"
            401, 403 -> "鉴权失败（HTTP $code），请检查 API 密钥和服务权限"
            402 -> "账户余额不足（HTTP 402），请在服务商处检查余额"
            404 -> "接口或模型不存在（HTTP 404），请检查 API 地址和模型名称"
            429 -> "请求过于频繁（HTTP 429），请稍后手动重试"
            in 300..399 -> "服务要求跳转（HTTP $code），为保护密钥已停止请求，请填写最终 HTTPS 地址"
            in 500..599 -> "服务暂不可用（HTTP $code），请稍后手动重试"
            else -> "服务请求失败（HTTP $code）"
        })
        fun networkFailure(error: Exception) = AiFailure(if (error is SocketTimeoutException)
            "连接或回复超时，已保留部分回复，可手动重试" else "网络连接失败或响应格式无效，请检查网络与服务配置")
    }
}

/** Bounded SSE frames; EOF without DONE and incomplete finish reasons must never become success. */
object SseReader {
    const val MAX_CHARS = 64_000
    fun read(reader: BufferedReader, consume: (String) -> Unit) {
        val frame = StringBuilder()
        var total = 0
        var finish: String? = null
        fun event(): Boolean {
            if (frame.isEmpty()) return false
            val data = frame.toString().trimEnd('\n'); frame.clear()
            if (data.isBlank()) return false // Empty data events are permitted SSE keepalives.
            if (data.trim() == "[DONE]") {
                if (finish != "stop") throw AiFailure(if (finish == "length") "回复达到长度上限，部分内容已保留" else "回复未完整结束，部分内容已保留")
                if (total == 0) throw AiFailure("服务未返回文字内容")
                return true
            }
            val chunk = try { JSONObject(data) } catch (_: Exception) { throw AiFailure("流式响应格式无效，部分回复已保留") }
            if (chunk.has("error")) throw AiFailure("服务在生成过程中返回错误，部分回复已保留")
            val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return false
            if (!choice.isNull("finish_reason")) finish = choice.optString("finish_reason")
            val delta = choice.optJSONObject("delta")
            if (delta?.has("tool_calls") == true) throw AiFailure("当前对话不允许执行模型工具操作")
            if (delta != null && !delta.isNull("content") && delta.opt("content") !is String)
                throw AiFailure("流式文字字段格式无效")
            val text = if (delta == null || delta.isNull("content")) "" else delta.optString("content")
            total += text.length
            if (total > MAX_CHARS) throw AiFailure("回复过长，已停止接收并保留部分内容")
            if (text.isNotEmpty()) consume(text)
            return false
        }
        while (true) {
            val line = boundedLine(reader) ?: break
            if (line.isEmpty()) { if (event()) return }
            else if (line.startsWith("data:")) {
                val value = line.substring(5).removePrefix(" ")
                if (frame.length + value.length > MAX_CHARS) throw AiFailure("流式数据帧过大，已停止接收")
                frame.append(value).append('\n')
            }
        }
        if (event()) return
        throw AiFailure("连接提前结束，部分回复已保留")
    }
    private fun boundedLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val char = reader.read()
            if (char < 0) return if (line.isEmpty()) null else line.toString().trimEnd('\r')
            if (char == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= MAX_CHARS) throw AiFailure("流式数据行过大，已停止接收")
            line.append(char.toChar())
        }
    }
}
