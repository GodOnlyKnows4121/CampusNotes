package cn.campus.notes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun AiScreen() {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)
        .testTag("ai_screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("对话") }, modifier = Modifier.testTag("ai_chat"))
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("代理") }, modifier = Modifier.testTag("ai_agent"))
        }
        Text(if (mode == 0) "你的校园 AI 助手" else "资料整理代理", style = MaterialTheme.typography.headlineSmall)
        Text("尚未接入 DeepSeek。可在更多中保存模型配置；当前不发送网络请求。",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (mode == 0) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("对话草稿") },
                placeholder = { Text("输入问题，草稿仅保留页面状态") }, modifier = Modifier.fillMaxWidth().testTag("ai_draft"),
                minLines = 3, maxLines = 6)
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("发送 · 尚未接入") }
            Text("语音识别与图片识别将独立配置，不依赖对话模型提供识别能力。", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("计划中的执行边界", style = MaterialTheme.typography.titleMedium)
                    Text("• 按当前资料大组的规则整理\n• 沙箱内执行授权任务\n• 沙箱外敏感修改先展示内容并确认\n• 保留原件和执行日志")
                    Text("权限检查和文件执行器尚未接入。", color = MaterialTheme.colorScheme.secondary)
                }
            }
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("开始整理 · 尚未实现") }
        }
    }
}
