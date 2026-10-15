package cn.campus.notes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun rememberCaptureActions(): (String) -> Unit {
    val vm = LocalCampus.current; val context = LocalContext.current
    var recording by rememberSaveable { mutableStateOf(false) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val name = cameraFile; cameraFile = null
        if (name != null) {
            val file = File(vm.repository.captures, name)
            if (ok) vm.act("照片原件已保存到待整理收件箱，尚未识别") {
                vm.repository.saveCapture(name, "照片 ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm"))}.jpg", "image/jpeg")
            } else if (file.length() == 0L) file.delete() else vm.message.value = "拍照未确认，临时文件保留，可在采集恢复中检查"
        }
    }
    if (recording) RecordingDialog { recording = false }
    return { action -> if (action == "录音") recording = true else {
        val name = "${newId()}.jpg"; val file = File(vm.repository.captures, name)
        try {
            check(file.createNewFile()); cameraFile = name
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.documents", file))
        } catch (_: Exception) { if(file.length() == 0L) file.delete(); cameraFile = null; vm.error.value = "无法打开系统相机，请检查是否安装可用相机应用" }
    } }
}

@Composable
fun RecordingDialog(onAttach:((String)->Unit)?=null,onDismiss: () -> Unit) {
    val vm = LocalCampus.current; val context = LocalContext.current
    val state by RecordingService.state.collectAsState()
    var recovery by rememberSaveable { mutableStateOf(false) }
    var record by rememberSaveable { mutableStateOf<String?>(null) }
    fun start() { try {
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(RecordingService.START))
    } catch (_: Exception) { vm.error.value = "无法启动录音；请保持应用前台并检查麦克风权限" } }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                vm.message.value = "通知权限未开启；录音状态可在应用中查看，也可在系统前台任务管理中停止"
        } else vm.error.value = "未取得麦克风权限，尚未开始录音"
    }
    fun command(action: String) { try { context.startService(Intent(context, RecordingService::class.java).setAction(action)) }
        catch (_: Exception) { vm.error.value = "录音控制失败，请通过常驻录音通知重试" } }
    EditorFrame("课堂录音", onDismiss, footer = { TextButton(onClick = onDismiss) { Text("返回（录音可后台继续）") } }) {
        Text(when(state.status) { "starting" -> "正在准备麦克风"; "recording" -> "正在录音"; "paused" -> "已暂停"; "saving" -> "正在保存原件"; "complete" -> "已保存"; "failed" -> "录音未完成"; "interrupted" -> "录音已中断"; else -> "尚未开始" }, modifier = Modifier.testTag("recording_status"))
        Text("%02d:%02d:%02d".format(state.seconds / 3600, state.seconds / 60 % 60, state.seconds % 60), style = MaterialTheme.typography.headlineMedium)
        Text("仅在点击开始后使用麦克风；原件本机保存，不自动上传。单次最多 6 小时或 512 MB。异常退出的临时文件会保留供检查。")
        if (state.detail.isNotBlank()) Text(state.detail)
        when(state.status) {
            "starting", "saving" -> CircularProgressIndicator()
            "recording", "paused" -> {
                OutlinedButton(onClick = { command(if(state.status == "paused") RecordingService.RESUME else RecordingService.PAUSE) }, modifier = Modifier.testTag("recording_pause")) { Text(if(state.status == "paused") "继续录音" else "暂停录音") }
                Button(onClick = { command(RecordingService.FINISH) }, modifier = Modifier.testTag("recording_finish")) { Text("结束并保存") }
            }
            else -> Button(onClick = {
                val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
                if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) start()
                else permissions.launch(needed.toTypedArray())
            }, modifier = Modifier.testTag("recording_start")) { Text("开始录音") }
        }
        state.recordId?.takeIf { state.status == "complete" }?.let { id -> TextButton(onClick = { record = id }) { Text("查看已保存原件 / 主动转写") } }
        if(onAttach!=null)state.recordId?.takeIf{state.status=="complete"}?.let{id->TextButton(onClick={onAttach(id);onDismiss()}){Text("将该录音添加到本次对话")}}
        TextButton(onClick = { recovery = true }) { Text("采集恢复") }
    }
    if (recovery) CaptureRecoveryDialog { recovery = false }
    record?.let { LocalRecordDialog(it) { record = null } }
}

