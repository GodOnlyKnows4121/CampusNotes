package cn.campus.notes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.campus.notes.data.*

@Composable
fun ScheduleScreen() {
    val vm = LocalCampus.current
    val courses = LocalContent.current.courses
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var week by rememberSaveable { mutableIntStateOf(1) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = listOf("1–2 节" to "08:00", "3–4 节" to "10:00", "5–6 节" to "14:00", "7–8 节" to "16:00", "9–10 节" to "19:00")
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("schedule_screen")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("大学周课表", modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { editor = "new" }, modifier = Modifier.testTag("new_course")) { Text("新增课程") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { expandedId = null; week-- }, enabled = week > 1) { Text("上一周") }
            Text("第 $week 周", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { expandedId = null; week++ }, enabled = week < 30) { Text("下一周") }
        }
        Text("手动切换周次 · 每格为两节课 · 点击课程管理", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
            val cellWidth = ((maxWidth - 44.dp) / 7).coerceAtLeast(
                if (LocalDensity.current.fontScale > 1.15f) 64.dp else 32.dp)
            Column(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                Row {
                    Spacer(Modifier.width(44.dp))
                    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEach {
                        Text(it, modifier = Modifier.width(cellWidth).padding(4.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                rows.forEachIndexed { slot, (label, time) ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Column(Modifier.width(44.dp).padding(vertical = 12.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                            Text(time, style = MaterialTheme.typography.labelSmall)
                        }
                        repeat(7) { day ->
                            val course = courses.firstOrNull { it.day == day && it.slot == slot && week in it.firstWeek..it.lastWeek }
                            Box(Modifier.width(cellWidth).padding(2.dp)) {
                                if (course == null) {
                                    Surface(Modifier.fillMaxWidth().height(100.dp), shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceContainerLow) {}
                                } else {
                                    Card(onClick = { expandedId = course.id }, modifier = Modifier.fillMaxWidth()
                                        .heightIn(min = 100.dp).testTag("course_${course.id}"),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                        Column(Modifier.padding(2.dp)) {
                                            Text(course.title, style = MaterialTheme.typography.labelMedium, maxLines = 4,
                                                overflow = TextOverflow.Ellipsis)
                                            Spacer(Modifier.height(6.dp))
                                            Text(if (cellWidth < 56.dp) course.room.substringAfterLast(' ') else course.room,
                                                style = MaterialTheme.typography.labelSmall, maxLines = 3,
                                                overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    // Anchored to the tapped cell; no full-screen detail page.
                                    DropdownMenu(expanded = expandedId == course.id, onDismissRequest = { expandedId = null },
                                        modifier = Modifier.widthIn(max = 280.dp).testTag("course_details")) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(course.title, style = MaterialTheme.typography.titleMedium)
                                            Text("${listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[course.day]} · $label · $time")
                                            Text(course.room)
                                            Text("教师：${course.teacher}")
                                            Text("第 ${course.firstWeek}–${course.lastWeek} 周")
                                            if (course.example) ExampleNotice("示例课程")
                                            Row {
                                                TextButton(onClick = { expandedId = null; editor = course.id }) { Text("编辑") }
                                                TextButton(onClick = { expandedId = null; deleting = course.id }) { Text("删除") }
                                            }
                                            TextButton(onClick = { expandedId = null }) { Text("收起详情") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    editor?.let { id -> key(id) { CourseEditor(courses.find { it.id == id }) { editor = null } } }
    deleting?.let { id -> DeleteConfirmation("课程", "删除本地课程后无法恢复。", { deleting = null }) {
        vm.act("课程已删除", { deleting = null }) { vm.repository.deleteCourse(id) }
    } }
}
