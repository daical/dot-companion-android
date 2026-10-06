package dev.dotcompanion.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.dotcompanion.shared.CheckedStateFileOperations
import dev.dotcompanion.shared.StateFileOperations
import dev.dotcompanion.identity.AccountIdentity
import dev.dotcompanion.identity.OAuthTokens
import dev.dotcompanion.identity.SignInSession
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal class SavedChatGptAccount(val identity: AccountIdentity, val session: SignInSession?) {
    override fun toString() = "SavedChatGptAccount(sessionPresent=${session != null})"
}

internal class ChatGptAccountsRecord(
    val activeClientId: String? = null,
    val accounts: List<SavedChatGptAccount> = emptyList(),
    val pendingClientId: String? = null,
) {
    override fun toString() = "ChatGptAccountsRecord(accountCount=${accounts.size})"
}

internal interface AccountCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

private class AndroidAccountCipher : AccountCipher {
    private val alias = "dot-companion-chatgpt-account-v1"
    private val aad = "dev.dotcompanion.app/chatgpt-account-v1".toByteArray(Charsets.UTF_8)
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Account encryption key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }
    override fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > 29 && encrypted[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted.copyOfRange(13, encrypted.size))
    }
}

internal interface ChatGptAccountStorage {
    fun hostId(): String
    fun read(): ChatGptAccountsRecord
    fun write(record: ChatGptAccountsRecord)
}

internal class AccountCommitUnconfirmed : Exception("The protected account change could not be confirmed.")

