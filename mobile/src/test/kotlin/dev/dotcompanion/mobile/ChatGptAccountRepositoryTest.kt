package dev.dotcompanion.mobile

import android.app.Application
import dev.dotcompanion.identity.AccountIdentity
import dev.dotcompanion.identity.OAuthHttpRequest
import dev.dotcompanion.identity.OAuthHttpResponse
import dev.dotcompanion.identity.OAuthHttpTransport
import dev.dotcompanion.identity.OAuthTokens
import dev.dotcompanion.identity.OpenAiIdentityClient
import dev.dotcompanion.identity.SignInSession
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ChatGptAccountRepositoryTest {
    private class MemoryStore(var record: ChatGptAccountsRecord) : ChatGptAccountStorage {
        var writes = 0
        var failWrite = false
        override fun hostId() = "urn:uuid:690dcb3f-0f1e-4916-a22e-80aed9cc4edb"
        override fun read() = record
        override fun write(record: ChatGptAccountsRecord) {
            writes++
            if (failWrite) throw IOException("Injected account commit failure")
            this.record = record
        }
    }
    private val repositories = mutableListOf<ChatGptAccountRepository>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val providerRequests = mutableListOf<OAuthHttpRequest>()
    private val client = OpenAiIdentityClient(object : OAuthHttpTransport {
        override fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            synchronized(providerRequests) { providerRequests += request }
            throw IOException("No provider requests expected in these lifecycle fixtures")
        }
    })
    private fun saved(clientId: String): SavedChatGptAccount {
        val identity = AccountIdentity("https://auth.openai.com", "fixture-$clientId", clientId,
            "fixture@example.com", null, 2000000000L)
        return SavedChatGptAccount(identity, SignInSession(identity,
            OAuthTokens(null, "identity-fixture", null, emptySet(), 2000000000L, null)))
    }
    private fun repository(storage: MemoryStore): ChatGptAccountRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return ChatGptAccountRepository(storage, client, scope).also { repositories += it }
    }
    private suspend fun await(repository: ChatGptAccountRepository, predicate: (ChatGptAccountUi) -> Boolean) =
        withTimeout(5000) { repository.ui.first(predicate) }

    @Before fun prepareMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun stop() {
        repositories.forEach { it.cancelSignIn() }
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    @Test fun failedNewCandidatePreservesTheSelectedAccountAndPerformsNoExchange() = runBlocking {
        val original = saved("oaiapp_original")
        val storage = MemoryStore(ChatGptAccountsRecord(original.identity.clientId, listOf(original)))
        val repository = repository(storage)
        repository.signIn({ throw IllegalStateException("Synthetic unavailable browser") }, newAccount = true)
        val failed = await(repository) { !it.busy && it.issue != null }
        assertTrue(failed.signedIn)
        assertEquals(original.identity.clientId, failed.activeAccount!!.clientId)
        assertSame(original.session, storage.record.accounts.single().session)
        assertEquals(0, storage.writes)
        assertTrue(providerRequests.isEmpty())
    }
    @Test fun cancelAndRestartUseFreshStateAndTheOldJobCannotClearTheNewAttempt() = runBlocking {
        val original = saved("oaiapp_cancel")
        val storage = MemoryStore(ChatGptAccountsRecord(original.identity.clientId, listOf(original)))
        val repository = repository(storage)
        val opened = Channel<URI>(Channel.UNLIMITED)
        repository.signIn({ opened.trySend(it) })
        val first = withTimeout(5000) { opened.receive() }
        repository.cancelSignIn()
        assertTrue(repository.ui.value.signedIn)
        assertFalse(repository.ui.value.busy)
        repository.signIn({ opened.trySend(it) })
        val second = withTimeout(5000) { opened.receive() }
        assertNotEquals(first.rawQuery, second.rawQuery)
        assertEquals(ChatGptSignInPhase.WAITING_FOR_BROWSER, repository.ui.value.phase)
        assertEquals(original.identity.clientId, repository.ui.value.activeAccount!!.clientId)
        repository.cancelSignIn()
        assertEquals(0, storage.writes)
        assertTrue(providerRequests.isEmpty())
    }
    @Test fun anUnconfirmedAccountSelectionDisablesCredentialsAndFurtherWrites() = runBlocking {
        val first = saved("oaiapp_first")
        val second = saved("oaiapp_second")
        val storage = MemoryStore(ChatGptAccountsRecord(first.identity.clientId, listOf(first, second))).also { it.failWrite = true }
        val repository = repository(storage)
        repository.selectAccount(second.identity.clientId)
        val failed = await(repository) { !it.storageAvailable }
        assertFalse(failed.signedIn)
        assertEquals(first.identity.clientId, storage.record.activeClientId)
        var browserOpened = false
        repository.signIn({ browserOpened = true })
        assertFalse(browserOpened)
        assertEquals(1, storage.writes)
    }
    @Test fun identityOnlySignOutClearsOneSessionAndDisclosesUnconfirmedRevocation() = runBlocking {
        val first = saved("oaiapp_out")
        val other = saved("oaiapp_retained")
        val storage = MemoryStore(ChatGptAccountsRecord(first.identity.clientId, listOf(first, other)))
        val repository = repository(storage)
        repository.signOut()
        val result = await(repository) { !it.busy && it.issue?.contains("revocation was not confirmed") == true }
        assertFalse(result.signedIn)
        assertFalse(result.credentialsPresent)
        assertEquals(first.identity.clientId, storage.record.activeClientId)
        assertNull(storage.record.accounts.first().session)
        assertSame(other.session, storage.record.accounts.last().session)
        assertTrue(providerRequests.isEmpty())
    }
}
