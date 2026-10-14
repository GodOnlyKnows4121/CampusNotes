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
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9CD9C4))
        else lightColorScheme(primary = Color(0xFF256D57), secondary = Color(0xFF52685E), background = Color(0xFFF7F9F6))
    MaterialTheme(colorScheme = colors) {
      CompositionLocalProvider(LocalCampus provides vm, LocalContent provides data) {
        var selected by rememberSaveable { mutableStateOf(Destination.HOME.name) }
        val current = Destination.valueOf(selected)
        val stateHolder = rememberSaveableStateHolder()
        BackHandler(enabled = current != Destination.HOME) { selected = Destination.HOME.name }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { TopAppBar(title = {
                Text(if (current == Destination.HOME) "校园记录" else current.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }, actions = { Text(if (busy) "处理中…" else "本地优先", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 16.dp)) }) },
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("nav_${destination.name}"),
                            selected = current == destination,
                            onClick = { selected = destination.name },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label, maxLines = 1) }
                        )
                    }
                }
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
      }
    }
}
