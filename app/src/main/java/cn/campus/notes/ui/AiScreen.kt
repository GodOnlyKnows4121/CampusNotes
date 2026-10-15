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
import cn.campus.notes.integration.*

@Composable
fun AiScreen() {
    val vm = LocalCampus.current
    val running by vm.aiRunning.collectAsState()
    val messages by vm.chat.collectAsState()
    val ready by vm.aiReady.collectAsState()
    val active by vm.activeChat.collectAsState()
    val approval by vm.workflowApproval.collectAsState()
    var workflowDetail by rememberSaveable{mutableStateOf<String?>(null)}
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var sessionsOpen by rememberSaveable { mutableStateOf(false) }
    val modes = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp).testTag("ai_screen")) {
      val compact = maxHeight < 300.dp || LocalDensity.current.fontScale > 1.3f
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(mode == 0, { mode = 0 }, label = { Text("对话") }, modifier = Modifier.testTag("ai_chat"))
            FilterChip(mode == 1, { mode = 1 }, label = { Text("代理") }, modifier = Modifier.testTag("ai_agent"))
            if(mode==0)TextButton(onClick={sessionsOpen=true},enabled=ready,modifier=Modifier.testTag("chat_sessions")){Text("会话")}
            if (mode == 0 && running == null) TextButton(onClick = { clear = true }, enabled = messages.any{it.sessionId==active} && ready) { Text("清空") }
            if (compact && running != null) TextButton(onClick = vm::cancelAi, modifier = Modifier.testTag("ai_cancel")) { Text("停止") }
        }
        if (!compact && running != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${running}中…", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::cancelAi, modifier = Modifier.testTag("ai_cancel")) { Text("停止") }
        }
        modes.SaveableStateProvider(mode) { if (mode == 0) key(active){ChatPanel(compact){workflowDetail=it}} else AgentPanel{workflowDetail=it} }
      }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空本机对话？") },
        text = { Text("只删除当前会话的消息；其他会话、当前草稿和已另存的记录保留。服务商端数据不由本应用删除。") },
        confirmButton = { TextButton(onClick = { vm.clearChat(); clear = false }, enabled = running == null) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
    if(sessionsOpen)ChatSessionsDialog{sessionsOpen=false}
    approval?.let{WorkflowPreview(it){vm.workflowApproval.value=null}}
    workflowDetail?.let{WorkflowDetailDialog(it){workflowDetail=null}}
}

