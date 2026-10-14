package cn.campus.notes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import cn.campus.notes.data.*

@Composable
fun AiScreen() {
    val vm = LocalCampus.current
    val running by vm.aiRunning.collectAsState()
    val messages by vm.chat.collectAsState()
    val ready by vm.aiReady.collectAsState()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var clear by rememberSaveable { mutableStateOf(false) }
    val modes = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp).testTag("ai_screen")) {
      val compact = maxHeight < 300.dp || LocalDensity.current.fontScale > 1.3f
      Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == 0, { mode = 0 }, label = { Text("对话") }, modifier = Modifier.testTag("ai_chat"))
            FilterChip(mode == 1, { mode = 1 }, label = { Text("代理") }, modifier = Modifier.testTag("ai_agent"))
            if (mode == 0 && running == null) TextButton(onClick = { clear = true }, enabled = messages.isNotEmpty() && ready) { Text("清空") }
            if (compact && running != null) TextButton(onClick = vm::cancelAi, modifier = Modifier.testTag("ai_cancel")) { Text("停止") }
        }
        if (!compact && running != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${running}中…", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::cancelAi, modifier = Modifier.testTag("ai_cancel")) { Text("停止") }
        }
        modes.SaveableStateProvider(mode) { if (mode == 0) ChatPanel(compact) else AgentPanel() }
      }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空本机对话？") },
        text = { Text("将删除全部对话记录并开始新的上下文；已另存的课堂记录保留。服务商端的数据不由本应用删除。") },
        confirmButton = { TextButton(onClick = { vm.clearChat(); clear = false }, enabled = running == null) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}

@Composable
private fun ColumnScope.ChatPanel(compact: Boolean) {
    val vm = LocalCampus.current
    val messages by vm.chat.collectAsState()
    val live by vm.liveReply.collectAsState()
    val running by vm.aiRunning.collectAsState()
    val ready by vm.aiReady.collectAsState()
    val settings by vm.settings.collectAsState()
    val config = settings.providers[ProviderKind.DEEPSEEK] ?: ProviderConfig()
    var draft by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()
    if (!compact) Text(if (config.hasKey && config.endpoint.isNotBlank() && config.model.isNotBlank())
        "发送到 ${config.endpoint} · ${config.model}" else "先在更多 → 模型配置填写 DeepSeek 地址、模型和密钥。",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) scroll.animateScrollToItem(messages.lastIndex) }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_history"), state = scroll,
        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        if (messages.isEmpty()) item {
            Text("主动发送到更多中配置的 DeepSeek 服务，对话保存在本机。每次发送最近最多 20 轮完整对话；不会自动上传资料库。取消和失败的回复保留供查看，不进入后续上下文。",
                style = MaterialTheme.typography.bodySmall)
        }
        items(messages, key = { it.id }) { row ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("chat_${row.id}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (row.role == "user") "我" else "DeepSeek · ${chatStatus(row.status)}", style = MaterialTheme.typography.labelLarge)
                    val body = if (row.status == "streaming" && live.isNotEmpty()) live else row.content
                    SelectionContainer { Text(body.ifBlank { if (row.status == "streaming") "等待模型回复…" else "没有返回文字" }) }
                    if (row.detail.isNotBlank()) Text(row.detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (row.role == "assistant" && row.status == "complete") TextButton(onClick = {
                        vm.act("回答已保存为本地记录") { vm.repository.saveNote(Note(title = "AI 对话摘录", body = row.content)) }
                    }) { Text("保存为记录") }
                }
            }
        }
    }
    OutlinedTextField(draft, { if (it.length <= 8000) draft = it }, label = { Text("对话草稿") },
        placeholder = { Text("输入课堂问题") }, modifier = Modifier.fillMaxWidth().testTag("ai_draft"),
        minLines = 1, maxLines = 3, readOnly = running != null)
    Button(onClick = { vm.sendChat(draft) { draft = "" } }, enabled = ready && running == null && draft.isNotBlank(),
        modifier = Modifier.fillMaxWidth().testTag("ai_send")) { Text("发送") }
}

