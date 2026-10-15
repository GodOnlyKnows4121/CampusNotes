package cn.campus.notes.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import cn.campus.notes.domain.DemoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import cn.campus.notes.domain.WritePolicy
import cn.campus.notes.domain.WriteScope
import cn.campus.notes.domain.WriteDecision

class LocalRepository(private val context: Context, val db: CampusDatabase) {
    private val dao = db.dao()
    private val fileLock = Mutex()
    val documents = File(context.filesDir, "documents").apply { mkdirs() }
    val captures = File(context.filesDir, "captures").apply { mkdirs() }
    val data = combine(dao.observeNotes(), dao.observeCourses(), dao.observeNodes(), ::LocalData)

    suspend fun saveNote(note: Note) {
        require(note.title.isNotBlank()) { "请填写记录标题" }
        dao.save(note.copy(title = note.title.trim(), updatedAt = System.currentTimeMillis()))
    }
    suspend fun deleteNote(id: String) = db.withTransaction {
        val value = dao.notes().firstOrNull { it.id == id } ?: return@withTransaction
        db.features().save(TrashEntry(kind = "note", title = value.title, payload = JSONObject()
            .put("id",value.id).put("title",value.title).put("body",value.body).put("updatedAt",value.updatedAt).toString()))
        dao.deleteNote(id)
    }
    suspend fun saveCourse(course: LocalCourse) = db.withTransaction {
        require(course.title.isNotBlank()) { "请填写课程名称" }
        require(course.day in 0..6 && course.slot in 0..4) { "请选择有效的星期与节次" }
        require(course.firstWeek in 1..30 && course.lastWeek in course.firstWeek..30) { "周次须在 1–30 之间，结束周不能早于开始周" }
        checkCourseConflict(course, db.features().allPlacements().find { it.courseId == course.id } ?: CoursePlacement.legacy(course))
        dao.save(course.copy(title = course.title.trim()))
    }
    suspend fun deleteCourse(id: String) = db.withTransaction {
        val c = dao.courses().find { it.id == id } ?: return@withTransaction
        val p = db.features().allPlacements().find { it.courseId == id }
        val json = JSONObject().put("id",c.id).put("title",c.title).put("day",c.day).put("slot",c.slot)
            .put("room",c.room).put("teacher",c.teacher).put("firstWeek",c.firstWeek).put("lastWeek",c.lastWeek).put("example",c.example)
        p?.let { json.put("placement",JSONObject().put("semesterId",it.semesterId).put("startPeriod",it.startPeriod)
            .put("endPeriod",it.endPeriod).put("pattern",it.pattern).put("customWeeks",it.customWeeks)) }
        db.features().save(TrashEntry(kind="course",title=c.title,payload=json.toString()))
        db.features().deletePlacement(id); dao.deleteCourse(id)
    }
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
        db.withTransaction {
            val all = dao.nodes()
            val root = all.find { it.id == id } ?: return@withTransaction
            val ids = mutableSetOf(id)
            do { val size = ids.size; all.filter { it.parentId in ids }.forEach { ids.add(it.id) } } while (size != ids.size)
            val payload = JSONArray(); all.filter { it.id in ids }.forEach { payload.put(it.toJson()) }
            val noteIds=db.features().allLinks().filter{it.topicId in ids}.map{it.recordId}.toSet()
            val notes=dao.notes().filter{it.id in noteIds};val noteJson=JSONArray()
            notes.forEach{note->noteJson.put(JSONObject().put("id",note.id).put("title",note.title).put("body",note.body).put("updatedAt",note.updatedAt));dao.deleteNote(note.id)}
            db.features().save(TrashEntry(kind="node",title=root.title,payload=JSONObject().put("nodes",payload).put("notes",noteJson).toString()))
            dao.deleteNode(id)
        }
    } }
    fun document(name: String): File {
        require(Regex("[a-f0-9-]{36}").matches(name)) { "无效的本地文件引用" }
        return File(documents, name)
    }
    suspend fun importDocument(topicId: String, uri: Uri) { importDocumentReference(topicId,uri) }
    suspend fun importDocumentReference(topicId: String, uri: Uri):String = withContext(Dispatchers.IO) { fileLock.withLock {
        require(uri.scheme == "content") { "请选择系统文件选择器中的文档" }
        var title = "导入资料"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) title = it.getString(0)?.take(200)?.ifBlank { "导入资料" } ?: title
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { importStreamReference(topicId, title, mime, it) }
    } }
    internal suspend fun importStream(topicId: String, title: String, mime: String, input: InputStream) { importStreamReference(topicId,title,mime,input) }
    private suspend fun importStreamReference(topicId: String, title: String, mime: String, input: InputStream):String {
        val id = newId()
        val nodeId=newId()
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
                saveNode(LibraryNode(id=nodeId,parentId = topicId, kind = NodeKind.ORIGINAL, title = title,
                    localName = id, mime = mime, bytes = total))
                committed = true
            }
        } catch (e: Exception) { temp.delete(); if (!committed) target.delete(); throw e }
        return nodeId
    }
    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        documents.listFiles().orEmpty().sumOf { it.length() } + captures.listFiles().orEmpty().sumOf { it.length() }
    }
    /** Only a camera/recorder file created by this app can be adopted. Never accepts model paths. */
    suspend fun saveCapture(name: String, title: String, mime: String): String = withContext(Dispatchers.IO) { fileLock.withLock {
        require(Regex("[a-f0-9-]{36}\\.(m4a|jpg)").matches(name)) { "无效的采集文件引用" }
        val source = File(captures, name)
        require(source.canonicalFile.parentFile == captures.canonicalFile && source.isFile && source.length() > 0) { "未取得有效采集内容，临时文件保留" }
        val id = newId(); val target = document(id); val bytes = source.length()
        val topic = inboxTopic()
        val node = LibraryNode(parentId = topic, kind = NodeKind.ORIGINAL, title = title, localName = id, mime = mime, bytes = bytes)
        withContext(NonCancellable) {
            check(source.renameTo(target)) { "无法保存采集原件，临时文件保留" }
            try { saveNode(node) } catch(e: Exception) { check(target.renameTo(source)) { "原件保存在本地副本中，暂未关联记录" }; throw e }
        }
        node.id
    } }
    fun sourceStamp(node: LibraryNode): String = stampFile(node,document(node.localName ?: error("本地原件缺失")))
    private fun stampFile(node:LibraryNode,file:File):String {
        require(file.isFile) { "本地原件缺失" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(node.id.toByteArray()); digest.update((node.localName?:error("本地原件缺失")).toByteArray())
        file.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val n = input.read(buffer); if(n < 0) break; digest.update(buffer,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    data class FrozenOriginal(val file:File):java.io.Closeable {override fun close(){file.delete()}}
    suspend fun freezeOriginal(source:WorkflowSource):FrozenOriginal=withContext(Dispatchers.IO){fileLock.withLock{
        val node=dao.nodes().find{it.id==source.id&&it.kind==NodeKind.ORIGINAL}?:error("来源已移入回收站或删除")
        require(node.title==source.title&&node.mime==source.mime&&node.parentId==source.parentId&&node.bytes==source.bytes){"来源信息已改变，请重新预览任务"}
        val original=document(node.localName?:error("原件缺失"))
        require(original.length() in 1..512_000_000L){"原件为空或超过本轮处理上限"}
        val dir=File(context.cacheDir,"workflow-sources").apply{mkdirs()}
        val frozen=File.createTempFile("source-",".snapshot",dir)
        try{
            original.inputStream().use{input->frozen.outputStream().use{output->val buffer=ByteArray(65536);var total=0L;while(true){
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val n=input.read(buffer);if(n<0)break;total+=n;require(total<=512_000_000L){"原件超过处理上限"};output.write(buffer,0,n)
            }}}
            require(stampFile(node,frozen)==source.stamp&&sourceStamp(node)==source.stamp){"原件版本已改变，本次未上传"}
            FrozenOriginal(frozen)
        }catch(e:Exception){frozen.delete();throw e}
    }}
    suspend fun cleanOrphans(): Int = withContext(Dispatchers.IO) { fileLock.withLock {
        val retained = (dao.nodes() + db.features().allTrash().flatMap { it.nodes() }).mapNotNull { it.localName }.toSet()
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
            val confirmed = db.content().forSource(node.id).firstOrNull { it.status == "confirmed" }
            val plain = node.mime in setOf("text/plain", "text/markdown", "text/csv", "application/json") ||
                node.title.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "csv", "json")
            require(plain || confirmed != null) { "请先对图片、音频和文档主动识别/解析，并核对确认文字；未确认内容不会发给 AI" }
            val file = document(node.localName ?: error("原件本地副本缺失"))
            require(file.canonicalFile.parentFile == documents.canonicalFile && file.isFile) { "原件本地副本缺失或地址无效" }
            val bytes = if (confirmed != null) {
                require(sourceStamp(node) == confirmed.sourceStamp) { "原件已改变，请重新识别或解析并确认文字" }
                hash(confirmed.id); hash(confirmed.updatedAt.toString()); hash(confirmed.sourceStamp)
                confirmed.text.toByteArray(Charsets.UTF_8)
            } else file.inputStream().use { input ->
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
        require(dao.nodes().isEmpty() && dao.courses().isEmpty() && dao.notes().isEmpty() && db.features().allTrash().isEmpty()) { "示例仅能载入空资料库（含回收站），避免影响已有内容" }
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

    suspend fun saveSemester(value: Semester) = db.withTransaction {
        require(value.name.isNotBlank() && value.weeks in 1..30) { "请填写学期名称，总周数须为 1–30" }
        val date = try { LocalDate.parse(value.startDate) } catch (_: Exception) { error("开始日期格式为 yyyy-MM-dd") }
        require(date.dayOfWeek.value == 1) { "学期开始日期请选择第一周的周一" }
        val times = try { value.times() } catch (_: Exception) { error("节次格式为 08:00-08:45，每行一节") }
        require(times.size in 1..16 && times.all { it.first < it.second } && times.zipWithNext().all { it.first.second <= it.second.first }) { "请设置 1–16 节顺序排列且不重叠的上课时间" }
        val placed = db.features().allPlacements().filter { it.semesterId == value.id }
        require(placed.all { it.endPeriod <= times.size }) { "已有课程超出新节次范围，请先调整课程" }
        require(dao.courses().filter { c -> placed.any { it.courseId == c.id } }.all { it.lastWeek <= value.weeks }) { "已有课程超出新学期周数" }
        db.features().save(value.copy(name=value.name.trim()))
    }
    private suspend fun checkCourseConflict(course: LocalCourse, rule: CoursePlacement) {
        val placements = db.features().allPlacements().associateBy { it.courseId }
        require(dao.courses().none { other ->
            val p = placements[other.id] ?: CoursePlacement.legacy(other)
            other.id != course.id && other.day == course.day && p.semesterId == rule.semesterId &&
                p.startPeriod <= rule.endPeriod && rule.startPeriod <= p.endPeriod &&
                (1..30).any { rule.occurs(course,it) && p.occurs(other,it) }
        }) { "课程在相同教学周与节次发生冲突" }
    }
    suspend fun saveScheduledCourse(course: LocalCourse, rule: CoursePlacement) = db.withTransaction {
        require(course.id == rule.courseId && course.title.isNotBlank() && course.day in 0..6) { "请填写课程名称与星期" }
        val semester = rule.semesterId?.let { id -> db.features().allSemesters().find { it.id == id } ?: error("学期已不存在") }
        val maxWeek = semester?.weeks ?: 30
        require(course.firstWeek in 1..maxWeek && course.lastWeek in course.firstWeek..maxWeek) { "课程周次超出学期范围" }
        require(rule.startPeriod in 1..(semester?.times()?.size ?: 10) && rule.endPeriod in rule.startPeriod..(semester?.times()?.size ?: 10)) { "节次超出范围" }
        require(rule.pattern in setOf("all","odd","even","custom")) { "周次规则无效" }
        if (rule.pattern == "custom") {
            val weeks = rule.customWeeks.split(',').map { it.trim().toIntOrNull() }
            require(weeks.isNotEmpty() && weeks.all { it != null && it in course.firstWeek..course.lastWeek }) { "指定周请用英文逗号分隔，并在起止周内" }
        }
        require((course.firstWeek..course.lastWeek).any { rule.occurs(course,it) }) { "此规则没有任何上课周次" }
        checkCourseConflict(course,rule)
        dao.save(course.copy(title=course.title.trim(),slot=((rule.startPeriod-1)/2).coerceIn(0,4)))
        db.features().save(rule)
    }
    suspend fun saveTodo(value: CampusTodo) {
        require(value.title.isNotBlank()) { "请填写待办标题" }
        require(!value.remind) { "提醒将在后续通知阶段接入，请先关闭提醒保存" }
        require(value.courseId == null || dao.courses().any { it.id == value.courseId }) { "关联课程已不存在" }
        db.features().save(value.copy(title=value.title.trim()))
    }
    suspend fun deleteTodo(id: String) = db.withTransaction {
        val t = db.features().allTodos().find { it.id == id } ?: return@withTransaction
        db.features().save(TrashEntry(kind="todo",title=t.title,payload=JSONObject().put("id",t.id).put("title",t.title)
            .put("body",t.body).put("dueAt",t.dueAt).put("courseId",t.courseId).put("done",t.done).toString()))
        db.features().deleteTodo(id)
    }
    suspend fun restoreTrash(id: String) = db.withTransaction {
        val item = db.features().allTrash().find { it.id == id } ?: error("回收站条目不存在")
        val json = if(item.kind == "node") null else JSONObject(item.payload)
        when(item.kind) {
            "node" -> {
                val nodes = item.nodes(); val active = dao.nodes().map { it.id }.toMutableSet()
                require(nodes.none { it.id in active }) { "恢复位置存在同 ID 内容，未覆盖任何资料" }
                val pending = nodes.toMutableList()
                while(pending.isNotEmpty()) {
                    val ready = pending.filter { it.parentId == null || it.parentId in active }
                    require(ready.isNotEmpty()) { "上级资料尚未恢复，请先恢复其所属大组或主题" }
                    ready.forEach { dao.save(it); active.add(it.id); pending.remove(it) }
                }
                val activeNotes=dao.notes().map{it.id}.toSet()
                require(item.notes().none{it.id in activeNotes}){"恢复的文字记录已存在，未覆盖原记录"}
                item.notes().forEach{dao.save(it)}
            }
            "note" -> { val j=json!!; require(dao.notes().none { it.id==j.getString("id") }) { "记录已存在" }; dao.save(Note(j.getString("id"),j.getString("title"),j.getString("body"),j.getLong("updatedAt"))) }
            "todo" -> { val j=json!!; require(db.features().allTodos().none { it.id==j.getString("id") }) { "待办已存在" }; db.features().save(CampusTodo(j.getString("id"),j.getString("title"),j.getString("body"),if(j.isNull("dueAt")) null else j.getLong("dueAt"),j.nullable("courseId"),j.optBoolean("done"))) }
            "course" -> {
                val j=json!!; val c=LocalCourse(j.getString("id"),j.getString("title"),j.getInt("day"),j.getInt("slot"),j.getString("room"),j.getString("teacher"),j.getInt("firstWeek"),j.getInt("lastWeek"),j.optBoolean("example"))
                require(dao.courses().none { it.id==c.id }) { "课程已存在" }
                val p=j.optJSONObject("placement")?.let { CoursePlacement(c.id,it.nullable("semesterId"),it.getInt("startPeriod"),it.getInt("endPeriod"),it.getString("pattern"),it.getString("customWeeks")) } ?: CoursePlacement.legacy(c)
                saveScheduledCourse(c,p)
            }
        }
        db.features().deleteTrash(id)
    }
    suspend fun purgeTrash(id: String) = withContext(Dispatchers.IO) { fileLock.withLock {
        val item=db.features().allTrash().find { it.id==id } ?: return@withLock
        val names=item.nodes().mapNotNull { it.localName }
        db.withTransaction {
            db.features().deleteTrash(id)
            val retainedIds=(dao.nodes()+db.features().allTrash().flatMap { it.nodes() }).map { it.id }.toSet()
            val deleted=item.nodes().map { it.id }.filter { it !in retainedIds }
            db.content().deleteSources(deleted)
            db.workflows().all().forEach{workflow->
                val references=runCatching{WorkflowApproval.read(workflow.approval).sources.map{it.id}}.getOrDefault(emptyList())
                if(references.any{it in deleted}){
                    db.workflows().update(workflow.copy(status="invalidated",results="[]",inputs="[]",error="来源已永久删除，任务不可继续",updatedAt=System.currentTimeMillis()))
                    db.workflows().clearMessageReferences(workflow.id)
                }
            }
        }
        val retained=(dao.nodes()+db.features().allTrash().flatMap { it.nodes() }).mapNotNull { it.localName }.toSet()
        names.filter { it !in retained }.forEach { name -> val f=document(name); check(!f.exists()||f.delete()) { "部分副本清理失败，请稍后清理未关联副本" } }
    } }
    suspend fun retainsSource(id: String): Boolean = dao.nodes().any { it.id == id } || db.features().allTrash().any { item -> item.nodes().any { it.id == id } }
    suspend fun inboxTopic(): String = db.withTransaction {
        if(dao.nodes().none { it.id=="inbox" }) dao.save(LibraryNode("inbox",kind=NodeKind.GROUP,title="待整理收件箱"))
        if(dao.nodes().none { it.id=="inbox-subject" }) dao.save(LibraryNode("inbox-subject","inbox",NodeKind.SUBJECT,"未分类"))
        if(dao.nodes().none { it.id=="inbox-topic" }) dao.save(LibraryNode("inbox-topic","inbox-subject",NodeKind.TOPIC,"待整理"))
        "inbox-topic"
    }
    suspend fun linkRecord(link: RecordLink) {
        require(link.courseId==null||dao.courses().any { it.id==link.courseId }) { "课程已不存在" }
        require(link.topicId==null||dao.nodes().any { it.id==link.topicId&&it.kind==NodeKind.TOPIC }) { "主题已不存在" }
        require(dao.notes().any { it.id==link.recordId }||dao.nodes().any { it.id==link.recordId }) { "记录已不存在" }
        db.features().save(link)
    }
    suspend fun archiveOriginal(recordId: String, topicId: String) = db.withTransaction {
        val all=dao.nodes(); val node=all.find { it.id==recordId&&it.kind==NodeKind.ORIGINAL } ?: error("原件已不存在")
        require(all.any { it.id==topicId&&it.kind==NodeKind.TOPIC }) { "主题已不存在" }
        dao.save(node.copy(parentId=topicId))
    }
    suspend fun assignRecord(recordId:String,courseId:String?,topicId:String?) = db.withTransaction {
        linkRecord(RecordLink(recordId,courseId,topicId))
        if(dao.nodes().any{it.id==recordId&&it.kind==NodeKind.ORIGINAL})archiveOriginal(recordId,topicId?:inboxTopic())
    }
    suspend fun importDocuments(topicId:String,uris:List<Uri>):String {
        var saved=0;var failed=0
        uris.forEach {uri->try{importDocument(topicId,uri);saved++}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;failed++}}
        return if(failed==0)"已导入 $saved 份原件，未调用 AI；外部源文件不变" else "已保存 $saved 份原件，$failed 份导入失败；已保存内容保留，请检查文件权限、大小及存储空间后重试失败文件"
    }
}
