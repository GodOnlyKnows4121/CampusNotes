package cn.campus.notes.integration

import cn.campus.notes.data.ProviderConfig
import cn.campus.notes.data.ProviderKind
import java.net.URI

/** Local protocol checks only; passing is never evidence of account access or online availability. */
object RecognitionConfiguration {
    const val QWEN_BASE = "https://maas.qianwenaiapi.com/compatible-mode/v1"
    fun qwenExample(kind: ProviderKind): ProviderConfig {
        require(kind != ProviderKind.DEEPSEEK)
        return ProviderConfig(QWEN_BASE, if(kind==ProviderKind.ASR) "qwen3-asr-flash" else "qwen3.5-ocr")
    }
    fun validate(kind:ProviderKind,endpoint:String,model:String) {
        require(kind!=ProviderKind.DEEPSEEK)
        val url=DeepSeekProvider.completionUrl(endpoint)
        require(model.isNotBlank()){ "请填写明确支持${if(kind==ProviderKind.ASR)"音频" else "图片"}识别的完整模型名" }
        val path=URI(endpoint.trim()).path.orEmpty().trimEnd('/').lowercase()
        require(!Regex("(^|/)anthropic(/|$)").containsMatchIn(path) && !path.endsWith("/messages")) {
            "此地址使用 Anthropic Messages 协议；当前识别仅接入 Chat Completions，请改用识别服务的兼容地址，原配置不会自动替换"
        }
        require(!path.endsWith("/responses") && !path.endsWith("/audio/transcriptions") && !path.startsWith("/api/v1/services/")) {
            "此地址不是当前识别支持的 Chat Completions 协议，请填写兼容基址或完整 /chat/completions 地址"
        }
        if(url.host=="maas.qianwenaiapi.com") {
            require(url.encodedPath=="/compatible-mode/v1/chat/completions") { "千问识别须使用 /compatible-mode/v1 或其 /chat/completions 完整地址" }
            require(!model.trim().equals("qwen",true)) { "qwen 不是明确的识别模型名；请填写已开通的完整 ASR/OCR 模型名，可参考官方示例" }
            if(kind==ProviderKind.ASR) {
                require(!model.contains("ocr",true)&&!model.contains("-vl",true)){ "该模型名称属于图片识别/视觉系列，请为 ASR 选择音频识别模型" }
                require(!model.contains("filetrans",true)){ "Filetrans 使用异步转写协议，当前只接入兼容 Chat Completions 的同步 ASR" }
            } else require(!model.contains("asr",true)){ "该模型名称属于语音识别系列，请为 OCR 选择图片文字识别模型" }
        }
    }
    fun problem(kind:ProviderKind,config:ProviderConfig):String? = try { validate(kind,config.endpoint,config.model);null }
        catch(e:IllegalArgumentException){e.message?:"识别配置不完整"}
}
