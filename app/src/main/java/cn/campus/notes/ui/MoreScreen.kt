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

@Composable
fun MoreScreen() {
    val vm = LocalCampus.current
    val data = LocalContent.current
    val settings by vm.settings.collectAsState()
    val bytes by vm.storageBytes.collectAsState()
    val busy by vm.busy.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var clean by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.storageBytes.value = vm.repository.storageBytes() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("more_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExampleNotice("更多 · V1.0 · 本地功能")
        listOf(
            Triple("review", "复习", "复习计划与卡片 · 尚未实现"),
            Triple("models", "模型配置", "分别保存 DeepSeek、ASR、OCR 配置；调用尚未接入"),
            Triple("permissions", "沙箱权限", "保存整理授权偏好；执行器尚未实现"),
            Triple("storage", "本地存储", "查看占用与清理未关联副本")
        ).forEach { (key,title,description) -> Box(Modifier.testTag("more_$key")) {
            FeatureCard(title,description) { selected = key }
        } }
        if (data.nodes.isEmpty() && data.courses.isEmpty() && data.notes.isEmpty()) {
            OutlinedButton(onClick = { vm.act("示例已载入，示例条目均带标注") { vm.repository.loadExamples() } },
                enabled = !busy, modifier = Modifier.testTag("load_examples")) { Text("载入示例课表与资料结构") }
        }
        Text("0.2.0 · 本地优先\n记录与资料保存在本机。尚未接入 AI、识别或云同步。卸载应用会删除本地数据；备份功能尚未实现。",
            style = MaterialTheme.typography.bodySmall)
    }
    when (selected) {
        "review" -> NotImplementedDialog("复习 · 尚未实现", "后续基于本地课堂记录构建复习计划。") { selected = null }
        "models" -> ModelSettingsDialog { selected = null }
        "permissions" -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("沙箱权限") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("允许未来的整理代理在 App 私有目录内按大组规则整理。当前只保存偏好，尚无自动执行任务。")
                Row { Text("允许沙箱内整理", Modifier.weight(1f).padding(top = 12.dp))
                    Switch(settings.sandboxEnabled, { enabled -> vm.act { vm.settingsStore.sandbox(enabled) } }, enabled = !busy) }
                Text("沙箱外敏感修改必须逐次展示操作内容并确认，不能通过此开关跳过。原件默认保留。")
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("返回") } })
        "storage" -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("本地存储") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("文件副本：${"%.2f".format(bytes / 1024.0 / 1024.0)} MB\n文字记录：${data.notes.size} 条\n课程：${data.courses.size} 门\n资料条目：${data.nodes.size} 项")
                Text("清理仅删除未被资料库引用的导入残留文件；保留全部已关联副本。若需删除资料，请在资料库中逐项操作。")
                OutlinedButton(onClick = { clean = true }, enabled = !busy) { Text("清理未关联副本") }
                Text("备份与恢复尚未实现。卸载应用会删除本地数据库、设置和文件副本。")
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("返回") } })
    }
    if (clean) DeleteConfirmation("未关联副本", "仅清理本应用内没有资料条目引用的文件，不能恢复。", { clean = false }) {
        vm.act("未关联副本清理完成", { clean = false }) { vm.repository.cleanOrphans() }
    }
}

@Composable
private fun ModelSettingsDialog(onDismiss: () -> Unit) {
    val vm = LocalCampus.current
    val settings by vm.settings.collectAsState()
    val busy by vm.busy.collectAsState()
    var provider by rememberSaveable { mutableStateOf(ProviderKind.DEEPSEEK.name) }
    val kind = ProviderKind.valueOf(provider)
    val config = settings.providers[kind] ?: ProviderConfig()
    key(kind) {
        var endpoint by rememberSaveable { mutableStateOf(config.endpoint) }
        var model by rememberSaveable { mutableStateOf(config.model) }
        // Deliberately not saveable: never place plaintext API keys in saved instance state.
        var secret by remember { mutableStateOf("") }
        var removeKey by rememberSaveable { mutableStateOf(false) }
        EditorFrame("模型配置", { if (!busy) onDismiss() }, footer = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("返回") }
            TextButton(enabled = !busy, onClick = {
                vm.act("${kind.label} 配置已保存在本机", { secret = ""; onDismiss() }) {
                    vm.settingsStore.save(kind,endpoint,model,secret,removeKey)
                }
            }) { Text("保存配置") }
        }) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    ProviderKind.entries.forEach { item -> FilterChip(kind == item, { provider = item.name },
                        label = { Text(item.label) }, enabled = !busy) }
                }
                Text("各服务独立保存；切换服务前请先保存。当前不调用网络或验证服务可用性。")
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS API 地址") }, singleLine = true, enabled = !busy, modifier = Modifier.testTag("model_endpoint"))
                OutlinedTextField(model, { model = it }, label = { Text("模型 / 服务名称") }, singleLine = true, enabled = !busy, modifier = Modifier.testTag("model_name"))
                Text(if (config.hasKey) "已保存加密密钥；留空保持原值" else "尚未保存密钥")
                OutlinedTextField(secret, { secret = it }, label = { Text("新 API 密钥") }, visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, enabled = !busy && !removeKey)
                Row { Checkbox(removeKey, { removeKey = it }, enabled = !busy); Text("保存时移除已有密钥", Modifier.padding(top = 12.dp)) }
                Text("密钥由 Android Keystore 保护，不回显。密钥输入不随旋转恢复。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
