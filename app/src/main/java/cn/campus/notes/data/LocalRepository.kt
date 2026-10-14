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
