package cn.campus.notes.integration

import kotlinx.coroutines.flow.Flow

data class PromptMessage(val role: String, val content: String)
interface AiProvider { fun streamReply(messages: List<PromptMessage>, json: Boolean = false): Flow<String> }
/** Independent recognition contracts; service adapters are not connected yet. */
interface SpeechRecognizer { suspend fun transcribe(audio: ByteArray): String }
interface TextRecognizer { suspend fun recognize(image: ByteArray): String }
