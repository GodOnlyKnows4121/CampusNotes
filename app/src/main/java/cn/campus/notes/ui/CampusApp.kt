package cn.campus.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.campus.notes.data.LocalData

val LocalCampus = staticCompositionLocalOf<CampusViewModel> { error("Missing app state") }
val LocalContent = staticCompositionLocalOf { LocalData() }

enum class Destination(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Default.Home), SCHEDULE("日程", Icons.Default.DateRange),
    AI("AI", Icons.AutoMirrored.Filled.Send), LIBRARY("资料库", Icons.AutoMirrored.Filled.List), MORE("更多", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusApp() {
    val application = LocalContext.current.applicationContext as cn.campus.notes.CampusApplication
    val factory = remember(application) { androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(application) }
    val vm: CampusViewModel = viewModel(factory = factory)
    val data by vm.data.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val message by vm.message.collectAsState()
    val ready by vm.ready.collectAsState()
    val settingsReady by vm.settingsReady.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    val colors = darkColorScheme(primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF191919),
        secondary = Color(0xFFB0B0B0), background = Color(0xFF191919), surface = Color(0xFF191919),
        surfaceContainer = Color(0xFF242424), surfaceContainerLow = Color(0xFF121212),
        surfaceVariant = Color(0xFF262626), outline = Color(0xFF393939), onSurface = Color(0xFFF5F5F5))
    MaterialTheme(colorScheme = colors) {
      CompositionLocalProvider(LocalCampus provides vm, LocalContent provides data) {
        var selected by rememberSaveable { mutableStateOf(Destination.HOME.name) }
        val current = Destination.valueOf(selected)
        val stateHolder = rememberSaveableStateHolder()
        var alerts by rememberSaveable { mutableStateOf(false) }
        val compactHeader = LocalConfiguration.current.screenHeightDp < 500
        BackHandler(enabled = current != Destination.HOME) { selected = Destination.HOME.name }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { Column(Modifier.statusBarsPadding().padding(horizontal=20.dp,vertical=if(compactHeader) 4.dp else 10.dp)) {
                if(!compactHeader) Text("CAMPUS ASSISTANT",style=MaterialTheme.typography.labelSmall,color=colors.secondary)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text(current.label,style=if(compactHeader) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                    if(current==Destination.HOME) Row {
                        IconButton(onClick={selected=Destination.AI.name},modifier=Modifier.semantics{contentDescription="打开 AI 助手"}) { LineGlyph("助手") }
                        IconButton(onClick={alerts=true},modifier=Modifier.semantics{contentDescription="消息"}) { LineGlyph("消息") }
                    }
                    if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                }
            } },
            bottomBar = {
                Column { HorizontalDivider(color=colors.outline); NavigationBar(containerColor=colors.background,
                    tonalElevation=0.dp,modifier=Modifier.height(if(compactHeader) 62.dp else 76.dp)) {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("nav_${destination.name}"),
                            selected = current == destination,
                            colors = NavigationBarItemDefaults.colors(indicatorColor=Color.Transparent,
                                selectedIconColor=colors.onSurface,selectedTextColor=colors.onSurface,
                                unselectedIconColor=colors.secondary,unselectedTextColor=colors.secondary),
                            onClick = { selected = destination.name },
                            icon = { LineGlyph(destination.label) },
                            label = { Text(destination.label, maxLines = 1) }
                        )
                    }
                } }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
              if (!ready || !settingsReady) { Text("正在读取本地资料…", Modifier.padding(24.dp)) } else {
                stateHolder.SaveableStateProvider(selected) {
                    when (current) {
                        Destination.HOME -> HomeScreen { selected = it.name }
                        Destination.SCHEDULE -> ScheduleScreen()
                        Destination.AI -> AiScreen()
                        Destination.LIBRARY -> LibraryScreen()
                        Destination.MORE -> MoreScreen()
                    }
                }
              }
            }
        }
        error?.let { NotImplementedDialog("操作未完成", it) { vm.error.value = null } }
        if(alerts) { val tasks by vm.tasks.collectAsState(); AlertDialog(onDismissRequest={alerts=false},
            title={Text("消息")},text={Column { Text(if(tasks.isEmpty()) "暂无任务消息" else tasks.take(8).joinToString("\n") { "${it.topicTitle} · ${it.detail.ifBlank { it.status }}" });Text("课程和系统通知将在提醒阶段接入。",style=MaterialTheme.typography.bodySmall) }},
            confirmButton={TextButton(onClick={alerts=false}){Text("返回")}}) }
      }
    }
}
