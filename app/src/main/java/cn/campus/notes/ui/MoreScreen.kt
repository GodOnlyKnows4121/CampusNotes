package cn.campus.notes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.campus.notes.data.*
import cn.campus.notes.integration.RecognitionConfiguration
import cn.campus.notes.integration.DeepSeekProvider

@Composable
fun MoreScreen() {
    val vm = LocalCampus.current
    val data = LocalContent.current
    val settings by vm.settings.collectAsState()
    val bytes by vm.storageBytes.collectAsState()
    val busy by vm.busy.collectAsState()
    val trashed by vm.trash.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var clean by rememberSaveable { mutableStateOf(false) }
    var trash by rememberSaveable { mutableStateOf(false) }
    var inbox by rememberSaveable { mutableStateOf(false) }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var storageRead by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.storageBytes.value = vm.repository.storageBytes();storageRead=true }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("more_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedCard(Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {Text("我的校园助手",style=MaterialTheme.typography.titleMedium);Text("本地优先 · 记录与资料由你管理",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)}
        }
        Text("学习与工具",style=MaterialTheme.typography.titleLarge)
        listOf(
            Triple("review", "复习", "要点、提纲、自测与错题 · 待接入"),
            Triple("models", "模型配置", "多个语言模型；ASR、OCR 独立配置并主动调用"),
            Triple("permissions", "沙箱权限", "授权按大组规则自动新建整理成果；原件保留"),
            Triple("storage", "本地存储", "原件、成果、回收站与未关联副本")
        ).forEach { (key,title,description) -> Box(Modifier.testTag("more_$key")) {
            FeatureCard(title,description) { selected = key }
        } }
        if (data.nodes.isEmpty() && data.courses.isEmpty() && data.notes.isEmpty() && trashed.isEmpty()) {
            OutlinedButton(onClick = { vm.act("示例已载入，示例条目均带标注") { vm.repository.loadExamples() } },
                enabled = !busy, modifier = Modifier.testTag("load_examples")) { Text("载入示例课表与资料结构") }
        }
        Text("0.6.2 · 本地优先\n独立会话与多模型配置；识别主动发起，文字需核对确认。附件对话、复习与备份尚未实现。卸载应用会删除本地数据。",
            style = MaterialTheme.typography.bodySmall)
    }
    when (selected) {
        "review" -> NotImplementedDialog("复习 · 尚未实现", "后续基于本地课堂记录构建复习计划。") { selected = null }
        "models" -> ModelSettingsDialog { selected = null }
        "permissions" -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("沙箱权限") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("开启后，点击生成整理计划会按所选主题所属大组的规则，自动在 App 私有目录中新建一份 AI 成果。关闭时必须先查看预览并确认写入。可使用 UTF-8 文本或已核对确认的识别/解析文字。")
                Row { Text("允许沙箱内整理", Modifier.weight(1f).padding(top = 12.dp))
                    Switch(settings.sandboxEnabled, { enabled -> vm.act { vm.settingsStore.sandbox(enabled) } }, enabled = !busy) }
                Text("生成计划会上传所选原件文本与规则到配置的 DeepSeek 服务。执行仅新建成果，不删除、覆盖或移动原件。沙箱外修改暂不提供执行入口；未来接入时必须逐次确认。")
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("返回") } })
        "storage" -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("本地存储") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if(storageRead) "文件副本（含回收站）：${"%.2f".format(bytes / 1024.0 / 1024.0)} MB\n文字记录：${data.notes.size} 条\n课程：${data.courses.size} 门\n资料条目：${data.nodes.size} 项" else "正在读取本地占用…")
                OutlinedButton(onClick={inbox=true}){Text("待整理收件箱")}
                OutlinedButton(onClick={recovery=true}){Text("采集恢复")}
                OutlinedButton(onClick={trash=true}){Text("回收站 / 恢复")}
                Text("清理保留资料库及回收站中的全部副本，只删除未关联残留。永久删除请在回收站逐项确认。")
                OutlinedButton(onClick = { clean = true }, enabled = !busy) { Text("清理未关联副本") }
                Text("备份与恢复尚未实现。卸载应用会删除本地数据库、设置和文件副本。")
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("返回") } })
    }
    if(trash)TrashDialog{trash=false}
    if(inbox)InboxDialog{inbox=false}
    if(recovery)CaptureRecoveryDialog{recovery=false}
    if (clean) DeleteConfirmation("未关联副本", "仅清理本应用内没有资料条目引用的文件，不能恢复。", { clean = false }) {
        vm.act("未关联副本清理完成", { clean = false }) { vm.repository.cleanOrphans() }
    }
}

