package cn.campus.notes.integration

import org.json.JSONObject
import java.net.URI

enum class ThinkingLevel(val id: String, val label: String, val effort: String?) {
    DEFAULT("default","模型默认",null), LIGHT("light","轻量","low"), STANDARD("standard","标准","high"), DEEP("deep","深入","max");
    companion object { fun from(id: String) = entries.find { it.id==id } ?: throw IllegalArgumentException("未知思考档位") }
}

/** Verified protocol mapping, not a promise that a configured account/model is available online. */
object ModelCapabilities {
    fun deepSeekThinking(endpoint: String, model: String): Boolean =
        runCatching { URI(endpoint.trim()).host.equals("api.deepseek.com",true) }.getOrDefault(false) && model in setOf("deepseek-flash","deepseek-v4-pro")
    fun levels(endpoint: String, model: String): List<ThinkingLevel> =
        if(deepSeekThinking(endpoint,model)) ThinkingLevel.entries.toList() else listOf(ThinkingLevel.DEFAULT)
    fun apply(payload: JSONObject, endpoint: String, model: String, selection: String) {
        val level=ThinkingLevel.from(selection)
        require(level in levels(endpoint,model)) { "该地址与模型尚未核验思考档位，请选择模型默认" }
        if(level!=ThinkingLevel.DEFAULT) payload.put("thinking",JSONObject().put("type","enabled")).put("reasoning_effort",level.effort)
    }
}