@Composable
fun CaptureRecoveryDialog(onDismiss: () -> Unit) {
    val vm = LocalCampus.current; val state by RecordingService.state.collectAsState()
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(refresh, state.status) {
        entries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            vm.repository.captures.listFiles().orEmpty().filter { it.isFile && Regex("[a-f0-9-]{36}\\.(m4a|jpg)").matches(it.name) }
                .filterNot { it.name == state.file && state.status in setOf("starting", "recording", "paused", "saving") }.sortedByDescending { it.lastModified() }
        }
    }
    EditorFrame("采集恢复", onDismiss, footer = { TextButton(onClick = onDismiss) { Text("返回") } }) {
        Text("以下为尚未归入资料库的本机采集文件。中断录音可能不完整或无法播放；保存原件不表示内容完整，也不会自动识别。")
        if (entries.isEmpty()) Text("没有待恢复采集文件")
        entries.forEach { file -> OutlinedCard { Column(Modifier.padding(12.dp)) {
            Text("${if(file.extension == "jpg") "照片" else "中断录音"} · ${file.length()} 字节")
            TextButton(enabled = file.length() > 0, onClick = { vm.act("采集原件已保存到待整理收件箱", { refresh++ }) {
                vm.repository.saveCapture(file.name, "恢复采集 ${file.lastModified()}.${file.extension}", if(file.extension == "jpg") "image/jpeg" else "audio/mp4")
            } }) { Text("保存原件") }
            TextButton(onClick = { deleting = file }) { Text("丢弃临时文件") }
        } } }
    }
    deleting?.let { file -> DeleteConfirmation("临时采集", "此文件尚未归入资料库，丢弃后无法恢复。", { deleting = null }) {
        vm.act("临时文件已丢弃", { deleting = null; refresh++ }) { check(file.delete()) { "未能删除临时文件" } }
    } }
}

