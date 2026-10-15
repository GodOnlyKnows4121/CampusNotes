package cn.campus.notes.integration

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.*
import androidx.core.app.NotificationCompat
import cn.campus.notes.CampusApplication
import cn.campus.notes.MainActivity
import cn.campus.notes.R
import cn.campus.notes.data.newId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class RecordingState(val status: String = "idle", val seconds: Long = 0, val file: String? = null,
    val recordId: String? = null, val detail: String = "")

/** Recording begins only from a visible Activity after runtime microphone permission. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var ticker: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var elapsed = 0L
    private var segmentStart = 0L
    private val repo get() = (application as CampusApplication).repository
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "课堂录音", NotificationManager.IMPORTANCE_LOW).apply { description = "录音进行中及暂停状态；通知可暂停、继续或结束保存" })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when(intent?.action) {
                START -> if (recorder == null && state.value.status != "saving") begin()
                PAUSE -> if (state.value.status == "recording") {
                    recorder?.pause(); elapsed += SystemClock.elapsedRealtime() - segmentStart
                    state.value = state.value.copy(status = "paused", seconds = elapsed / 1000); foreground()
                }
                RESUME -> if (state.value.status == "paused") {
                    recorder?.resume(); segmentStart = SystemClock.elapsedRealtime()
                    state.value = state.value.copy(status = "recording"); foreground()
                }
                FINISH -> if (recorder != null) finishRecording()
                else -> if (recorder == null) stopSelf()
            }
        } catch (_: Exception) { fail("录音未完成：请检查麦克风授权、其他录音应用和可用空间；临时内容会保留供恢复") }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun begin() {
        elapsed = 0; segmentStart = SystemClock.elapsedRealtime()
        val name = "${newId()}.m4a"
        state.value = RecordingState("starting", file = name)
        foreground()
        val output = File(repo.captures, name)
        val device = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        recorder = device
        device.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100); setAudioEncodingBitRate(64000); setAudioChannels(1)
            setMaxDuration(6 * 60 * 60 * 1000); setMaxFileSize(512_000_000)
            setOutputFile(output.absolutePath)
            setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) finishRecording() }
            setOnErrorListener { _, _, _ -> fail("录音设备或存储发生错误，已停止；临时内容保留供恢复") }
            prepare(); start()
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CampusNotes:Recording").apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L + 60_000)
        }
        segmentStart = SystemClock.elapsedRealtime()
        state.value = state.value.copy(status = "recording"); foreground()
        ticker = scope.launch {
            while (isActive) { delay(1000)
                if (state.value.status == "recording") state.value = state.value.copy(seconds = (elapsed + SystemClock.elapsedRealtime() - segmentStart) / 1000)
            }
        }
    }
    private fun finishRecording() {
        if (state.value.status == "saving" || recorder == null) return
        val name = state.value.file ?: return
        if (state.value.status == "recording") elapsed += SystemClock.elapsedRealtime() - segmentStart
        state.value = state.value.copy(status = "saving", seconds = elapsed / 1000)
        try { recorder?.stop() } catch (_: Exception) { fail("录音太短或结束失败，临时内容保留；可在采集恢复中检查"); return }
        release()
        scope.launch {
            try {
                val title = "课堂录音 ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm"))}.m4a"
                val id = withContext(Dispatchers.IO) { repo.saveCapture(name, title, "audio/mp4") }
                state.value = state.value.copy(status = "complete", recordId = id, detail = "录音原件已保存到待整理收件箱，尚未转写")
            } catch (_: Exception) { state.value = state.value.copy(status = "failed", detail = "录音保存未完成，临时文件保留；请在采集恢复中重试") }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    private fun release() {
        ticker?.cancel(); ticker = null
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        if (wake?.isHeld == true) wake?.release()
        wake = null
    }
    private fun fail(message: String) {
        try { recorder?.stop() } catch (_: Exception) { }
        release()
        state.value = state.value.copy(status = "failed", detail = message)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun foreground() {
        val paused = state.value.status == "paused"
        fun action(command: String) = PendingIntent.getService(this, command.hashCode(), Intent(this, RecordingService::class.java).setAction(command), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_record_notification)
            .setContentTitle(if(paused) "课堂录音已暂停" else "课堂录音进行中")
            .setContentText("原件保存在本机；结束后进入待整理收件箱")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if(paused) "继续" else "暂停", action(if(paused) RESUME else PAUSE))
            .addAction(0, "结束并保存", action(FINISH)).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
    }
    override fun onDestroy() {
        if (recorder != null) {
            try { recorder?.stop() } catch (_: Exception) { }
            state.value = state.value.copy(status = "interrupted", detail = "录音服务中断，临时内容保留；请检查采集恢复，不会自动重新录音")
        }
        release(); scope.cancel(); super.onDestroy()
    }
    companion object {
        const val START = "cn.campus.notes.record.START"
        const val PAUSE = "cn.campus.notes.record.PAUSE"
        const val RESUME = "cn.campus.notes.record.RESUME"
        const val FINISH = "cn.campus.notes.record.FINISH"
        private const val CHANNEL = "classroom-recording"
        private const val NOTIFICATION_ID = 1001
        val state = MutableStateFlow(RecordingState())
    }
}