@Composable
private fun ColumnScope.ChatPanel(compact: Boolean,onWorkflow:(String)->Unit) {
    val vm = LocalCampus.current
    val allMessages by vm.chat.collectAsState()
    val live by vm.liveReply.collectAsState()
    val running by vm.aiRunning.collectAsState()
    val ready by vm.aiReady.collectAsState()
    val settings by vm.settings.collectAsState()
    val active by vm.activeChat.collectAsState();val sessions by vm.sessions.collectAsState();val drafts by vm.chatDrafts.collectAsState()
    val session=sessions.find{it.id==active}
    val config = settings.chatProfiles.find{it.id==session?.profileId}
    val messages=allMessages.filter{it.sessionId==active}
    val draft=drafts[active]?:session?.draft.orEmpty()
    val levels=ModelCapabilities.levels(config?.endpoint.orEmpty(),config?.model.orEmpty())
    val allAttachments by vm.attachments.collectAsState();val allWorkflows by vm.workflows.collectAsState()
    val attached=allAttachments.filter{it.sessionId==active}.mapNotNull{item->LocalContent.current.nodes.find{it.id==item.sourceId}}
    val workflows=allWorkflows.filter{it.sessionId==active&&runCatching{WorkflowApproval.read(it.approval).mode=="chat"}.getOrDefault(false)}
    val scroll = rememberLazyListState()
    if (!compact) Text("${session?.title?:"正在读取会话"}\n"+if (config?.hasKey==true && config.endpoint.isNotBlank() && config.model.isNotBlank())
        "发送到 ${config.endpoint} · ${config.model}" else "请配置所选语言模型的地址、模型和密钥。",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
    var allWorkflowHistory by rememberSaveable{mutableStateOf(false)}
    val visibleWorkflows=if(allWorkflowHistory)workflows else workflows.take(10)
    val workflowRows=visibleWorkflows.size+if(workflows.size>10)1 else 0
    LaunchedEffect(messages.size,workflowRows) { if (messages.isNotEmpty()) scroll.animateScrollToItem(workflowRows+messages.lastIndex) }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_history"), state = scroll,
        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        visibleWorkflows.forEach{task->item(key="workflow_${task.id}"){WorkflowCard(task){onWorkflow(task.id)}}}
        if(workflows.size>10)item{TextButton(onClick={allWorkflowHistory=!allWorkflowHistory}){Text(if(allWorkflowHistory)"仅显示最近10项联动任务" else "查看全部${workflows.size}项联动任务")}}
        if (messages.isEmpty()&&workflows.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical=if(compact)4.dp else 24.dp),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(!compact){LineGlyph("助手");Text("今天想聊些什么？",style=MaterialTheme.typography.headlineSmall);Text("解释知识点，梳理课堂内容。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)}
                listOf("帮我解释函数与极限","帮我梳理课堂笔记的重点").forEach{prompt->OutlinedButton(onClick={vm.updateDraft(active,prompt)},modifier=Modifier.fillMaxWidth(),enabled=ready){Text(prompt,style=MaterialTheme.typography.bodySmall)}}
                Text("主动发送时调用已配置服务，不会自动读取资料库。",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
            }
        }
        items(messages, key = { it.id }) { row ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("chat_${row.id}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (row.role == "user") "我" else "${row.modelName.ifBlank{"旧消息未记录模型"}} · ${chatStatus(row.status)}", style = MaterialTheme.typography.labelLarge)
                    if(row.role=="assistant"&&row.modelName.isNotBlank())Text("本次强度：${runCatching{ThinkingLevel.from(row.reasoning).label}.getOrDefault(row.reasoning)}",style=MaterialTheme.typography.labelSmall)
                    val body = if (row.status == "streaming" && live.isNotEmpty()) live else row.content
                    SelectionContainer { Text(body.ifBlank { if (row.status == "streaming") "等待模型回复…" else "没有返回文字" }) }
                    if (row.detail.isNotBlank()) Text(row.detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (row.role == "assistant" && row.status == "complete") TextButton(onClick = {
                        vm.act("回答已保存为本地记录") { vm.repository.saveNote(Note(title = "AI 对话摘录", body = row.content)) }
                    }) { Text("保存为记录") }
                    row.workflowId?.let{id->TextButton(onClick={onWorkflow(id)}){Text("查看本次资料与引用来源")}}
                }
            }
        }
    }
    var configOpen by rememberSaveable { mutableStateOf(false) }
    var attachOpen by rememberSaveable { mutableStateOf(false) }
    var audioOnly by rememberSaveable { mutableStateOf(false) }
    var strengthMenu by remember { mutableStateOf(false) };var modelMenu by remember { mutableStateOf(false) }
    if(attached.isNotEmpty())Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
        attached.forEach{source->InputChip(selected=true,onClick={vm.setAttachment(active,source.id,false)},label={Text("${source.title} ×",maxLines=1)},modifier=Modifier.testTag("attached_${source.id}"))}
    }
    Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
        border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(8.dp)) {
            OutlinedTextField(draft, { vm.updateDraft(active,it) }, placeholder = { Text("输入问题，或聊聊课堂内容…",style=MaterialTheme.typography.bodySmall) },
                modifier = Modifier.fillMaxWidth().testTag("ai_draft"),minLines=1,maxLines=if(compact)1 else 3,enabled=ready,
                colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedBorderColor=androidx.compose.ui.graphics.Color.Transparent))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                TextButton(onClick={audioOnly=false;attachOpen=true},contentPadding=PaddingValues(2.dp),modifier=Modifier.width(24.dp).testTag("ai_attach")){Text("＋")}
                TextButton(onClick={audioOnly=true;attachOpen=true},contentPadding=PaddingValues(2.dp),modifier=Modifier.testTag("ai_voice")){Text("语音",style=MaterialTheme.typography.labelSmall)}
                Box{TextButton(onClick={strengthMenu=true},contentPadding=PaddingValues(2.dp),modifier=Modifier.testTag("chat_strength")){Text((runCatching{ThinkingLevel.from(session?.reasoning?:"default").label}.getOrDefault("设置强度"))+" ⌄",style=MaterialTheme.typography.labelSmall)}
                    DropdownMenu(strengthMenu,{strengthMenu=false}){levels.forEach{level->DropdownMenuItem(text={Text(level.label)},modifier=Modifier.testTag("choose_strength_${level.id}"),onClick={session?.let{vm.selectChatModel(it.profileId,level.id)};strengthMenu=false})};DropdownMenuItem(text={Text(if(levels.size==1)"此模型暂只提供默认档位" else "深入思考可能增加耗时与用量",style=MaterialTheme.typography.bodySmall)},onClick={},enabled=false)}
                }
                Box(Modifier.weight(1f)){TextButton(onClick={modelMenu=true},contentPadding=PaddingValues(2.dp),modifier=Modifier.fillMaxWidth().testTag("chat_model")){Text(config?.model?.ifBlank{"选择模型"}.orEmpty().ifBlank{"选择模型"}+" ⌄",style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
                    DropdownMenu(modelMenu,{modelMenu=false}){settings.chatProfiles.forEach{profile->DropdownMenuItem(text={Text("${profile.title} · ${profile.model.ifBlank{"未配置"}}")},modifier=Modifier.testTag("choose_profile_${profile.id}"),onClick={vm.selectChatModel(profile.id,"default");modelMenu=false})};DropdownMenuItem(text={Text("管理语言模型")},onClick={modelMenu=false;configOpen=true})}
                }
                Button(onClick={if(attached.isEmpty())vm.sendChat(draft) else vm.prepareWorkflow("chat",draft,attached.map{it.id})},enabled=ready&&running==null&&draft.isNotBlank()&&session!=null&&config!=null,contentPadding=PaddingValues(0.dp),modifier=Modifier.size(36.dp).testTag("ai_send")){Text("↑")}
            }
        }
    }
    if(configOpen)ChatProfilesDialog{configOpen=false}
    if(attachOpen)AttachmentPicker(active,audioOnly){attachOpen=false}
}

