package cn.campus.notes
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.campus.notes.data.KeyCipher
import cn.campus.notes.data.ProviderKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreDeviceTest {
    @Test fun encryptedKeysSurviveCipherRecreationAndAreBoundToProvider() {
        val encrypted = KeyCipher().encrypt("test-only-secret", ProviderKind.DEEPSEEK)
        assertFalse(encrypted.contains("test-only-secret"))
        assertEquals("test-only-secret", KeyCipher().decrypt(encrypted,ProviderKind.DEEPSEEK))
        try { KeyCipher().decrypt(encrypted,ProviderKind.ASR); fail("Must reject wrong provider") }
        catch (_: java.security.GeneralSecurityException) { }
    }
}
