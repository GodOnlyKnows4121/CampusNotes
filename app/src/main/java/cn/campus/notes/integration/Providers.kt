package cn.campus.notes.integration

import kotlinx.coroutines.flow.Flow

/** Contracts for a later phase; there are intentionally no live implementations. */
interface AiProvider { fun streamReply(message: String): Flow<String> }
interface SpeechRecognizer { suspend fun transcribe(audio: ByteArray): String }
interface TextRecognizer { suspend fun recognize(image: ByteArray): String }
