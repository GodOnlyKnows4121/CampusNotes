package cn.campus.notes.integration

import android.content.Context
import androidx.room.withTransaction
import cn.campus.notes.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/** Process-scoped jobs survive Activity recreation; unfinished persisted jobs are interrupted on restart. */
class ContentProcessor(context: Context, private val repo: LocalRepository, private val settings: SettingsStore,
    private val parser: ((java.io.File, LibraryNode) -> String)? = null,
    private val recognizer: (ProviderCredentials) -> RecognitionProvider = { RecognitionProvider(it) }) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val ready = CompletableDeferred<Unit>()
    private val audioSegments = AudioSegments(context)
    private val localParser = DocumentParser(context)
    init { scope.launch {
        try { repo.db.content().interrupt(); ready.complete(Unit) }
        catch (e: Exception) { ready.completeExceptionally(e) }
    } }

    fun start(sourceId: String, kind: String, expected: ProviderConfig? = null): Job {
        require(kind in setOf("parse", "asr", "ocr"))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var result: ContentResult? = null
            try {
                ready.await()
                val source = source(sourceId)
                result = ContentResult(sourceId = sourceId, kind = kind)
                persist(result!!)
                val providerKind = when(kind) { "asr" -> ProviderKind.ASR; "ocr" -> ProviderKind.OCR; else -> null }
                val credentials = providerKind?.let { p ->
                    val current = settings.data.first().providers[p] ?: ProviderConfig()
                    require(expected != null && current == expected) { "识别配置已改变，请重新查看上传内容后确认" }
                    RecognitionConfiguration.validate(p,current.endpoint,current.model)
                    settings.credentials(p)
                }
                result = result!!.copy(provider = credentials?.let { "${it.endpoint}\n${it.model}" } ?: "本机解析")
                persist(result!!)
                val file = repo.document(source.localName ?: error("原件本地副本缺失"))
                val limit = when(kind) { "parse" -> 20_000_000; "asr" -> 512_000_000; else -> RecognitionProvider.MAX_INPUT }
                require(file.isFile && file.length() in 1..limit.toLong()) {
                    if (kind == "parse") "本机解析上限 20 MB，原件保留" else "音频原件上限 512 MB，图片上限 7 MB；原件保留"
                }
                val stamp = repo.sourceStamp(source)
                result = result!!.copy(status = "running", sourceStamp = stamp,
                    detail=if(kind=="ocr")"正在发送并等待图片识别完整结果，单次最多约5.5分钟；可取消，不自动重试，原件保留" else "")
                persist(result!!)
                val text = if (credentials == null) parser?.invoke(file, source) ?: localParser.extract(file, source) { coroutineContext.ensureActive() } else {
                    val adapter = recognizer(credentials)
                    if (kind == "asr") {
                        val output = StringBuilder()
                        audioSegments.transcribe(file, processingMime(source)) { bytes, mime, index, offset ->
                            val part = adapter.transcribe(bytes, mime)
                            output.append("[%02d:%02d]".format(offset / 60_000, offset / 1000 % 60)).append('\n').append(part).append("\n\n")
                            require(output.length <= DocumentParser.MAX_TEXT) { "转写超过 60000 字，部分文字已保留；请拆分原件后处理" }
                            result = result!!.copy(text = output.toString(), detail = "已处理 $index 段；仍在处理，尚未确认", updatedAt = System.currentTimeMillis())
                            persist(result!!)
                        }
                        output.toString()
                    } else adapter.recognize(file.inputStream().use { it.readBytesBounded(RecognitionProvider.MAX_INPUT) }, processingMime(source))
                }
                ensureActive()
                require(text.isNotBlank() && text.length <= DocumentParser.MAX_TEXT) { "处理结果为空或超过文字上限" }
                require(repo.sourceStamp(source(sourceId)) == stamp) { "原件在处理期间改变，请重新发起任务" }
                persist(result!!.copy(status = "review", text = text,
                    detail = "待核对，尚未作为已确认内容使用", updatedAt = System.currentTimeMillis()))
            } catch (e: Exception) {
                result?.let { saved -> withContext(NonCancellable) {
                    repo.db.withTransaction { if(repo.retainsSource(saved.sourceId)) repo.db.content().save(saved.copy(status = if(e is CancellationException) "cancelled" else "failed",
                        detail = if (e is CancellationException) { if(saved.kind=="parse")"已取消本机解析，原件保留" else "已取消；服务商可能仍计费，原件保留" } else safeFailure(e),
                        updatedAt = System.currentTimeMillis())) }
                } }
                if (e is CancellationException) throw e
            }
        }
        val existing = jobs.putIfAbsent(sourceId, job)
        if (existing != null) { job.cancel(); return existing }
        job.invokeOnCompletion { jobs.remove(sourceId, job) }
        job.start()
        return job
    }
    fun cancel(sourceId: String) { jobs[sourceId]?.cancel() }
    suspend fun shutdown() { scope.coroutineContext[Job]?.cancelAndJoin() }
    private suspend fun persist(value: ContentResult) = repo.db.withTransaction {
        check(repo.retainsSource(value.sourceId)) { "原件已永久删除，不再保存其处理内容" }
        repo.db.content().save(value)
    }
    suspend fun confirm(id: String, text: String) = repo.db.withTransaction {
        val value = repo.db.content().result(id) ?: error("结果已不存在")
        require(value.status in setOf("review", "confirmed")) { "只能确认完整的识别或解析结果" }
        require(text.isNotBlank() && text.length <= DocumentParser.MAX_TEXT) { "确认文字须为 1–60000 字" }
        require(repo.sourceStamp(source(value.sourceId)) == value.sourceStamp) { "原件已经改变，请重新处理" }
        repo.db.content().save(value.copy(status = "confirmed", text = text.trim(), detail = "已由用户核对确认", updatedAt = System.currentTimeMillis()))
    }
    suspend fun saveAsNote(id: String) = repo.db.withTransaction {
        val result = repo.db.content().result(id) ?: error("结果已不存在")
        require(result.status == "confirmed") { "请先核对并确认文字" }
        val source = source(result.sourceId)
        require(repo.sourceStamp(source) == result.sourceStamp) { "原件已经改变，请重新处理" }
        require(result.outputNoteId == null || repo.db.dao().notes().none { it.id == result.outputNoteId }) { "此结果已另存文字记录，编辑请进入原记录" }
        val note = Note(title = "${source.title} · 确认文字", body = result.text)
        repo.saveNote(note)
        val link = repo.db.features().allLinks().find { it.recordId == source.id }
        repo.db.features().save(RecordLink(note.id, link?.courseId, source.parentId?.takeUnless { it == "inbox-topic" }))
        repo.db.content().save(result.copy(outputNoteId = note.id))
    }
    private suspend fun source(id: String) = repo.db.dao().nodes().find { it.id == id && it.kind == NodeKind.ORIGINAL }
        ?: throw IllegalArgumentException("原件已移入回收站或不存在，请恢复后再处理")
    private fun safeFailure(e: Exception): String = when (e) {
        is AiFailure -> e.message ?: "识别请求失败，原件保留"
        is IllegalArgumentException, is IllegalStateException -> e.message?.take(200) ?: "处理未完成，原件保留"
        else -> "文件无法解析或读取；可能已损坏、加密或格式不支持，原件保留"
    }
}

fun processingMime(node: LibraryNode): String = when (node.title.substringAfterLast('.', "").lowercase()) {
    "m4a", "mp4" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"
    "ogg" -> "audio/ogg"; "opus" -> "audio/opus"; "aac" -> "audio/aac"
    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
    else -> node.mime
}
