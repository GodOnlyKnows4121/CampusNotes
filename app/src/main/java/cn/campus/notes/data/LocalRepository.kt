package cn.campus.notes.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import cn.campus.notes.domain.DemoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import cn.campus.notes.domain.WritePolicy
import cn.campus.notes.domain.WriteScope
import cn.campus.notes.domain.WriteDecision

class LocalRepository(private val context: Context, val db: CampusDatabase) {
    private val dao = db.dao()
    private val fileLock = Mutex()
    val documents = File(context.filesDir, "documents").apply { mkdirs() }
    val data = combine(dao.observeNotes(), dao.observeCourses(), dao.observeNodes(), ::LocalData)

    suspend fun saveNote(note: Note) {
        require(note.title.isNotBlank()) { "请填写记录标题" }
        dao.save(note.copy(title = note.title.trim(), updatedAt = System.currentTimeMillis()))
    }
    suspend fun deleteNote(id: String) = dao.deleteNote(id)
    suspend fun saveCourse(course: LocalCourse) = db.withTransaction {
        require(course.title.isNotBlank()) { "请填写课程名称" }
        require(course.day in 0..6 && course.slot in 0..4) { "请选择有效的星期与节次" }
        require(course.firstWeek in 1..30 && course.lastWeek in course.firstWeek..30) { "周次须在 1–30 之间，结束周不能早于开始周" }
        require(dao.courses().none { it.id != course.id && it.day == course.day && it.slot == course.slot &&
            it.firstWeek <= course.lastWeek && course.firstWeek <= it.lastWeek }) { "该时段已有课程，请调整星期、节次或周次" }
        dao.save(course.copy(title = course.title.trim()))
    }
    suspend fun deleteCourse(id: String) = dao.deleteCourse(id)
    suspend fun saveNode(node: LibraryNode) = db.withTransaction {
        require(node.title.isNotBlank()) { "名称不能为空" }
        val all = dao.nodes()
        val previous = all.find { it.id == node.id }
        require(previous == null || (previous.parentId == node.parentId && previous.kind == node.kind &&
            previous.localName == node.localName)) { "请勿更改资料的归属与文件地址" }
        val parent = all.find { it.id == node.parentId }
        require(when(node.kind) {
            NodeKind.GROUP -> node.parentId == null
            NodeKind.SUBJECT -> parent?.kind == NodeKind.GROUP
            NodeKind.TOPIC -> parent?.kind == NodeKind.SUBJECT
            NodeKind.ORIGINAL, NodeKind.OUTPUT -> parent?.kind == NodeKind.TOPIC
            else -> false
        }) { "上级资料已不存在，请重新选择" }
        dao.save(node.copy(title = node.title.trim()))
    }
    // Deletion only touches this application's own copies. External source documents are never edited.
    suspend fun deleteNode(id: String) = withContext(Dispatchers.IO) { fileLock.withLock {
        val removed = db.withTransaction {
            val all = dao.nodes()
            val ids = mutableSetOf(id)
            do { val size = ids.size; all.filter { it.parentId in ids }.forEach { ids.add(it.id) } } while (size != ids.size)
            dao.deleteNode(id)
            all.filter { it.id in ids }.mapNotNull { it.localName }
        }
        removed.forEach { name ->
            val file = document(name)
            check(!file.exists() || file.delete()) { "资料条目已移除，但部分本地副本清理失败；请在本地存储中重试清理" }
        }
    } }
    fun document(name: String): File {
        require(Regex("[a-f0-9-]{36}").matches(name)) { "无效的本地文件引用" }
        return File(documents, name)
    }
    suspend fun importDocument(topicId: String, uri: Uri) = withContext(Dispatchers.IO) { fileLock.withLock {
        require(uri.scheme == "content") { "请选择系统文件选择器中的文档" }
        var title = "导入资料"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) title = it.getString(0)?.take(200)?.ifBlank { "导入资料" } ?: title
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { importStream(topicId, title, mime, it) }
    } }
    internal suspend fun importStream(topicId: String, title: String, mime: String, input: InputStream) {
        val id = newId()
        val temp = File(documents, "$id.part")
        val target = document(id)
        var committed = false
        try {
            var total = 0L
            temp.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_IMPORT_BYTES) { "单文件上限为 100 MB，请选择较小的文件" }
                    out.write(buffer, 0, count)
                }
            }
            check(temp.renameTo(target)) { "无法保存本地副本" }
            withContext(NonCancellable) {
                saveNode(LibraryNode(parentId = topicId, kind = NodeKind.ORIGINAL, title = title,
                    localName = id, mime = mime, bytes = total))
                committed = true
            }
        } catch (e: Exception) { temp.delete(); if (!committed) target.delete(); throw e }
    }
    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) { documents.listFiles()?.sumOf { it.length() } ?: 0L }
    suspend fun cleanOrphans(): Int = withContext(Dispatchers.IO) { fileLock.withLock {
        val retained = dao.nodes().mapNotNull { it.localName }.toSet()
        val orphaned = documents.listFiles().orEmpty().filter { it.name !in retained }
        orphaned.forEach { check(it.delete()) { "部分未关联副本清理失败，请重试" } }
        orphaned.size
    } }
    suspend fun organizeSnapshot(topicId: String): OrganizeSnapshot = withContext(Dispatchers.IO) {
        fileLock.withLock { snapshotUnlocked(topicId) }
    }
    private suspend fun snapshotUnlocked(topicId: String): OrganizeSnapshot {
        val all = dao.nodes()
        val topic = all.singleOrNull { it.id == topicId && it.kind == NodeKind.TOPIC }
            ?: throw IllegalArgumentException("主题已不存在，请重新选择")
        val subject = all.single { it.id == topic.parentId && it.kind == NodeKind.SUBJECT }
        val group = all.single { it.id == subject.parentId && it.kind == NodeKind.GROUP }
        val originals = all.filter { it.parentId == topic.id && it.kind == NodeKind.ORIGINAL }.sortedBy { it.id }
        require(originals.isNotEmpty()) { "此主题没有原件，请先导入 UTF-8 文本资料" }
        require(originals.size <= 10) { "本轮整理单主题最多读取 10 份原件" }
        val digest = MessageDigest.getInstance("SHA-256")
        fun hash(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
        hash(topic.id); hash(topic.title); hash(subject.title); hash(group.id); hash(group.title); hash(group.rule)
        val text = StringBuilder()
        originals.forEach { node ->
            require(node.mime in setOf("text/plain", "text/markdown", "text/csv", "application/json") ||
                node.title.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "csv", "json")) {
                "本轮代理只读取 UTF-8 文本；图片、录音和 PDF 须先识别为文本"
            }
            val file = document(node.localName ?: error("原件本地副本缺失"))
            require(file.canonicalFile.parentFile == documents.canonicalFile && file.isFile) { "原件本地副本缺失或地址无效" }
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(64_001); var count = 0
                while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                buffer.copyOf(count)
            }
            require(bytes.size <= 64_000) { "单份整理文本上限为 64 KB" }
            val content = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (_: Exception) { throw IllegalArgumentException("原件不是 UTF-8 文本，请转换后导入") }
            require(!content.contains('\u0000')) { "原件不是可读取的文本" }
            text.append("\n<资料 id=\"").append(node.id).append("\">\n名称：").append(node.title).append('\n').append(content).append("\n</资料>\n")
            require(text.length <= 48_000) { "单次整理文本合计上限为 48000 字，请拆分主题" }
            hash(node.id); hash(node.title); hash(node.localName!!); hash(content)
        }
        require(group.rule.length <= 8000) { "整理规则过长，请缩短至 8000 字以内" }
        return OrganizeSnapshot(topic.id, "${subject.title} / ${topic.title}", group.title, group.rule,
            originals.joinToString("\n") { "${it.title} (${it.bytes} 字节)" }, digest.digest().joinToString("") { "%02x".format(it) }, text.toString())
    }
    /** The model never chooses a filesystem path; writes are limited to a new UUID file in documents. */
    suspend fun executeOrganize(taskId: String, sandboxEnabled: Boolean, confirmed: Boolean) = withContext(Dispatchers.IO) {
        fileLock.withLock {
            require(WritePolicy.evaluate(WriteScope.APP_SANDBOX, false) == WriteDecision.ALLOW) { "写入策略拒绝此操作" }
            require(sandboxEnabled || confirmed) { "请预览操作并确认沙箱内写入，或在更多中授权沙箱整理" }
            val task = dao.task(taskId) ?: error("整理任务不存在")
            require(task.status == "preview") { "此任务已执行或不具备可执行的计划" }
            val snapshot = snapshotUnlocked(task.topicId)
            require(snapshot.stamp == task.sourceStamp) { "规则或原件已改变，请重新生成计划" }
            require(task.title.isNotBlank() && task.title.length <= 120 && task.title.none { it in "/\\\r\n" } &&
                task.body.isNotBlank() && task.body.length <= 30_000) { "整理计划内容无效" }
            val id = newId()
            val temp = File(documents, "$id.part")
            val target = document(id)
            val output = "# ${task.title}\n\n${task.body}\n\n---\nAI 整理成果，请核对。\n来源主题：${task.topicTitle}\n原件：\n${task.sources}\n整理规则：${task.rule}\n任务：${task.id}\n"
            var committed = false
            try {
                temp.writeText(output, Charsets.UTF_8)
                check(temp.renameTo(target)) { "成果文件保存失败" }
                withContext(NonCancellable) {
                    db.withTransaction {
                        require(dao.task(taskId)?.status == "preview") { "任务已被执行" }
                        require(snapshotUnlocked(task.topicId).stamp == task.sourceStamp) { "规则或原件已改变，请重新生成计划" }
                        saveNode(LibraryNode(id, task.topicId, NodeKind.OUTPUT, "${task.title}.md", localName = id,
                            mime = "text/markdown", bytes = target.length()))
                        dao.save(task.copy(status = "complete", outputId = id, detail = "已新建沙箱内成果；原件保留"))
                    }
                    committed = true
                }
            } catch (e: Exception) { temp.delete(); if (!committed) target.delete(); throw e }
        }
    }
    suspend fun loadExamples() = db.withTransaction {
        require(dao.nodes().isEmpty() && dao.courses().isEmpty() && dao.notes().isEmpty()) { "示例仅能载入空资料库，避免影响已有内容" }
        DemoData.courses.forEach { dao.save(LocalCourse(it.id,it.title,it.day,it.slot,it.room,it.teacher, example = true)) }
        DemoData.groups.forEach { g ->
            dao.save(LibraryNode(g.id,kind=NodeKind.GROUP,title=g.title,rule=g.organizationRule,example=true))
            g.subjects.forEach { s ->
                dao.save(LibraryNode(s.id,g.id,NodeKind.SUBJECT,s.title,example=true))
                s.topics.forEachIndexed { i,t -> dao.save(LibraryNode("${s.id}-$i",s.id,NodeKind.TOPIC,t,example=true)) }
            }
        }
    }
    companion object { const val MAX_IMPORT_BYTES = 100L * 1024 * 1024 }
}
