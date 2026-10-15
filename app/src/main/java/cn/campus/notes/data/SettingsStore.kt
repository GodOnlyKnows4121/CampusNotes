package cn.campus.notes.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
enum class ProviderKind(val label: String) { DEEPSEEK("DeepSeek"), ASR("语音识别 ASR"), OCR("文字识别 OCR") }
data class ProviderConfig(val endpoint: String = "", val model: String = "", val hasKey: Boolean = false)
data class ChatModelProfile(val id: String, val title: String, val endpoint: String, val model: String, val hasKey: Boolean)
data class LocalSettings(val providers: Map<ProviderKind,ProviderConfig> = emptyMap(), val sandboxEnabled: Boolean = false,
    val activeSemester: String? = null, val groupModes: Map<String,String> = emptyMap(),
    val chatProfiles: List<ChatModelProfile> = emptyList(), val activeChatSession: String = LEGACY_CHAT)

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
    fun encrypt(value: String, provider: ProviderKind): String = encryptBound(value,provider.name)
    fun decrypt(value: String, provider: ProviderKind): String = decryptBound(value,provider.name)
    fun encryptProfile(value: String, id: String): String = encryptBound(value,"CHAT_PROFILE:$id")
    fun decryptProfile(value: String, id: String): String = decryptBound(value,"CHAT_PROFILE:$id")
    private fun encryptBound(value: String, scope: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    private fun decryptBound(value: String, scope: String): String {
        val parts = value.split(':'); require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
}

class SettingsStore(context: Context, private val cipher: KeyCipher = KeyCipher()) {
    private val store = context.settingsDataStore
    private fun field(kind: ProviderKind, name: String) = stringPreferencesKey("${kind.name}.$name")
    private val sandbox = booleanPreferencesKey("sandboxEnabled")
    private val semester = stringPreferencesKey("activeSemester")
    private val modes = stringPreferencesKey("groupModes")
    private val profiles = stringPreferencesKey("chatProfiles")
    private val activeChat = stringPreferencesKey("activeChatSession")
    private fun profileKey(id: String) = stringPreferencesKey("CHAT_PROFILE.$id.key")
    val data = store.data.map { p -> LocalSettings(ProviderKind.entries.associateWith { k ->
        ProviderConfig(p[field(k,"endpoint")] ?: "", p[field(k,"model")] ?: "", !p[field(k,"key")].isNullOrBlank())
    }, p[sandbox] ?: false, p[semester], try { val json=org.json.JSONObject(p[modes]?:"{}"); json.keys().asSequence().associateWith { json.getString(it) } } catch (_: Exception) { emptyMap() },
        listOf(ChatModelProfile(LEGACY_CHAT,"默认 DeepSeek",p[field(ProviderKind.DEEPSEEK,"endpoint")]?:"",p[field(ProviderKind.DEEPSEEK,"model")]?:"",!p[field(ProviderKind.DEEPSEEK,"key")].isNullOrBlank())) +
            try { val list=org.json.JSONArray(p[profiles]?:"[]"); (0 until list.length()).map { i -> val j=list.getJSONObject(i);val id=j.getString("id");ChatModelProfile(id,j.getString("title"),j.getString("endpoint"),j.getString("model"),!p[profileKey(id)].isNullOrBlank()) } } catch(_:Exception){emptyList()},
        p[activeChat]?:LEGACY_CHAT) }
    suspend fun activeChatSession(id: String) { store.edit { it[activeChat]=id } }
    suspend fun saveChatProfile(id: String, title: String, endpoint: String, model: String, newKey: String, removeKey: Boolean) {
        require(Regex("[a-f0-9-]{36}").matches(id)) { "无效的模型配置引用" }
        require(title.isNotBlank()&&title.length<=60&&endpoint.isNotBlank()&&endpoint.length<=2048&&model.isNotBlank()&&model.length<=200) { "请填写名称、HTTPS 地址及实际模型名" }
        validateEndpoint(endpoint);require(newKey.length<=8192){"密钥长度异常"}
        val encrypted=if(newKey.isNotBlank()&&!removeKey)cipher.encryptProfile(newKey.trim(),id)else null
        store.edit { p ->
            val list=org.json.JSONArray(p[profiles]?:"[]");val updated=org.json.JSONArray()
            for(i in 0 until list.length())if(list.getJSONObject(i).getString("id")!=id)updated.put(list.getJSONObject(i))
            updated.put(org.json.JSONObject().put("id",id).put("title",title.trim()).put("endpoint",endpoint.trim()).put("model",model.trim()))
            p[profiles]=updated.toString()
            if(removeKey)p.remove(profileKey(id))else if(encrypted!=null)p[profileKey(id)]=encrypted
        }
    }
    suspend fun deleteChatProfile(id: String) {
        require(id!=LEGACY_CHAT&&Regex("[a-f0-9-]{36}").matches(id)){"默认服务不能从这里移除"}
        store.edit { p->val list=org.json.JSONArray(p[profiles]?:"[]");val kept=org.json.JSONArray()
            for(i in 0 until list.length())if(list.getJSONObject(i).getString("id")!=id)kept.put(list.getJSONObject(i))
            p[profiles]=kept.toString();p.remove(profileKey(id))
        }
    }
    suspend fun chatCredentials(id: String): ProviderCredentials {
        if(id==LEGACY_CHAT)return credentials(ProviderKind.DEEPSEEK)
        val p=store.data.first();val list=org.json.JSONArray(p[profiles]?:"[]")
        val item=(0 until list.length()).map{list.getJSONObject(it)}.find{it.getString("id")==id}?:error("该模型配置已移除，请重新选择")
        val encrypted=p[profileKey(id)]
        require(!encrypted.isNullOrBlank()){ "请先保存所选模型的 API 密钥" }
        val key=try{cipher.decryptProfile(encrypted,id)}catch(_:Exception){throw IllegalStateException("所选模型密钥无法解密，请重新保存")}
        return ProviderCredentials(item.getString("endpoint"),item.getString("model"),key)
    }
    suspend fun activeSemester(id: String?) { store.edit { if(id==null) it.remove(semester) else it[semester]=id } }
    suspend fun groupMode(id: String, mode: String) { require(mode in listOf("课堂模式","项目模式","自定义模式")); store.edit { val json=org.json.JSONObject(it[modes]?:"{}"); json.put(id,mode); it[modes]=json.toString() } }
    suspend fun save(kind: ProviderKind, endpoint: String, model: String, newKey: String, removeKey: Boolean) {
        require(endpoint.length <= 2048 && model.length <= 200) { "地址或模型名称过长" }
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
    // Decrypt only for an explicit request, never expose plaintext through observable settings.
    suspend fun credentials(kind: ProviderKind): ProviderCredentials {
        val p = store.data.first()
        val config = ProviderConfig(p[field(kind,"endpoint")] ?: "", p[field(kind,"model")] ?: "")
        require(config.endpoint.isNotBlank() && config.model.isNotBlank()) { "请在更多 → 模型配置填写 ${kind.label} 地址和模型" }
        val encrypted = p[field(kind,"key")]
        require(!encrypted.isNullOrBlank()) { "请在更多 → 模型配置保存 ${kind.label} 密钥" }
        val secret = try { cipher.decrypt(encrypted, kind) } catch (_: Exception) {
            throw IllegalStateException("${kind.label} 密钥无法解密，请重新保存")
        }
        return ProviderCredentials(config.endpoint, config.model, secret)
    }
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

/** Not a data class: toString must never include key material. */
class ProviderCredentials(val endpoint: String, val model: String, val key: String) {
    override fun toString() = "ProviderCredentials(redacted)"
}
