package cn.campus.notes.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.campus.notes.CampusApplication
import cn.campus.notes.data.*
import cn.campus.notes.integration.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CampusViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as CampusApplication).repository
    val settingsStore = (application as CampusApplication).settings
    val data = MutableStateFlow(LocalData())
    val settings = MutableStateFlow(LocalSettings())
    val ready = MutableStateFlow(false)
    val settingsReady = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val storageBytes = MutableStateFlow(0L)
    val chat = repository.db.dao().observeChat().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tasks = repository.db.dao().observeTasks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val aiReady = MutableStateFlow(false)
    val aiRunning = MutableStateFlow<String?>(null)
    val liveReply = MutableStateFlow("")
    private var aiJob: Job? = null
    private val chatService = ChatService(repository.db, { settingsStore.credentials(ProviderKind.DEEPSEEK) })
    private val organizeService = OrganizeService(repository, { settingsStore.credentials(ProviderKind.DEEPSEEK) })
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.db.dao().interruptChat(); repository.db.dao().interruptPlans(); aiReady.value = true
            } catch (_: Exception) { error.value = "对话与任务记录读取失败，请重新启动应用" }
        }
        viewModelScope.launch {
            try { repository.data.collect { data.value = it; ready.value = true } }
            catch (e: Exception) { if (e is CancellationException) throw e; error.value = "本地数据库读取失败，请重新启动应用" }
        }
        viewModelScope.launch {
            try { settingsStore.data.collect { settings.value = it; settingsReady.value = true } }
            catch (e: Exception) { if (e is CancellationException) throw e; error.value = "设置读取失败，请检查本地存储后重新启动" }
        }
    }
    private fun request(label: String, block: suspend () -> Unit) {
        if (!aiReady.value || aiRunning.value != null) return
        aiRunning.value = label
        aiJob = viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                error.value = ChatService.safeMessage(e)
            } finally { liveReply.value = ""; aiRunning.value = null }
        }
    }
    fun sendChat(question: String, accepted: () -> Unit = {}) = request("对话") {
        chatService.send(question, { liveReply.value = it }, { withContext(Dispatchers.Main) { accepted() } })
    }
    fun cancelAi() { aiJob?.cancel() }
    fun clearChat() = request("清空对话") { repository.db.dao().clearChat() }
    fun planOrganize(topicId: String) = request("生成整理计划") {
        val task = organizeService.plan(topicId)
        if (settingsStore.data.first().sandboxEnabled) executeTask(task.id, confirmed = false)
    }
    fun confirmOrganize(taskId: String) = request("执行整理") { executeTask(taskId, confirmed = true) }
    private suspend fun executeTask(taskId: String, confirmed: Boolean) {
        try {
            repository.executeOrganize(taskId, settingsStore.data.first().sandboxEnabled, confirmed)
            storageBytes.value = repository.storageBytes()
            message.value = "整理成果已保存到资料库，原件保留"
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val current = repository.db.dao().task(taskId)
                if (current?.status == "preview" && e !is CancellationException)
                    repository.db.dao().save(current.copy(status = "error", detail = ChatService.safeMessage(e)))
            }
            throw e
        }
    }
    fun act(success: String? = null, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block(); storageBytes.value = repository.storageBytes() }
                message.value = success; onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error.value = when (e) {
                    is IllegalArgumentException, is IllegalStateException -> e.message ?: "操作失败，请重试"
                    is SecurityException -> "无法读取文件或保护密钥，请重新授权；本次操作未完成"
                    else -> "操作失败，请检查文件是否可用及存储空间后重试"
                }
            } finally { busy.value = false }
        }
    }
}
