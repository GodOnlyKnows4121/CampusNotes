package cn.campus.notes.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
enum class ProviderKind(val label: String) { DEEPSEEK("DeepSeek"), ASR("语音识别 ASR"), OCR("文字识别 OCR") }
data class ProviderConfig(val endpoint: String = "", val model: String = "", val hasKey: Boolean = false)
data class LocalSettings(val providers: Map<ProviderKind,ProviderConfig> = emptyMap(), val sandboxEnabled: Boolean = false)

/** Only authenticated ciphertext enters DataStore; key material stays in Android Keystore. */
class KeyCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("campus-api-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("campus-api-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun encrypt(value: String, provider: ProviderKind): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    fun decrypt(value: String, provider: ProviderKind): String {
        val parts = value.split(':'); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(provider.name.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}

class SettingsStore(context: Context, private val cipher: KeyCipher = KeyCipher()) {
    private val store = context.settingsDataStore
    private fun field(kind: ProviderKind, name: String) = stringPreferencesKey("${kind.name}.$name")
    private val sandbox = booleanPreferencesKey("sandboxEnabled")
    val data = store.data.map { p -> LocalSettings(ProviderKind.entries.associateWith { k ->
        ProviderConfig(p[field(k,"endpoint")] ?: "", p[field(k,"model")] ?: "", !p[field(k,"key")].isNullOrBlank())
    }, p[sandbox] ?: false) }
    suspend fun save(kind: ProviderKind, endpoint: String, model: String, newKey: String, removeKey: Boolean) {
        validateEndpoint(endpoint)
        require(newKey.length <= 8192) { "密钥长度异常" }
        val encrypted = if (newKey.isNotBlank() && !removeKey) cipher.encrypt(newKey.trim(), kind) else null
        store.edit { p ->
            p[field(kind,"endpoint")] = endpoint.trim()
            p[field(kind,"model")] = model.trim()
            if (removeKey) p.remove(field(kind,"key")) else if (encrypted != null) p[field(kind,"key")] = encrypted
        }
    }
    suspend fun sandbox(enabled: Boolean) { store.edit { it[sandbox] = enabled } }
    companion object {
        fun validateEndpoint(value: String) {
            if (value.isBlank()) return
            val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的 HTTPS 地址") }
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "地址须使用 HTTPS，且不能包含密码、查询参数或片段"
            }
        }
    }
}
