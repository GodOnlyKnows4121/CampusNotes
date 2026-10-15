package cn.campus.notes.integration

import kotlinx.coroutines.flow.Flow

data class PromptMessage(val role: String, val content: String)
interface AiProvider { fun streamReply(messages: List<PromptMessage>, json: Boolean = false): Flow<String> }
/** Separate ASR/OCR adapters; sending requires an explicit user action. */
interface SpeechRecognizer { suspend fun transcribe(audio: ByteArray, mime: String): String }
interface TextRecognizer { suspend fun recognize(image: ByteArray, mime: String): String }
