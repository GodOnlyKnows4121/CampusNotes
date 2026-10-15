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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import cn.campus.notes.data.*

@Composable
fun LibraryScreen() {
    val vm = LocalCampus.current
    val data = LocalContent.current
    val busy by vm.busy.collectAsState()
    val links by vm.links.collectAsState()
    val context = LocalContext.current
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var subjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var topicId by rememberSaveable { mutableStateOf<String?>(null) }
    var artifactTab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImport by rememberSaveable { mutableStateOf<String?>(null) }
    var searchResults by rememberSaveable { mutableStateOf(false) }
    var fileDetails by rememberSaveable { mutableStateOf<String?>(null) }
    val settings by vm.settings.collectAsState()
    val groups = data.children(null).filter { it.id != "inbox" }
    val group = data.node(groupId)?.takeIf { it.kind == NodeKind.GROUP } ?: groups.firstOrNull()
    val subject = data.node(subjectId)?.takeIf { it.parentId == group?.id }
    val topic = data.node(topicId)?.takeIf { subject != null && it.parentId == subject.id }
    fun back() { if (topic != null) topicId = null else subjectId = null }
    BackHandler(enabled = subject != null) { back() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val destination = pendingImport
        pendingImport = null
        if (uris.isNotEmpty() && destination != null) { var summary="";vm.act(onDone={vm.message.value=summary}) { summary=vm.repository.importDocuments(destination,uris) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("library_screen")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("我的大组",style=MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick={edit="new_group"},contentPadding=PaddingValues(horizontal=12.dp,vertical=0.dp)){Text("＋ 新建",style=MaterialTheme.typography.labelMedium)}
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { item ->
                FilterChip(selected = group?.id == item.id, onClick = {
                    groupId = item.id; subjectId = null; topicId = null; artifactTab = 0
                }, label = { Text(item.title + if (item.example) " · 示例" else "") }, modifier = Modifier.testTag("group_${item.id}"))
            }
        }
        if (group != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(topic?.title?:subject?.title?:group.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(top = 8.dp))
            TextButton(onClick = { edit = group.id }, modifier = Modifier.testTag("group_rule")) { Text((settings.groupModes[group.id]?:if(group.title.contains("工作"))"项目模式" else "课堂模式")+" ⚙",style=MaterialTheme.typography.labelMedium) }
        }
        OutlinedTextField(query, { query = it }, singleLine = true,
            placeholder = { Text("让 AI 帮我查找资料…", maxLines = 1, overflow = TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall) }, modifier = Modifier.fillMaxWidth().testTag("library_search"),shape=RoundedCornerShape(18.dp),
            trailingIcon = { TextButton(onClick = {searchResults=true}) { Text("↑") } })
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
                    if(subject==null) children.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                            pair.forEach {item-> val index=children.indexOf(item)
                                OutlinedCard(onClick={subjectId=item.id;topicId=null},modifier=Modifier.weight(1f).testTag("subject_${item.id}"),shape=RoundedCornerShape(18.dp)) {
                                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                                        Surface(Modifier.fillMaxWidth().height(38.dp),color=listOf(Color(0xFFDCEAFE),Color(0xFFECE7FC),Color(0xFFDCF9E8),Color(0xFFFFECD4))[index%4],shape=RoundedCornerShape(12.dp)) {Text("▱",Modifier.padding(start=12.dp,top=4.dp),color=Color(0xFF3E4E63),style=MaterialTheme.typography.titleLarge)}
                                        Text(item.title,style=MaterialTheme.typography.titleSmall)
                                        Text("文件夹  ›",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                            if(pair.size==1)Spacer(Modifier.weight(1f))
                        }
                    } else children.forEachIndexed { index, item ->
                        Box(Modifier.testTag(if (subject == null) "subject_${item.id}" else "topic_$index")) {
                            FeatureCard(item.title, "${data.children(item.id).size} 项内容${if (item.example) " · 示例" else ""}") {
                                if (subject == null) { subjectId = item.id; topicId = null } else { topicId = item.id; artifactTab = 0 }
                            }
                        }
                    }
                    OutlinedButton(onClick = { edit = if (subject == null) "new_subject" else "new_topic" }, enabled = !busy) {
                        Text(if (subject == null) "新增学科" else "新增课堂主题")
                    }
                    if(subject==null){Text("最近文件",style=MaterialTheme.typography.titleLarge);val recent=data.nodes.filter{it.localName!=null}.takeLast(3)
                        if(recent.isEmpty())Text("暂无文件，进入课堂主题导入资料。",style=MaterialTheme.typography.bodySmall)
                        recent.forEach{file->TextButton(onClick={fileDetails=file.id},modifier=Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth()){Text(file.title,style=MaterialTheme.typography.titleSmall);Text(if(file.kind==NodeKind.OUTPUT)"AI 整理成果" else "原始资料",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)}}}
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(artifactTab == 0, { artifactTab = 0 }, label = { Text("原件") }, modifier = Modifier.testTag("originals_tab"))
                        FilterChip(artifactTab == 1, { artifactTab = 1 }, label = { Text("AI 成果") }, modifier = Modifier.testTag("outputs_tab"))
                    }
                    val files = data.children(topic.id).filter { it.kind == if (artifactTab == 0) NodeKind.ORIGINAL else NodeKind.OUTPUT }
                    val topicNotes=if(artifactTab==0)data.notes.filter{n->links.any{it.recordId==n.id&&it.topicId==topic.id}}else emptyList()
                    topicNotes.forEach{note->FeatureCard(note.title,"文字记录 · 已归档主题"){fileDetails=note.id}}
                    if (files.isEmpty()&&topicNotes.isEmpty()) {
                        Text(if (artifactTab == 0) "暂无原件" else "暂无 AI 成果", modifier = Modifier.testTag("empty_outputs"))
                        Text(if (artifactTab == 0) "导入后保存本地副本，单文件上限 100 MB。" else "在 AI → 代理整理文本原件，成果在此单独保存。")
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
                                    TextButton(onClick = { fileDetails = file.id }) { Text("关联") }
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
        val fields = if (kind == NodeKind.GROUP) listOf("大组名称" to (existing?.title ?: ""), "整理规则" to (existing?.rule ?: "保留原件，成果单独保存。"),"组织模式（课堂模式/项目模式/自定义模式）" to (settings.groupModes[existing?.id]?:if(existing?.title?.contains("工作")==true)"项目模式" else "课堂模式"))
            else listOf("名称" to (existing?.title ?: ""))
        TextEditor(if (existing == null) "新增资料层级" else "编辑资料", fields, { edit = null }) { values ->
            vm.act("已保存", { edit = null }) {
                val node = existing ?: LibraryNode(parentId = parent, kind = kind, title = values[0])
                if(kind==NodeKind.GROUP) require(values[2] in listOf("课堂模式","项目模式","自定义模式")){"请选择课堂模式、项目模式或自定义模式"}
                vm.repository.saveNode(node.copy(title = values[0], rule = values.getOrNull(1) ?: node.rule, example = false))
                if(kind==NodeKind.GROUP)vm.settingsStore.groupMode(node.id,values[2])
            }
        }
    } }
    deleting?.let { id -> DeleteConfirmation(data.node(id)?.title ?: "资料", "所选条目及其下级内容会进入回收站，可恢复。外部源文件不变。", { deleting = null }) {
        vm.act("本地资料已移入回收站", { deleting = null }) { vm.repository.deleteNode(id) }
    } }
    fileDetails?.let{id->LocalRecordDialog(id){fileDetails=null}}
    if(searchResults)EditorFrame("查找本地资料",{searchResults=false},footer={TextButton(onClick={searchResults=false}){Text("返回")}}){
        Text("当前为文件名检索；AI 回答与引用检索将在 AI 工作流阶段接入。",style=MaterialTheme.typography.bodySmall)
        data.nodes.filter{it.localName!=null&&it.title.contains(query,true)}.forEach{file->TextButton(onClick={searchResults=false;fileDetails=file.id}){Text(file.title)}}
    }
}