@Composable
fun ModelSettingsDialog(onDismiss: () -> Unit) {
    val vm = LocalCampus.current
    val settings by vm.settings.collectAsState()
    val busy by vm.busy.collectAsState()
    var provider by rememberSaveable { mutableStateOf(ProviderKind.DEEPSEEK.name) }
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    val kind = ProviderKind.valueOf(provider)
    val config = settings.providers[kind] ?: ProviderConfig()
    key(kind) {
        var endpoint by rememberSaveable { mutableStateOf(config.endpoint.ifBlank { if (kind == ProviderKind.DEEPSEEK) "https://api.deepseek.com" else "" }) }
        var model by rememberSaveable { mutableStateOf(config.model.ifBlank { if (kind == ProviderKind.DEEPSEEK) "deepseek-flash" else "" }) }
        // Deliberately not saveable: never place plaintext API keys in saved instance state.
        var secret by remember { mutableStateOf("") }
        var removeKey by rememberSaveable { mutableStateOf(false) }
        val problem=if(kind!=ProviderKind.DEEPSEEK) RecognitionConfiguration.problem(kind,ProviderConfig(endpoint,model)) else null
        EditorFrame("模型配置", { if (!busy) onDismiss() }, footer = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("返回") }
            TextButton(enabled = !busy, onClick = {
                vm.act("${kind.label} 配置已保存在本机", { secret = ""; onDismiss() }) {
                    if(kind!=ProviderKind.DEEPSEEK&&!removeKey)RecognitionConfiguration.validate(kind,endpoint,model)
                    vm.settingsStore.save(kind,endpoint,model,secret,removeKey)
                    if(kind==ProviderKind.DEEPSEEK)vm.normalizeChatProfile(LEGACY_CHAT)
                }
            }) { Text("保存配置") }
        }) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ProviderKind.entries.forEach { item -> FilterChip(kind == item, { provider = item.name },
                        label = { Text(item.label) }, enabled = !busy,modifier=Modifier.testTag("provider_${item.name}")) }
                }
                Text("各服务独立保存；切换服务前请先保存。保存配置不调用网络；对话、整理及 ASR/OCR 均由用户主动发起，识别需在原件详情中确认上传。")
                TextButton(onClick={profilesOpen=true}){Text("管理多个语言模型")}
                if (kind != ProviderKind.DEEPSEEK) Text("识别适配兼容 Chat Completions：ASR 使用 input_audio，OCR 使用 image_url。请填写服务实际支持的完整模型名；不会自动替换配置或保证别名可用。")
                if(kind!=ProviderKind.DEEPSEEK) {
                    OutlinedButton(enabled=!busy,onClick={val example=RecognitionConfiguration.qwenExample(kind);endpoint=example.endpoint;model=example.model},modifier=Modifier.testTag("recognition_example")){Text("填入千问官方示例（未保存）")}
                    Text("仅替换当前编辑草稿，点击保存才生效；已有密钥留空保留。示例不代表账户已开通或连接成功。",style=MaterialTheme.typography.bodySmall)
                    Text(problem?:"本地协议检查通过（未联网）",color=if(problem==null)MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,modifier=Modifier.testTag("recognition_config_status"))
                    if(problem==null)Text("实际请求：POST ${DeepSeekProvider.completionUrl(endpoint)}",style=MaterialTheme.typography.bodySmall)
                }
                if (kind == ProviderKind.DEEPSEEK) Text("地址可填写 API 基址或完整 /chat/completions 地址。模型以服务商当前可用名称为准；请求不会自动重试。")
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS API 地址") }, singleLine = true, enabled = !busy, modifier = Modifier.testTag("model_endpoint"))
                OutlinedTextField(model, { model = it }, label = { Text("模型 / 服务名称") }, singleLine = true, enabled = !busy, modifier = Modifier.testTag("model_name"))
                Text(if (config.hasKey) "已保存加密密钥；留空保持原值" else "尚未保存密钥")
                OutlinedTextField(secret, { secret = it }, label = { Text("新 API 密钥") }, visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !busy && !removeKey)
                Row { Checkbox(removeKey, { removeKey = it }, enabled = !busy); Text("保存时移除已有密钥", Modifier.padding(top = 12.dp)) }
                Text("密钥由 Android Keystore 保护，不回显。密钥输入不随旋转恢复。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if(profilesOpen)ChatProfilesDialog{profilesOpen=false}
}
