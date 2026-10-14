package cn.campus.notes.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.campus.notes.data.*

@Composable
fun LibraryScreen() {
    val vm = LocalCampus.current
    val data = LocalContent.current
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var subjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var topicId by rememberSaveable { mutableStateOf<String?>(null) }
    var artifactTab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImport by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = data.children(null)
    val group = data.node(groupId)?.takeIf { it.kind == NodeKind.GROUP } ?: groups.firstOrNull()
    val subject = data.node(subjectId)?.takeIf { it.parentId == group?.id }
    val topic = data.node(topicId)?.takeIf { subject != null && it.parentId == subject.id }
    fun back() { if (topic != null) topicId = null else subjectId = null }
    BackHandler(enabled = subject != null) { back() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val destination = pendingImport
        pendingImport = null
        if (uri != null && destination != null) vm.act("已导入本地副本，原文件保持不变") { vm.repository.importDocument(destination, uri) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("library_screen")) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { item ->
                FilterChip(selected = group?.id == item.id, onClick = {
                    groupId = item.id; subjectId = null; topicId = null; artifactTab = 0
                }, label = { Text(item.title + if (item.example) " · 示例" else "") }, modifier = Modifier.testTag("group_${item.id}"))
            }
            AssistChip(onClick = { edit = "new_group" }, label = { Text("＋ 大组") }, enabled = !busy)
        }
        if (group != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("各大组独立整理规则", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { edit = group.id }, modifier = Modifier.testTag("group_rule")) { Text("编辑规则") }
        }
        OutlinedTextField(query, { query = it }, singleLine = true,
            label = { Text("AI 检索 · 尚未接入", maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.fillMaxWidth().testTag("library_search"),
            trailingIcon = { TextButton(onClick = {}, enabled = false) { Text("检索") } })
        if (group != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { subjectId = null; topicId = null }) { Text(group.title) }
            if (subject != null) TextButton(onClick = { topicId = null }) { Text("› ${subject.title}") }
            if (topic != null) TextButton(onClick = {}) { Text("› ${topic.title}") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (group == null) Text("先创建资料大组，每个大组可以设置独立的整理规则。")
            else {
                val current = topic ?: subject ?: group
                if (current.example) ExampleNotice("示例结构 · 可编辑 · 无虚构文件")
                Row {
                    TextButton(onClick = { edit = current.id }) { Text("编辑${if (topic != null) "主题" else if (subject != null) "学科" else "大组"}") }
                    TextButton(onClick = { deleting = current.id }) { Text("删除") }
                }
                if (topic == null) {
                    val children = data.children(current.id)
                    if (children.isEmpty()) Text(if (subject == null) "还没有学科" else "还没有课堂主题")
                    children.forEachIndexed { index, item ->
                        Box(Modifier.testTag(if (subject == null) "subject_${item.id}" else "topic_$index")) {
                            FeatureCard(item.title, "${data.children(item.id).size} 项内容${if (item.example) " · 示例" else ""}") {
                                if (subject == null) { subjectId = item.id; topicId = null } else { topicId = item.id; artifactTab = 0 }
                            }
                        }
                    }
                    OutlinedButton(onClick = { edit = if (subject == null) "new_subject" else "new_topic" }, enabled = !busy) {
                        Text(if (subject == null) "新增学科" else "新增课堂主题")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(artifactTab == 0, { artifactTab = 0 }, label = { Text("原件") }, modifier = Modifier.testTag("originals_tab"))
                        FilterChip(artifactTab == 1, { artifactTab = 1 }, label = { Text("AI 成果") }, modifier = Modifier.testTag("outputs_tab"))
                    }
                    val files = data.children(topic.id).filter { it.kind == if (artifactTab == 0) NodeKind.ORIGINAL else NodeKind.OUTPUT }
                    if (files.isEmpty()) {
                        Text(if (artifactTab == 0) "暂无原件" else "暂无 AI 成果", modifier = Modifier.testTag("empty_outputs"))
                        Text(if (artifactTab == 0) "导入后保存本地副本，单文件上限 100 MB。" else "AI 尚未接入；生成成果会与原件分开保存。")
                    }
                    files.forEach { file ->
                        OutlinedCard {
                            Column(Modifier.padding(12.dp)) {
                                Text(file.title, style = MaterialTheme.typography.titleMedium)
                                Text("${file.bytes} 字节 · 本地副本", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = {
                                        try {
                                            val local = vm.repository.document(file.localName ?: error("无本地文件"))
                                            check(local.isFile)
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.documents", local)
                                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                        } catch (_: Exception) { vm.error.value = "无法打开文件：请确认本地副本存在，并安装支持该格式的应用" }
                                    }) { Text("打开") }
                                    TextButton(onClick = { edit = file.id }) { Text("重命名") }
                                    TextButton(onClick = { deleting = file.id }) { Text("删除") }
                                }
                            }
                        }
                    }
                    if (artifactTab == 0) OutlinedButton(enabled = !busy && pendingImport == null, onClick = {
                        pendingImport = topic.id
                        try { importer.launch(arrayOf("*/*")) } catch (_: Exception) {
                            pendingImport = null; vm.error.value = "无法打开系统文件选择器"
                        }
                    }, modifier = Modifier.fillMaxWidth().testTag("import_document")) { Text("导入资料") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    edit?.let { id -> key(id) {
        val existing = data.node(id)
        val kind = existing?.kind ?: when(id) { "new_group" -> NodeKind.GROUP; "new_subject" -> NodeKind.SUBJECT; else -> NodeKind.TOPIC }
        val parent = existing?.parentId ?: when(kind) { NodeKind.SUBJECT -> group?.id; NodeKind.TOPIC -> subject?.id; else -> null }
        val fields = if (kind == NodeKind.GROUP) listOf("大组名称" to (existing?.title ?: ""), "整理规则" to (existing?.rule ?: "保留原件，成果单独保存。"))
            else listOf("名称" to (existing?.title ?: ""))
        TextEditor(if (existing == null) "新增资料层级" else "编辑资料", fields, { edit = null }) { values ->
            vm.act("已保存", { edit = null }) {
                val node = existing ?: LibraryNode(parentId = parent, kind = kind, title = values[0])
                vm.repository.saveNode(node.copy(title = values[0], rule = values.getOrNull(1) ?: node.rule, example = false))
            }
        }
    } }
    deleting?.let { id -> DeleteConfirmation(data.node(id)?.title ?: "资料", "将删除所选条目及全部下级内容和本地副本，无法恢复。外部原始文件不会被修改。", { deleting = null }) {
        vm.act("本地资料已删除", { deleting = null }) { vm.repository.deleteNode(id) }
    } }
}
