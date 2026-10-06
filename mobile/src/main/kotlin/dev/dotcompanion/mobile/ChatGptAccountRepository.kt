package dev.dotcompanion.mobile

import android.content.Context
import dev.dotcompanion.identity.AccountIdentity
import dev.dotcompanion.identity.LoopbackCallbackListener
import dev.dotcompanion.identity.OpenAiIdentityClient
import dev.dotcompanion.identity.OpenAiSignIn
import dev.dotcompanion.identity.SignInAttempt
import dev.dotcompanion.identity.SignInException
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ChatGptSignInPhase { IDLE, WAITING_FOR_BROWSER, VERIFYING, SIGNING_OUT }

data class ChatGptAccountUi(
    val activeAccount: AccountIdentity? = null,
    val savedAccounts: List<AccountIdentity> = emptyList(),
    val signedIn: Boolean = false,
    val phase: ChatGptSignInPhase = ChatGptSignInPhase.IDLE,
    val issue: String? = null,
    val storageAvailable: Boolean = true,
    val unfinishedRegistration: Boolean = false,
    val credentialsPresent: Boolean = false,
) {
    val busy get() = phase != ChatGptSignInPhase.IDLE
}

/** Owns account identity only. These credentials never enter the message bridge or Wear transport. */
class ChatGptAccountRepository internal constructor(
    private val storage: ChatGptAccountStorage,
    private val client: OpenAiIdentityClient = OpenAiIdentityClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    constructor(context: Context) : this(EncryptedChatGptAccountStore(context.applicationContext))
    private val lock = Any()
    private var records = ChatGptAccountsRecord()
    private var writable = true
    private var generation = 0L
    private var job: Job? = null
    private var listener: LoopbackCallbackListener? = null
    private var pendingAttempt: SignInAttempt? = null
    private val mutableUi = MutableStateFlow(ChatGptAccountUi())
    val ui = mutableUi.asStateFlow()

    init {
        try { records = storage.read(); publish() }
        catch (_: Exception) {
            writable = false
            publish(issue = "Saved sign-in could not be read. No account has been activated; the stored file was preserved.")
        }
    }

    fun refreshVisibleState() = synchronized(lock) { publish(issue = mutableUi.value.issue, phase = mutableUi.value.phase) }

    fun signIn(openBrowser: (URI) -> Unit, newAccount: Boolean = false) {
        val ticket = synchronized(lock) {
            if (!writable || mutableUi.value.busy) return
            if (newAccount && records.accounts.size >= EncryptedChatGptAccountStore.MAX_ACCOUNTS) {
                publish(issue = "This phone has reached its saved-account limit.")
                return
            }
            generation += 1
            publish(phase = ChatGptSignInPhase.WAITING_FOR_BROWSER)
            generation
        }
        job = scope.launch {
            var pending: LoopbackCallbackListener? = null
            try {
                val selected = synchronized(lock) {
                    if (ticket != generation) return@launch
                    if (newAccount) null else records.activeClientId ?: records.pendingClientId
                }
                val expectedSubject = synchronized(lock) {
                    records.accounts.find { it.identity.clientId == selected }?.identity?.subject
                }
                val callback = LoopbackCallbackListener()
                pending = callback
                synchronized(lock) {
                    if (ticket != generation) { callback.close(); return@launch }
                    listener = callback
                }
                val attempt = OpenAiSignIn.createAttempt(storage.hostId(), callback.redirectUri, selected, expectedSubject)
                synchronized(lock) {
                    if (ticket != generation) { attempt.cancel(); return@launch }
                    pendingAttempt = attempt
                }
                withContext(Dispatchers.Main) { openBrowser(OpenAiSignIn.authorizationUri(attempt)) }
                val authorization = callback.awaitCallback(attempt)
                synchronized(lock) {
                    if (ticket != generation) return@launch
                    // A state-validated registration may be reused after an expired-code error.
                    if (selected == null) commit(ChatGptAccountsRecord(records.activeClientId, records.accounts, authorization.clientId))
                    publish(phase = ChatGptSignInPhase.VERIFYING)
                }
                val verified = client.complete(attempt, authorization)
                synchronized(lock) {
                    if (ticket != generation) return@launch
                    val retained = records.accounts.filterNot { it.identity.clientId == verified.identity.clientId }
                    require(retained.size < EncryptedChatGptAccountStore.MAX_ACCOUNTS)
                    commit(ChatGptAccountsRecord(verified.identity.clientId, retained + SavedChatGptAccount(verified.identity, verified)))
                    publish()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: AccountCommitUnconfirmed) {
                synchronized(lock) {
                    if (ticket == generation) publish(issue = "The account change could not be confirmed in storage. Sign-in is paused; restart to recover. Your messages are preserved.")
                }
            } catch (failure: SignInException) {
                synchronized(lock) {
                    if (ticket == generation) publish(issue = failure.reason.safeMessage + " Your previous account and messages are preserved.")
                }
            } catch (_: Exception) {
                synchronized(lock) {
                    if (ticket == generation) publish(issue = "Sign-in did not complete. Return from the browser and try again. Your previous account and messages are preserved.")
                }
            } finally {
                pending?.close()
                synchronized(lock) {
                    if (listener === pending) { listener = null; pendingAttempt = null }
                }
            }
        }
    }

    fun cancelSignIn() = synchronized(lock) {
        if (mutableUi.value.phase != ChatGptSignInPhase.WAITING_FOR_BROWSER && mutableUi.value.phase != ChatGptSignInPhase.VERIFYING) return
        generation += 1
        pendingAttempt?.cancel(); pendingAttempt = null
        listener?.close(); listener = null
        job?.cancel(); job = null
        publish(issue = "Sign-in cancelled. Your previous account and messages are preserved.")
    }

    fun selectAccount(clientId: String) {
        scope.launch { synchronized(lock) {
            if (!writable || mutableUi.value.busy || records.accounts.none { it.identity.clientId == clientId }) return@launch
            try {
                commit(ChatGptAccountsRecord(clientId, records.accounts))
                publish()
            } catch (_: Exception) { publish(issue = "The account selection could not be saved.") }
        } }
    }

    fun signOut() {
        val selection = synchronized(lock) {
            if (!writable || mutableUi.value.busy) return
            val selected = records.accounts.find { it.identity.clientId == records.activeClientId } ?: return
            generation += 1
            publish(phase = ChatGptSignInPhase.SIGNING_OUT)
            Triple(generation, selected.identity.clientId, selected.session)
        }
        scope.launch {
            val revoked = selection.third?.let { try { client.revoke(it) } catch (_: Exception) { false } } ?: true
            synchronized(lock) {
                if (selection.first != generation) return@launch
                val cleared = records.accounts.map { if (it.identity.clientId == selection.second) SavedChatGptAccount(it.identity, null) else it }
                try {
                    commit(ChatGptAccountsRecord(records.activeClientId, cleared))
                    publish(issue = if (revoked) "Signed out on this phone." else
                        "Signed out on this phone. Remote revocation was not confirmed; you can disconnect the app in ChatGPT settings.")
                } catch (_: Exception) {
                    records = ChatGptAccountsRecord(records.activeClientId, cleared)
                    writable = false
                    publish(issue = "Sign-out could not be saved. This process stopped using the credentials, but their removal from storage is unconfirmed.")
                }
            }
        }
    }

    private fun commit(next: ChatGptAccountsRecord) {
        try { storage.write(next) }
        catch (_: Exception) { writable = false; throw AccountCommitUnconfirmed() }
        records = next
    }
    private fun publish(issue: String? = null, phase: ChatGptSignInPhase = ChatGptSignInPhase.IDLE) {
        val selected = records.accounts.find { it.identity.clientId == records.activeClientId }
        val active = selected?.session
        val expired = active != null && (active.identity.expiresAtEpochSeconds <= now() || active.tokens.expiresAtEpochSeconds <= now())
        val current = active != null && writable && !expired && phase != ChatGptSignInPhase.SIGNING_OUT
        mutableUi.value = ChatGptAccountUi(selected?.identity, records.accounts.map { it.identity }, current, phase,
            issue ?: if (expired && writable) "Your account session expired. Continue with ChatGPT to sign in again." else null,
            writable, records.pendingClientId != null, active != null)
    }
}