@Composable
fun ContentProcessingPanel(file: LibraryNode, onReview: (String) -> Unit) {
    if (file.kind != NodeKind.ORIGINAL || file.localName == null) return
    val vm = LocalCampus.current; val results by vm.contentResults.collectAsState(); val settings by vm.settings.collectAsState()
    val own = results.filter { it.sourceId == file.id }
    val running = own.any { it.status in setOf("waiting", "running") }
    var upload by rememberSaveable { mutableStateOf<String?>(null) }
    val mime = processingMime(file)
    HorizontalDivider()
    Text("文字处理", style = MaterialTheme.typography.titleMedium)
    if (mime.startsWith("audio/")) TextButton(enabled = !running, onClick = { upload = "asr" }) { Text("主动语音转写 ASR") }
    else if (mime.startsWith("image/")) TextButton(enabled = !running, onClick = { upload = "ocr" }) { Text("主动图片识别 OCR") }
    else TextButton(enabled = !running, onClick = { vm.contentProcessor.start(file.id, "parse") }, modifier = Modifier.testTag("extract_text")) { Text("本机提取文字（不联网）") }
    Text("原件保留。DOCX/PPTX/PDF/TXT/Markdown 可尝试本机提取；旧版 DOC/PPT 仍可保存与外部打开。结果核对确认后才用于搜索与整理。", style = MaterialTheme.typography.bodySmall)
    (own.take(6)+listOfNotNull(own.firstOrNull{it.status=="confirmed"})).distinctBy{it.id}.forEach { result ->
        val label = mapOf("waiting" to "等待", "running" to "处理中", "review" to "待核对", "confirmed" to "已确认", "failed" to "失败", "cancelled" to "已取消", "interrupted" to "已中断")[result.status] ?: result.status
        Text("${mapOf("parse" to "本机解析", "asr" to "语音转写", "ocr" to "图片识别")[result.kind]} · $label", modifier = Modifier.testTag("content_status_${result.status}"))
        if (result.detail.isNotBlank()) Text(result.detail, style = MaterialTheme.typography.bodySmall)
        if(result.diagnostics.isNotBlank()){
            val diagnostic=runCatching{org.json.JSONObject(result.diagnostics)}.getOrNull()
            val outcome=when(diagnostic?.optString("outcome")){"complete"->"完成";"transport_failure"->"传输失败";"cancelled"->"已取消";"rejected"->"响应未通过校验";"configuration_error"->"配置错误";else->"处理中"}
            if(diagnostic!=null)Text("请求诊断：${diagnostic.optString("phase")} · ${diagnostic.optLong("elapsedMs")}毫秒 · $outcome"+
                if(diagnostic.isNull("httpStatus"))"" else " · HTTP ${diagnostic.optInt("httpStatus")}",style=MaterialTheme.typography.bodySmall)
        }
        if (result.status in setOf("waiting", "running")) TextButton(onClick = { vm.contentProcessor.cancel(file.id) }) { Text("取消处理") }
        if (result.text.isNotBlank()) TextButton(onClick = { onReview(result.id) }) { Text(if(result.status in setOf("review","confirmed")) "查看 / 核对文字" else "查看部分文字（未完成）") }
    }
    upload?.let { kind ->
        val config = settings.providers[if(kind == "asr") ProviderKind.ASR else ProviderKind.OCR] ?: ProviderConfig()
        // Freeze the visible configuration for this confirmation; changes require another preview.
        val snapshot = remember(kind) { config }
        val problem=RecognitionConfiguration.problem(if(kind=="asr")ProviderKind.ASR else ProviderKind.OCR,snapshot)
        AlertDialog(onDismissRequest = { upload = null }, title = { Text("确认上传原件进行${if(kind == "asr") "语音转写" else "图片识别"}") }, text = { Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(file.title); Text("${file.bytes} 字节 · ${if(kind=="asr")"AAC/M4A 长录音按最多 3 分钟、5 MB 分段连续转写，可能产生多次请求；其他音频限 3 分钟、7 MB" else "图片上限 7 MB"}")
            Text("服务：${snapshot.endpoint.ifBlank { "未配置" }}\n模型：${snapshot.model.ifBlank { "未配置" }}")
            if(problem!=null)Text(problem,color=MaterialTheme.colorScheme.error)
            else Text("实际请求：POST ${DeepSeekProvider.completionUrl(snapshot.endpoint)}")
            Text("将仅发送这份原件到所示服务，可能产生费用。采用兼容 Chat Completions 的 input_audio / image_url 协议；模型名称不等于能力已验证。不会使用 DeepSeek 密钥替代识别配置。")
            if(kind=="ocr")Text("非流式图片识别需等待完整结果，单次最多约5.5分钟；可取消，不自动重试。")
        } }, confirmButton = { TextButton(enabled = problem==null && snapshot.hasKey && file.bytes in 1..(if(kind=="asr")512_000_000L else RecognitionProvider.MAX_INPUT.toLong()), onClick = {
            upload = null; vm.contentProcessor.start(file.id, kind, snapshot)
        }) { Text("发送并处理") } }, dismissButton = { TextButton(onClick = { upload = null }) { Text("取消") } })
    }
}

@Composable
fun ContentReviewDialog(result: ContentResult, onDismiss: () -> Unit) {
    val vm = LocalCampus.current
    var text by rememberSaveable(result.id) { mutableStateOf(result.text) }
    val busy by vm.busy.collectAsState()
    val complete = result.status in setOf("review", "confirmed")
    EditorFrame("核对识别 / 解析文字", onDismiss, footer = { TextButton(onClick = onDismiss,modifier=Modifier.testTag("content_review_back")) { Text("返回") }; Button(enabled = !busy && complete, onClick = {
        vm.act("文字已确认，原件保留", onDismiss) { vm.contentProcessor.confirm(result.id, text) }
    }, modifier = Modifier.testTag("confirm_content")) { Text("确认文字") } }) {
        Text("请对照原件检查遗漏、公式和错字。未确认的文字不会进入搜索或整理；关闭此页不会确认。")
        if(!complete)Text("任务未完整完成，以下为部分内容，不能确认或作为已理解资料使用。请主动重试。")
        OutlinedTextField(text, { if(it.length <= DocumentParser.MAX_TEXT) text = it }, readOnly = !complete, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 380.dp).testTag("content_text"), label = { Text("识别 / 解析结果") })
        if (result.status == "confirmed") TextButton(enabled = !busy, onClick = { vm.act("已另存文字记录，仍关联来源原件") { vm.contentProcessor.saveAsNote(result.id) } }) { Text("将已确认版本另存文字记录") }
    }
}
