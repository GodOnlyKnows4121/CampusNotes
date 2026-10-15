package cn.campus.notes.integration

import androidx.room.withTransaction
import cn.campus.notes.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class ChatService(private val db: CampusDatabase,
    private val credentials: suspend () -> ProviderCredentials,
    private val provider: (ProviderCredentials) -> AiProvider = { DeepSeekProvider(it) }) {
    suspend fun send(question: String, update: (String) -> Unit = {}, accepted: suspend () -> Unit = {},
        sessionId: String = LEGACY_CHAT, profileId: String = LEGACY_CHAT, reasoning: String = "default") {
        require(question.isNotBlank() && question.length <= 8000) { "请输入 1–8000 字的问题" }
        val frozen = credentials()
        ModelCapabilities.apply(org.json.JSONObject(),frozen.endpoint,frozen.model,reasoning)
        val client = provider(frozen) // Fail before storing a message if configuration is missing.
        if(sessionId==LEGACY_CHAT&&db.sessions().session(sessionId)==null)db.sessions().insert(ChatSession(LEGACY_CHAT,"默认会话"))
        val history = db.sessions().messages(sessionId)
        val prompt = context(history, question.trim())
        val reply = db.withTransaction {
            require(db.sessions().session(sessionId)!=null){"会话已删除，本次未发送"}
            db.dao().insert(ChatMessage(role = "user", content = question.trim(), sessionId = sessionId,profileId=profileId,reasoning=reasoning,modelName=frozen.model,endpoint=frozen.endpoint))
            db.sessions().touch(sessionId,System.currentTimeMillis())
            val message = ChatMessage(role = "assistant", content = "", status = "streaming", sessionId=sessionId,profileId=profileId,reasoning=reasoning,modelName=frozen.model,endpoint=frozen.endpoint)
            message.copy(id = db.dao().insert(message))
        }
        val text = StringBuilder()
        var lastSave = 0L
        try {
            accepted()
            client.streamReply(prompt).collect { delta ->
                text.append(delta)
                update(text.toString())
                val now = System.nanoTime()
                if (now - lastSave > 200_000_000) {
                    persist(reply.copy(content = text.toString())); lastSave = now
                }
            }
            persist(reply.copy(content = text.toString(), status = "complete"))
        } catch (e: Exception) {
            withContext(NonCancellable) {
                persist(reply.copy(content = text.toString(), status = if (e is CancellationException) "cancelled" else "error",
                    detail = if (e is CancellationException) "已停止，部分回复不进入后续对话上下文" else safeMessage(e)))
            }
            throw e
        }
    }
    private suspend fun persist(row: ChatMessage) = db.withTransaction {
        if(db.sessions().session(row.sessionId)!=null&&db.sessions().hasMessage(row.id,row.sessionId))db.dao().save(row)
    }
    companion object {
        fun safeMessage(e: Exception): String = when (e) {
            is AiFailure, is IllegalArgumentException, is IllegalStateException -> e.message ?: "请求未完成"
            else -> "请求未完成，请检查网络、配置或本地存储"
        }
        /** Only complete user/assistant pairs enter context; interrupted or failed answers are excluded. */
        fun context(history: List<ChatMessage>, question: String): List<PromptMessage> {
            val pairs = mutableListOf<List<PromptMessage>>()
            var user: ChatMessage? = null
            history.forEach { row ->
                if (row.role == "user") user = row
                else if (row.role == "assistant") {
                    if (row.status == "complete" && row.content.isNotBlank() && user != null)
                        pairs.add(listOf(PromptMessage("user", user!!.content), PromptMessage("assistant", row.content)))
                    user = null
                }
            }
            val selected = mutableListOf<List<PromptMessage>>()
            var chars = question.length
            for (pair in pairs.takeLast(20).asReversed()) {
                val size = pair.sumOf { it.content.length }
                if (chars + size > 50_000) break
                selected.add(0, pair); chars += size
            }
            return listOf(PromptMessage("system", "你是校园学习助手。根据对话回答；不声称已读取资料库、识别文件或执行任何文件操作。")) +
                selected.flatten() + PromptMessage("user", question)
        }
    }
}
