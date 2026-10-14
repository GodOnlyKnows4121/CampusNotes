package cn.campus.notes.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.campus.notes.CampusApplication
import cn.campus.notes.data.*
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
    init {
        viewModelScope.launch {
            try { repository.data.collect { data.value = it; ready.value = true } }
            catch (e: Exception) { if (e is CancellationException) throw e; error.value = "本地数据库读取失败，请重新启动应用" }
        }
        viewModelScope.launch {
            try { settingsStore.data.collect { settings.value = it; settingsReady.value = true } }
            catch (e: Exception) { if (e is CancellationException) throw e; error.value = "设置读取失败，请检查本地存储后重新启动" }
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
