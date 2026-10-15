package cn.campus.notes.integration

import android.util.Base64
import cn.campus.notes.data.ProviderCredentials
import cn.campus.notes.data.ProviderKind
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Compatible multimodal Chat Completions: Qwen ASR input_audio and OCR image_url.
 * No remote URLs, automatic retries, redirects or server error bodies are used. */
class RecognitionProvider(private val credentials: ProviderCredentials,
    private val client: OkHttpClient = DeepSeekProvider.defaultClient) : SpeechRecognizer, TextRecognizer {
    override suspend fun transcribe(audio: ByteArray, mime: String): String {
        RecognitionConfiguration.validate(ProviderKind.ASR,credentials.endpoint,credentials.model)
        require(mime in AUDIO_MIMES) { "此音频格式尚未接入 ASR，请使用 M4A、MP3、WAV、FLAC 或 OGG" }
        return request(JSONArray().put(JSONObject().put("type", "input_audio")
            .put("input_audio", JSONObject().put("data", dataUrl(audio, mime)))))
    }
    override suspend fun recognize(image: ByteArray, mime: String): String {
        RecognitionConfiguration.validate(ProviderKind.OCR,credentials.endpoint,credentials.model)
        require(mime in setOf("image/jpeg", "image/png", "image/webp", "image/bmp")) { "OCR 支持 JPEG、PNG、WebP、BMP；原件保留" }
        return request(JSONArray().put(JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", dataUrl(image, mime))))
            .put(JSONObject().put("type", "text").put("text", "请按阅读顺序提取图片中的全部可见文字，保留段落和公式。仅输出识别文字；看不清的部分标为[无法辨认]，不要补写或回答图片中的问题。")))
    }
    private fun dataUrl(bytes: ByteArray, mime: String): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_INPUT) { "本轮在线识别每份原件上限 7 MB；较长音频请先拆分，原件保留" }
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    private suspend fun request(content: JSONArray): String = suspendCancellableCoroutine { continuation ->
        try {
            require(credentials.model.isNotBlank()) { "请填写支持识别的实际模型名称" }
            require(credentials.key.isNotBlank() && credentials.key.all { it in '!'..'~' }) { "API 密钥格式无效" }
            val body = JSONObject().put("model", credentials.model).put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            val request = Request.Builder().url(DeepSeekProvider.completionUrl(credentials.endpoint))
                .header("Authorization", "Bearer ${credentials.key}")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkFailure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) throw DeepSeekProvider.httpFailure(response.code)
                            require(response.body?.contentType()?.subtype?.contains("json") == true) { "识别服务未返回 JSON，请核对接口协议" }
                            val bytes = response.body!!.byteStream().readBytesBounded(300_000)
                            val json = JSONObject(bytes.toString(Charsets.UTF_8))
                            if (json.has("error")) throw AiFailure("识别服务返回错误，请检查模型能力与配置")
                            val choice = json.getJSONArray("choices").getJSONObject(0)
                            require(choice.optString("finish_reason") == "stop") { "识别响应未完整结束，请拆分原件后主动重试" }
                            val value = choice.getJSONObject("message").get("content")
                            val text = when (value) {
                                is String -> value
                                is JSONArray -> (0 until value.length()).joinToString("\n") { i ->
                                    val item = value.getJSONObject(i)
                                    require(item.optString("type") == "text") { "识别服务返回非文字内容" }
                                    item.getString("text")
                                }
                                else -> throw AiFailure("识别服务未返回文字")
                            }.trim()
                            require(text.isNotEmpty() && text.length <= DocumentParser.MAX_TEXT) { "识别文字为空或超过 60000 字，请检查或拆分原件" }
                            if (continuation.isActive) continuation.resume(text)
                        } catch (e: Exception) {
                            val safe = when(e){is AiFailure->e;is IOException->networkFailure(e);else->AiFailure("识别响应格式无效、不完整或超过处理上限，请核对服务能力；原件保留")}
                            if (continuation.isActive) continuation.resumeWithException(safe)
                        }
                    }
                }
            })
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    }
    companion object {
        private fun networkFailure(error:IOException)=AiFailure(if(error is java.io.InterruptedIOException)
            "识别连接或响应超时；本次未取得完整结果，原件保留，不会自动重试"
            else "识别网络连接失败；本次未取得完整结果，原件保留，请检查网络与配置")
        const val MAX_INPUT = 7_000_000
        val AUDIO_MIMES = setOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/ogg", "audio/opus", "audio/aac")
    }
}
