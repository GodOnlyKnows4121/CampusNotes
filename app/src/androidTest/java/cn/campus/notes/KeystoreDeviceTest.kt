package cn.campus.notes
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.campus.notes.data.KeyCipher
import cn.campus.notes.data.ProviderKind
import cn.campus.notes.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreDeviceTest {
    @Test fun profileCiphertextIsBoundToProfileAndCannotReplaceLegacyKeys() {
        val first=cn.campus.notes.data.newId();val second=cn.campus.notes.data.newId()
        val encrypted=KeyCipher().encryptProfile("profile-test-only",first)
        assertEquals("profile-test-only",KeyCipher().decryptProfile(encrypted,first))
        try{KeyCipher().decryptProfile(encrypted,second);fail()}catch(_:java.security.GeneralSecurityException){}
        try{KeyCipher().decrypt(encrypted,ProviderKind.DEEPSEEK);fail()}catch(_:java.security.GeneralSecurityException){}
    }
    @Test fun existingConfiguredKeysRemainDecryptableWithoutNetworkOrWrites() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(context)
        val configured = settings.data.first().providers.filterValues {
            it.hasKey && it.endpoint.isNotBlank() && it.model.isNotBlank()
        }
        for (kind in configured.keys) {
            assertTrue("Saved provider key must remain decryptable", settings.credentials(kind).key.isNotBlank())
        }
    }
    @Test fun encryptedKeysSurviveCipherRecreationAndAreBoundToProvider() {
        val encrypted = KeyCipher().encrypt("test-only-secret", ProviderKind.DEEPSEEK)
        assertFalse(encrypted.contains("test-only-secret"))
        assertEquals("test-only-secret", KeyCipher().decrypt(encrypted,ProviderKind.DEEPSEEK))
        try { KeyCipher().decrypt(encrypted,ProviderKind.ASR); fail("Must reject wrong provider") }
        catch (_: java.security.GeneralSecurityException) { }
    }
}
