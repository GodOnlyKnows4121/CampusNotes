package cn.campus.notes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Fixed viewport: compact mode removes secondary copy rather than adding scrolling. */
@Composable
fun HomeScreen(onNavigate: (Destination) -> Unit) {
    val data = LocalContent.current
    val today = java.time.LocalDate.now().dayOfWeek.value - 1
    val courseText = data.courses.firstOrNull { it.day == today }?.let { "${it.title}${if (it.example) " · 示例" else ""} · ${it.room}" } ?: "今日暂无课程"
    val latest = data.nodes.lastOrNull { it.kind == cn.campus.notes.data.NodeKind.ORIGINAL }?.title ?: "还没有导入资料"
    var showCapture by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).testTag("home_screen")) {
        val compact = maxHeight < 420.dp || LocalDensity.current.fontScale > 1.3f
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本地首页 · V2.1.10", style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.secondary)
            if (!compact) {
                Text("把课堂和灵感留在这里", style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (landscape) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeTile("今日课程", courseText, "按星期展示，具体周次请查看课表", compact,
                        Modifier.weight(1f).fillMaxHeight()) { onNavigate(Destination.SCHEDULE) }
                    HomeTile("最近资料", latest, "学科 → 课堂主题 → 原件与成果", compact,
                        Modifier.weight(1f).fillMaxHeight()) { onNavigate(Destination.LIBRARY) }
                    HomeTile("AI 助手", "对话与整理代理", "配置 DeepSeek 后使用流式对话与文本整理", compact,
                        Modifier.weight(1f).fillMaxHeight()) { onNavigate(Destination.AI) }
                }
            } else {
                HomeTile("今日课程", courseText, "按星期展示，具体周次请查看课表", compact,
                    Modifier.weight(1f).fillMaxWidth()) { onNavigate(Destination.SCHEDULE) }
                HomeTile("最近资料", latest, "学科 → 课堂主题 → 原件与成果", compact,
                    Modifier.weight(1f).fillMaxWidth()) { onNavigate(Destination.LIBRARY) }
                HomeTile("AI 助手", "对话与整理代理", "配置 DeepSeek 后使用流式对话与文本整理", compact,
                    Modifier.weight(1f).fillMaxWidth()) { onNavigate(Destination.AI) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCapture = true }, modifier = Modifier.weight(1f).testTag("capture")) {
                    OneLine("快速记录")
                }
                OutlinedButton(onClick = { onNavigate(Destination.MORE) }, modifier = Modifier.weight(1f)) {
                    OneLine("更多工具")
                }
            }
        }
    }
    if (showCapture) RecordsDialog { showCapture = false }
}

@Composable
private fun HomeTile(title: String, value: String, description: String, compact: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(if (compact) 10.dp else 16.dp)) {
            val showValue = maxHeight >= 44.dp * LocalDensity.current.fontScale
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showValue) {
                    if (!compact) Spacer(Modifier.height(4.dp))
                    Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!compact) Text(description, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