@Composable
private fun AgentPanel(onWorkflow:(String)->Unit) {
    val vm = LocalCampus.current
    val data = LocalContent.current
    val tasks by vm.tasks.collectAsState()
    val running by vm.aiRunning.collectAsState()
    val settings by vm.settings.collectAsState()
    val ready by vm.aiReady.collectAsState()
    val workflows by vm.workflows.collectAsState()
    var goal by rememberSaveable{mutableStateOf("按本组规则整理课堂资料")}
    var profileId by rememberSaveable{mutableStateOf(LEGACY_CHAT)};var reasoning by rememberSaveable{mutableStateOf("default")}
    val config = settings.chatProfiles.find{it.id==profileId}
    val supportedLevels = ModelCapabilities.levels(config?.endpoint.orEmpty(),config?.model.orEmpty())
    LaunchedEffect(config?.endpoint,config?.model) {
        if(reasoning !in supportedLevels.map{it.id})reasoning="default"
    }
    var modelMenu by remember{mutableStateOf(false)};var strengthMenu by remember{mutableStateOf(false)}
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    val topics = data.nodes.filter { it.kind == NodeKind.TOPIC }
    val topic = topics.firstOrNull { it.id == selected } ?: topics.firstOrNull()
    val subject = data.node(topic?.parentId)
    val group = data.node(subject?.parentId)
    val originals = data.children(topic?.id).filter { it.kind == NodeKind.ORIGINAL }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("agent_panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("资料整理代理", style = MaterialTheme.typography.titleLarge)
        Row {
            Box{TextButton(onClick={modelMenu=true}){Text(config?.title?:"选择语言模型")};DropdownMenu(modelMenu,{modelMenu=false}){settings.chatProfiles.forEach{p->DropdownMenuItem(text={Text("${p.title} · ${p.model}")},onClick={profileId=p.id;reasoning="default";modelMenu=false})}}}
            Box{TextButton(onClick={strengthMenu=true}){Text(runCatching{ThinkingLevel.from(reasoning).label}.getOrDefault("模型默认"))};DropdownMenu(strengthMenu,{strengthMenu=false}){ModelCapabilities.levels(config?.endpoint.orEmpty(),config?.model.orEmpty()).forEach{l->DropdownMenuItem(text={Text(l.label)},onClick={reasoning=l.id;strengthMenu=false})}}}
        }
        Text("可使用 UTF-8 原件，或先在原件详情中识别/解析并核对确认的文字。不会将未确认的音频、图片或文档视为已理解内容。",
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
                Text("点击生成会把这些文本及规则发送到 ${config?.endpoint?.ifBlank { "尚未配置的语言模型服务" }?:"尚未配置的语言模型服务"}。")
                Text(if (settings.sandboxEnabled) "已授权沙箱整理：有效计划生成后自动新建一份成果。"
                    else "尚未授权自动整理：先生成预览，确认后才在沙箱内写入。")
            }
        }
        Button(onClick = { topic?.let { vm.planOrganize(it.id,profileId,reasoning) } },
            enabled = ready && running == null && topic != null && originals.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("agent_generate")) { Text("发送原件并生成整理计划") }
        OutlinedTextField(goal,{if(it.length<=8000)goal=it},label={Text("联动整理目标")},modifier=Modifier.fillMaxWidth().testTag("workflow_goal"))
        Button(enabled=ready&&running==null&&topic!=null&&originals.isNotEmpty()&&goal.isNotBlank(),onClick={vm.prepareWorkflow("organize",goal,originals.map{it.id},profileId,reasoning,topic?.id)},modifier=Modifier.fillMaxWidth().testTag("agent_workflow")){Text("联动识别 / 解析后整理")}
        workflows.filter{runCatching{WorkflowApproval.read(it.approval).mode=="organize"}.getOrDefault(false)}.forEach{task->WorkflowCard(task){onWorkflow(task.id)}}
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
            Text("本次模型：${task.modelName.ifBlank{"旧任务未记录"}}\n强度：${runCatching{ThinkingLevel.from(task.reasoning).label}.getOrDefault(task.reasoning)}")
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
