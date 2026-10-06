package dev.dotcompanion.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.dotcompanion.identity.AccountIdentity
import dev.dotcompanion.identity.OAuthTokens
import dev.dotcompanion.identity.SignInSession
import dev.dotcompanion.shared.CheckedStateFileOperations
import dev.dotcompanion.shared.StateFileOperations
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ChatGptAccountStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val cipher = object : AccountCipher {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun encrypt(plain: ByteArray): ByteArray {
            val operation = Cipher.getInstance("AES/GCM/NoPadding")
            operation.init(Cipher.ENCRYPT_MODE, key)
            return byteArrayOf(1) + operation.iv + operation.doFinal(plain)
        }
        override fun decrypt(encrypted: ByteArray): ByteArray {
            val operation = Cipher.getInstance("AES/GCM/NoPadding")
            operation.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
            return operation.doFinal(encrypted.copyOfRange(13, encrypted.size))
        }
    }
    private fun account(clientId: String) = AccountIdentity("https://auth.openai.com", "fixture-$clientId", clientId,
        "fixture@example.com", "Fixture account", 2000000000L)
    private fun session(identity: AccountIdentity) = SignInSession(identity,
        OAuthTokens("access-fixture", "id-fixture", null, setOf("openid", "profile", "email"), 2000000000L, "Bearer"))

    @Test fun encryptedReloadKeepsAccountsAndTokensBoundAndHostStable() {
        val store = EncryptedChatGptAccountStore(context, cipher)
        val first = account("oaiapp_first")
        val second = account("oaiapp_second")
        val host = store.hostId()
        store.write(ChatGptAccountsRecord(first.clientId,
            listOf(SavedChatGptAccount(first, session(first)), SavedChatGptAccount(second, null))))
        val raw = File(context.filesDir, "chatgpt-accounts-v1.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("access-fixture")); assertFalse(raw.contains("fixture@example.com"))
        val reopened = EncryptedChatGptAccountStore(context, cipher)
        assertEquals(host, reopened.hostId())
        val record = reopened.read()
        assertEquals(first.clientId, record.activeClientId)
        assertEquals(first, record.accounts[0].session!!.identity)
        assertEquals("access-fixture", record.accounts[0].session!!.tokens.accessToken)
        assertNull(record.accounts[1].session)
        assertFalse(record.toString().contains("access-fixture"))
    }
    @Test fun signedOutRegistrationSurvivesWithoutCredentials() {
        val store = EncryptedChatGptAccountStore(context, cipher)
        val identity = account("oaiapp_signedout")
        store.write(ChatGptAccountsRecord(identity.clientId, listOf(SavedChatGptAccount(identity, null))))
        val reopened = EncryptedChatGptAccountStore(context, cipher).read()
        assertEquals(identity, reopened.accounts.single().identity)
        assertNull(reopened.accounts.single().session)
    }
    @Test fun corruptedCredentialFileIsPreservedAndNeverActivatesAnAccount() {
        val store = EncryptedChatGptAccountStore(context, cipher)
        val identity = account("oaiapp_tamper")
        store.write(ChatGptAccountsRecord(identity.clientId, listOf(SavedChatGptAccount(identity, session(identity)))))
        val path = File(context.filesDir, "chatgpt-accounts-v1.enc")
        val corrupted = path.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        path.writeBytes(corrupted)
        try { store.read(); fail("Tampered credentials must be rejected") } catch (_: Exception) { }
        assertArrayEquals(corrupted, path.readBytes())
        val repository = ChatGptAccountRepository(store)
        assertFalse(repository.ui.value.signedIn)
        assertFalse(repository.ui.value.storageAvailable)
        assertTrue(repository.ui.value.issue!!.contains("preserved"))
    }
    @Test fun mismatchedSessionCannotOverwritePreviousEncryptedRecord() {
        val store = EncryptedChatGptAccountStore(context, cipher)
        val first = account("oaiapp_saved")
        val wrong = account("oaiapp_wrong")
        store.write(ChatGptAccountsRecord(first.clientId, listOf(SavedChatGptAccount(first, session(first)))))
        try {
            store.write(ChatGptAccountsRecord(first.clientId, listOf(SavedChatGptAccount(first, session(wrong)))))
            fail("A session from another registration must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(first.clientId, store.read().activeClientId)
        assertEquals(first, store.read().accounts.single().session!!.identity)
    }
    @Test fun failedSyncOrRenameDoesNotPublishCredentialRemovalOrPermitStaleOverwrite() {
        val identity = account("oaiapp_commit")
        for (boundary in listOf("sync", "rename", "directory")) {
            val normal = EncryptedChatGptAccountStore(context, cipher)
            normal.write(ChatGptAccountsRecord(identity.clientId, listOf(SavedChatGptAccount(identity, session(identity)))))
            var renamed = false
            val failing = object : StateFileOperations by CheckedStateFileOperations {
                override fun syncFile(stream: FileOutputStream) {
                    if (boundary == "sync") throw IOException("Injected sync failure")
                    CheckedStateFileOperations.syncFile(stream)
                }
                override fun rename(source: File, target: File) {
                    if (boundary == "rename") throw IOException("Injected rename failure")
                    CheckedStateFileOperations.rename(source, target)
                    renamed = true
                }
                override fun syncDirectory(directory: File) {
                    if (boundary == "directory" && renamed) throw IOException("Injected directory sync failure")
                    CheckedStateFileOperations.syncDirectory(directory)
                }
            }
            val candidate = EncryptedChatGptAccountStore(context, cipher, failing)
            candidate.read()
            val signedOut = ChatGptAccountsRecord(identity.clientId, listOf(SavedChatGptAccount(identity, null)))
            try { candidate.write(signedOut); fail("Unconfirmed commits must throw") } catch (_: AccountCommitUnconfirmed) { }
            try { candidate.write(signedOut); fail("Uncertain storage must block further writes") } catch (_: IllegalStateException) { }
            val recovered = EncryptedChatGptAccountStore(context, cipher).read()
            assertEquals(identity.clientId, recovered.activeClientId)
            if (boundary == "directory") assertNull(recovered.accounts.single().session)
            else assertNotNull(recovered.accounts.single().session)
        }
    }
}