@Composable
private fun AgentPanel() {
    val vm = LocalCampus.current
    val data = LocalContent.current
    val tasks by vm.tasks.collectAsState()
    val running by vm.aiRunning.collectAsState()
    val settings by vm.settings.collectAsState()
    val ready by vm.aiReady.collectAsState()
    val config = settings.providers[ProviderKind.DEEPSEEK] ?: ProviderConfig()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    val topics = data.nodes.filter { it.kind == NodeKind.TOPIC }
    val topic = topics.firstOrNull { it.id == selected } ?: topics.firstOrNull()
    val subject = data.node(topic?.parentId)
    val group = data.node(subject?.parentId)
    val originals = data.children(topic?.id).filter { it.kind == NodeKind.ORIGINAL }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("agent_panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("资料整理代理", style = MaterialTheme.typography.titleLarge)
        Text("先支持 UTF-8 文本（TXT / Markdown / CSV / JSON）。图片、录音和 PDF 识别尚未接入。",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            topics.forEach { item ->
                val parent = data.node(item.parentId)
                FilterChip(topic?.id == item.id, { selected = item.id }, label = { Text("${parent?.title} / ${item.title}") },
                    enabled = running == null, modifier = Modifier.testTag("agent_topic_${item.id}"))
            }
        }
        if (topic == null) Text("请先在资料库创建主题并导入文本原件。")
        else OutlinedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("大组：${group?.title}")
                Text("本组规则：${group?.rule.orEmpty().ifBlank { "未设置附加规则；保留原件，成果单独保存" }}")
                Text("将上传的原件：${if (originals.isEmpty()) "无" else originals.joinToString { it.title }}")
                Text("点击生成会把这些文本及规则发送到 ${config.endpoint.ifBlank { "尚未配置的 DeepSeek 服务" }}。")
                Text(if (settings.sandboxEnabled) "已授权沙箱整理：有效计划生成后自动新建一份成果。"
                    else "尚未授权自动整理：先生成预览，确认后才在沙箱内写入。")
            }
        }
        Button(onClick = { topic?.let { vm.planOrganize(it.id) } },
            enabled = ready && running == null && topic != null && originals.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("agent_generate")) { Text("发送原件并生成整理计划") }
        Text("执行边界：只在所选主题的 AI 成果区新建文件；保留全部原件。模型不能指定路径或删除文件。沙箱外修改暂不提供执行入口，未来接入时必须逐次确认。",
            style = MaterialTheme.typography.bodySmall)
        Text("任务与执行日志", style = MaterialTheme.typography.titleMedium)
        if (tasks.isEmpty()) Text("尚无整理任务")
        tasks.take(50).forEach { task ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.topicTitle, style = MaterialTheme.typography.titleSmall)
                    Text("${taskStatus(task.status)} · ${java.time.Instant.ofEpochMilli(task.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}")
                    Text(task.detail, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { previewId = task.id }) { Text(if (task.status == "preview") "查看操作预览" else "查看日志") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    tasks.find { it.id == previewId }?.let { task ->
        EditorFrame("${taskStatus(task.status)} · 操作内容", { previewId = null }, footer = {
            TextButton(onClick = { previewId = null }) { Text("返回") }
            if (task.status == "preview") TextButton(onClick = { vm.confirmOrganize(task.id) }, enabled = running == null) { Text("确认写入沙箱") }
        }) {
            Text("主题：${task.topicTitle}\n操作：新建 AI 成果 ${task.title}.md\n原件：保留\n规则：${task.rule}\n来源：\n${task.sources}")
            SelectionContainer { Text(task.body.ifBlank { task.detail }) }
            Text(task.detail)
        }
    }
}

private fun chatStatus(status: String) = when (status) {
    "streaming" -> "生成中"; "complete" -> "已完成"; "cancelled" -> "已停止"; "interrupted" -> "已中断"; else -> "未完成"
}
private fun taskStatus(status: String) = when (status) {
    "planning" -> "生成计划中"; "preview" -> "待确认"; "complete" -> "已执行"; "cancelled" -> "已停止"; "interrupted" -> "已中断"; else -> "未完成"
}
