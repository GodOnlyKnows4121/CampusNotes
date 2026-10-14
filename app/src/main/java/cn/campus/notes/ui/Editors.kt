package cn.campus.notes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.campus.notes.data.*

@Composable
fun TextEditor(title: String, fields: List<Pair<String,String>>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var values by rememberSaveable { mutableStateOf(fields.map { it.second }) }
    val busy by LocalCampus.current.busy.collectAsState()
    EditorFrame(title, { if (!busy) onDismiss() }, footer = {
        TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        TextButton(onClick = { onSave(values) }, enabled = !busy, modifier = Modifier.testTag("editor_save")) { Text("保存") }
    }) {
            fields.forEachIndexed { index, field ->
                OutlinedTextField(values[index], { value -> values = values.toMutableList().also { it[index] = value } },
                    label = { Text(field.first) }, modifier = Modifier.fillMaxWidth().testTag("edit_$index"),
                    minLines = if (field.first == "正文" || field.first == "整理规则") 3 else 1, maxLines = 6,
                    enabled = !busy)
            }
    }
}

/** Constrain the whole editor before allocating space for its scrollable fields and actions. */
@Composable
fun EditorFrame(title: String, onDismiss: () -> Unit, footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val windowSize = LocalWindowInfo.current.containerSize
    val maxHeight = with(LocalDensity.current) { (windowSize.height.toDp() - 48.dp).coerceAtLeast(180.dp) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = footer)
            }
        }
    }
}

@Composable
fun DeleteConfirmation(title: String, description: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val busy by LocalCampus.current.busy.collectAsState()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("删除$title？") }, text = { Text(description) },
        confirmButton = { TextButton(onClick = onDelete, enabled = !busy) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
fun RecordsDialog(onDismiss: () -> Unit) {
    val vm = LocalCampus.current
    val records = LocalContent.current.notes
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("本地记录") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (records.isEmpty()) Text("还没有记录，写下第一条课堂笔记吧。")
            records.forEach { note ->
                OutlinedCard {
                    Column(Modifier.padding(12.dp)) {
                        Text(note.title, style = MaterialTheme.typography.titleMedium)
                        Text(note.body)
                        Row { TextButton(onClick = { editId = note.id }) { Text("编辑") }; TextButton(onClick = { deleteId = note.id }) { Text("删除") } }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { editId = "new" }, modifier = Modifier.testTag("new_note")) { Text("新增记录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } })
    editId?.let { id -> key(id) {
        val note = records.find { it.id == id }
        TextEditor(if (note == null) "新增记录" else "编辑记录", listOf("标题" to (note?.title ?: ""), "正文" to (note?.body ?: "")),
            { editId = null }) { values -> vm.act("记录已保存", { editId = null }) {
                vm.repository.saveNote(Note(note?.id ?: newId(), values[0], values[1]))
            } }
    } }
    deleteId?.let { id -> DeleteConfirmation("记录", "删除后无法恢复。", { deleteId = null }) {
        vm.act("记录已删除", { deleteId = null }) { vm.repository.deleteNote(id) }
    } }
}

@Composable
fun CourseEditor(course: LocalCourse?, onDismiss: () -> Unit) {
    val vm = LocalCampus.current
    TextEditor(if (course == null) "新增课程" else "编辑课程", listOf(
        "课程名称" to (course?.title ?: ""), "星期（1–7，1 为周一）" to ((course?.day ?: 0) + 1).toString(),
        "时段（1–5，对应 1–2 至 9–10 节）" to ((course?.slot ?: 0) + 1).toString(),
        "教室" to (course?.room ?: ""), "教师" to (course?.teacher ?: ""),
        "开始周（1–30）" to (course?.firstWeek ?: 1).toString(), "结束周（1–30）" to (course?.lastWeek ?: 16).toString()
    ), onDismiss) { v -> vm.act("课程已保存", onDismiss) {
        fun number(i: Int) = v[i].trim().toIntOrNull() ?: throw IllegalArgumentException("星期、时段和周次须填写整数")
        vm.repository.saveCourse(LocalCourse(course?.id ?: newId(),v[0],number(1)-1,number(2)-1,v[3],v[4],number(5),number(6)))
    } }
}