internal class EncryptedChatGptAccountStore(
    private val context: Context,
    private val cipher: AccountCipher = AndroidAccountCipher(),
    private val operations: StateFileOperations = CheckedStateFileOperations,
) : ChatGptAccountStorage {
    private val file = File(context.filesDir, "chatgpt-accounts-v1.enc")
    private val temporary = File(file.path + ".new")
    private val backup = File(file.path + ".bak")
    private var recoveryRequired = false
    override fun hostId(): String {
        val preferences = context.getSharedPreferences("chatgpt-host-v1", Context.MODE_PRIVATE)
        val retained = preferences.getString("host_id", null)
        if (retained != null) {
            require(retained.matches(Regex("urn:uuid:[0-9a-fA-F-]{36}")))
            UUID.fromString(retained.removePrefix("urn:uuid:"))
            return retained
        }
        return "urn:uuid:${UUID.randomUUID()}".also {
            check(preferences.edit().putString("host_id", it).commit()) { "Host identity could not be saved." }
        }
    }
    override fun read(): ChatGptAccountsRecord {
        try {
            val present = listOf(file, temporary, backup).filter(operations::exists)
            if (present.isEmpty()) return ChatGptAccountsRecord()
            present.forEach(operations::requireRegular)
            check(!operations.exists(backup) && operations.exists(file)) { "Account recovery is required." }
            val encrypted = readBounded(file)
            val plain = cipher.decrypt(encrypted)
            val record = try { decode(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
            operations.syncDirectory(context.filesDir)
            if (operations.exists(temporary)) {
                operations.delete(temporary)
                operations.syncDirectory(context.filesDir)
            }
            return record
        } catch (error: Exception) {
            recoveryRequired = true
            throw error
        }
    }
    private fun readBounded(source: File): ByteArray = source.inputStream().use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(result.size() + count <= MAX_BYTES)
                result.write(buffer, 0, count)
            }
            result.toByteArray()
    }
    override fun write(record: ChatGptAccountsRecord) {
        check(!recoveryRequired) { "Account recovery is required before another write." }
        validate(record)
        val plain = encode(record).toString().toByteArray(Charsets.UTF_8)
        val encrypted = try {
            require(plain.size <= MAX_BYTES - 64)
            cipher.encrypt(plain)
        } finally { plain.fill(0) }
        try {
            listOf(file, temporary, backup).filter(operations::exists).forEach(operations::requireRegular)
            check(!operations.exists(backup)) { "Account recovery is required." }
            if (operations.exists(temporary)) {
                check(operations.exists(file)) { "Uncommitted account storage was preserved." }
                operations.delete(temporary)
                operations.syncDirectory(context.filesDir)
            }
            FileOutputStream(temporary).use { stream -> stream.write(encrypted); operations.syncFile(stream) }
            operations.rename(temporary, file)
            operations.requireRegular(file)
            check(readBounded(file).contentEquals(encrypted)) { "The protected account bytes did not match." }
            operations.syncDirectory(context.filesDir)
        } catch (_: Exception) {
            // Rename may have succeeded. Never overwrite uncertain disk state from stale memory.
            recoveryRequired = true
            throw AccountCommitUnconfirmed()
        }
    }

    private fun identityJson(identity: AccountIdentity) = JSONObject().apply {
        put("issuer", identity.issuer); put("subject", identity.subject); put("clientId", identity.clientId)
        put("email", identity.email ?: JSONObject.NULL); put("displayName", identity.displayName ?: JSONObject.NULL)
        put("expiresAtEpochSeconds", identity.expiresAtEpochSeconds)
    }
    private fun encode(record: ChatGptAccountsRecord) = JSONObject().apply {
        put("version", 1); put("activeClientId", record.activeClientId ?: JSONObject.NULL)
        put("pendingClientId", record.pendingClientId ?: JSONObject.NULL)
        put("accounts", JSONArray().apply { record.accounts.forEach { account ->
            put(identityJson(account.identity).apply {
                put("tokens", account.session?.tokens?.let { tokens -> JSONObject().apply {
                    put("accessToken", tokens.accessToken ?: JSONObject.NULL)
                    put("idToken", tokens.idToken); put("refreshToken", tokens.refreshToken ?: JSONObject.NULL)
                    put("tokenType", tokens.tokenType ?: JSONObject.NULL)
                    put("grantedScopes", JSONArray(tokens.grantedScopes.sorted()))
                    put("expiresAtEpochSeconds", tokens.expiresAtEpochSeconds)
                } } ?: JSONObject.NULL)
            })
        } })
    }
    private fun decode(json: JSONObject): ChatGptAccountsRecord {
        require(json.getInt("version") == 1)
        val array = json.getJSONArray("accounts")
        require(array.length() <= MAX_ACCOUNTS)
        val accounts = (0 until array.length()).map { index ->
            val account = array.getJSONObject(index)
            val identity = AccountIdentity(account.getString("issuer"), account.getString("subject"),
                account.getString("clientId"), account.optionalString("email"), account.optionalString("displayName"),
                account.getLong("expiresAtEpochSeconds"))
            val tokensJson = account.optJSONObject("tokens")
            val tokens = tokensJson?.let {
                val scopes = it.getJSONArray("grantedScopes")
                require(scopes.length() <= 32)
                OAuthTokens(it.optionalString("accessToken"), it.getString("idToken"), it.optionalString("refreshToken"),
                    (0 until scopes.length()).map { scope -> scopes.getString(scope) }.toSet(),
                    it.getLong("expiresAtEpochSeconds"), it.optionalString("tokenType"))
            }
            SavedChatGptAccount(identity, tokens?.let { SignInSession(identity, it) })
        }
        return ChatGptAccountsRecord(json.optionalString("activeClientId"), accounts, json.optionalString("pendingClientId")).also(::validate)
    }
    private fun validate(record: ChatGptAccountsRecord) {
        require(record.accounts.size <= MAX_ACCOUNTS)
        require(record.accounts.map { it.identity.clientId }.distinct().size == record.accounts.size)
        require(record.activeClientId == null || record.accounts.any { it.identity.clientId == record.activeClientId })
        record.accounts.forEach {
            require(it.identity.issuer == "https://auth.openai.com" && it.identity.subject.isNotBlank())
            require(it.identity.clientId.isNotBlank() && it.identity.clientId != "dynamic_agent_client")
            require(it.session == null || it.session.identity == it.identity)
        }
        require(record.pendingClientId == null || (record.pendingClientId.isNotBlank() && record.pendingClientId != "dynamic_agent_client"))
    }
    private fun JSONObject.optionalString(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)
    companion object { private const val MAX_BYTES = 512 * 1024; const val MAX_ACCOUNTS = 10 }
}
