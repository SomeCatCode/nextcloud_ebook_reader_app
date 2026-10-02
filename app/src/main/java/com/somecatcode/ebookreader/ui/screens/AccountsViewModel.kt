package com.somecatcode.ebookreader.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.somecatcode.ebookreader.data.account.Account
import com.somecatcode.ebookreader.data.account.AccountAlreadyExistsException
import com.somecatcode.ebookreader.data.account.AccountStore
import com.somecatcode.ebookreader.data.account.Credentials
import com.somecatcode.ebookreader.data.account.NewAccount
import com.somecatcode.ebookreader.data.api.ApiClientFactory
import com.somecatcode.ebookreader.data.api.ApiException
import com.somecatcode.ebookreader.data.api.LoginFlowClient
import com.somecatcode.ebookreader.data.api.LoginFlowPoll
import com.somecatcode.ebookreader.data.api.LoginFlowResult
import com.somecatcode.ebookreader.data.api.ServerCompatibility
import com.somecatcode.ebookreader.data.sync.SyncEngine
import com.somecatcode.ebookreader.data.sync.SyncError
import com.somecatcode.ebookreader.data.sync.SyncState
import com.somecatcode.ebookreader.ui.util.normalizeServerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AccountStatus { OK, SYNCING, OFFLINE, AUTH_EXPIRED, APP_UNAVAILABLE, ERROR }

data class AccountRow(val account: Account, val status: AccountStatus)

enum class AddStage { INPUT, STARTING, WAITING, VERIFYING }

enum class AddError { INVALID_URL, NETWORK, TIMEOUT, APP_MISSING, APP_TOO_OLD, DUPLICATE, WRONG_USER, REJECTED, GENERIC }

/** State of the add-account (or re-login) dialog. `null` in [AccountsUiState.addFlow] = dialog closed. */
data class AddFlowState(
    val serverInput: String = "",
    val stage: AddStage = AddStage.INPUT,
    val error: AddError? = null,
    /** Set when an existing account signs in again (keeps data and downloads). */
    val reloginAccountId: String? = null,
)

data class AccountsUiState(
    val rows: List<AccountRow> = emptyList(),
    val loaded: Boolean = false,
    val addFlow: AddFlowState? = null,
    val removeCandidate: Account? = null,
    val removing: Boolean = false,
    /** Shown after removal when the app password could not be revoked on the server. */
    val revokeFailedHint: Boolean = false,
)

sealed interface AccountsEvent {
    /** Open the Login Flow v2 URL in the browser. */
    data class OpenBrowser(val url: String) : AccountsEvent
    data class AccountAdded(val accountId: String) : AccountsEvent
}

class AccountsViewModel(
    private val accountStore: AccountStore,
    private val loginFlowClient: LoginFlowClient,
    private val apiClientFactory: ApiClientFactory,
    private val syncEngine: SyncEngine,
    private val pollIntervalMs: Long = 2_000,
    private val pollTimeoutMs: Long = 20 * 60_000,
) : ViewModel() {

    private val addFlow = MutableStateFlow<AddFlowState?>(null)
    private val removeCandidate = MutableStateFlow<Account?>(null)
    private val removing = MutableStateFlow(false)
    private val revokeFailedHint = MutableStateFlow(false)
    private val credentialsMissing = MutableStateFlow<Set<String>>(emptySet())
    private var addJob: Job? = null
    private var lastLoginUrl: String? = null

    private val eventChannel = Channel<AccountsEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private val rows = combine(accountStore.accounts, syncEngine.state, credentialsMissing) { accounts, sync, missing ->
        accounts.map { AccountRow(it, statusOf(it, sync[it.id], it.id in missing)) }
    }

    val state: StateFlow<AccountsUiState> = combine(
        rows, addFlow, removeCandidate, removing, revokeFailedHint,
    ) { rows, add, candidate, isRemoving, hint ->
        AccountsUiState(rows, true, add, candidate, isRemoving, hint)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    init {
        viewModelScope.launch {
            accountStore.accounts.collect { accounts ->
                credentialsMissing.value = accounts.filter { accountStore.credentials(it.id) == null }.map { it.id }.toSet()
            }
        }
    }

    private fun statusOf(account: Account, sync: SyncState?, noCredentials: Boolean): AccountStatus = when {
        noCredentials -> AccountStatus.AUTH_EXPIRED
        sync is SyncState.Running -> AccountStatus.SYNCING
        sync is SyncState.Failed -> when (sync.error) {
            SyncError.UNAUTHORIZED -> AccountStatus.AUTH_EXPIRED
            SyncError.APP_UNAVAILABLE -> AccountStatus.APP_UNAVAILABLE
            SyncError.OFFLINE -> AccountStatus.OFFLINE
            SyncError.SERVER, SyncError.UNKNOWN -> AccountStatus.ERROR
        }
        else -> AccountStatus.OK
    }

    // ---- add account / re-login --------------------------------------------------------------------

    fun showAddAccount() {
        addFlow.value = AddFlowState()
    }

    fun showRelogin(account: Account) {
        addFlow.value = AddFlowState(serverInput = account.serverUrl, reloginAccountId = account.id)
    }

    fun onServerInputChange(value: String) {
        addFlow.update { it?.copy(serverInput = value, error = null) }
    }

    fun dismissAddFlow() {
        addJob?.cancel()
        addFlow.value = null
    }

    /** Validates the address and runs Login Flow v2: start -> browser -> poll -> verify -> store. */
    fun startLogin() {
        val flow = addFlow.value ?: return
        if (flow.stage != AddStage.INPUT) return
        val server = normalizeServerInput(flow.serverInput)
        if (server == null) {
            addFlow.value = flow.copy(error = AddError.INVALID_URL)
            return
        }
        addFlow.value = flow.copy(serverInput = server, stage = AddStage.STARTING, error = null)
        addJob = viewModelScope.launch {
            try {
                val start = retryNetwork { loginFlowClient.start(server) }
                lastLoginUrl = start.login
                addFlow.update { it?.copy(stage = AddStage.WAITING) }
                eventChannel.send(AccountsEvent.OpenBrowser(start.login))
                val result = poll(start.poll)
                if (result == null) {
                    fail(AddError.TIMEOUT)
                    return@launch
                }
                addFlow.update { it?.copy(stage = AddStage.VERIFYING) }
                finish(result, flow.reloginAccountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                Log.w(TAG, "Login failed: ${e::class.simpleName}: ${e.message}", e)
                fail(if (e is ApiException.Network) AddError.NETWORK else AddError.GENERIC)
            } catch (e: Exception) {
                Log.w(TAG, "Login failed: ${e::class.simpleName}: ${e.message}", e)
                fail(AddError.GENERIC)
            }
        }
    }

    fun reopenBrowser() {
        val url = lastLoginUrl ?: return
        eventChannel.trySend(AccountsEvent.OpenBrowser(url))
    }

    /** Cancel while waiting for the browser: back to the address input. */
    fun cancelLogin() {
        addJob?.cancel()
        addFlow.update { it?.copy(stage = AddStage.INPUT, error = null) }
    }

    private suspend fun poll(poll: LoginFlowPoll): LoginFlowResult? {
        var waited = 0L
        var networkErrors = 0
        while (waited <= pollTimeoutMs) {
            delay(pollIntervalMs)
            waited += pollIntervalMs
            try {
                loginFlowClient.pollOnce(poll)?.let { return it }
                networkErrors = 0
            } catch (e: ApiException.Network) {
                if (++networkErrors > 5) throw e
            }
        }
        return null
    }

    private suspend fun finish(result: LoginFlowResult, reloginAccountId: String?) {
        val api = apiClientFactory.forCredentials(result.server, result.loginName, result.appPassword)
        suspend fun revokeNewPassword() = runCatching { api.revokeAppPassword() }
        try {
            val compatibility = when (val c = retryNetwork { api.checkCompatibility() }) {
                ServerCompatibility.AppMissing -> { revokeNewPassword(); fail(AddError.APP_MISSING); return }
                ServerCompatibility.AppTooOld -> { revokeNewPassword(); fail(AddError.APP_TOO_OLD); return }
                is ServerCompatibility.Ok -> c
            }
            val user = retryNetwork { api.currentUser() }
            if (reloginAccountId != null) {
                val existing = accountStore.get(reloginAccountId)
                if (existing == null || existing.userId != user.id) {
                    revokeNewPassword()
                    fail(AddError.WRONG_USER)
                    return
                }
                accountStore.updateCredentials(reloginAccountId, Credentials(result.loginName, result.appPassword))
                syncEngine.requestSync(reloginAccountId)
                addFlow.value = null
                eventChannel.send(AccountsEvent.AccountAdded(reloginAccountId))
                return
            }
            val added = accountStore.add(
                NewAccount(
                    serverUrl = result.server,
                    loginName = result.loginName,
                    appPassword = result.appPassword,
                    userId = user.id,
                    displayName = user.displayName ?: user.displayname,
                    serverVersion = compatibility.serverVersion,
                    appVersion = compatibility.capabilities.apiVersion.toString(),
                ),
            )
            syncEngine.requestSync(added.id)
            addFlow.value = null
            eventChannel.send(AccountsEvent.AccountAdded(added.id))
        } catch (e: AccountAlreadyExistsException) {
            revokeNewPassword()
            fail(AddError.DUPLICATE)
        } catch (e: ApiException.Unauthorized) {
            Log.w(TAG, "Login rejected: ${e.message}")
            fail(AddError.REJECTED)
        } catch (e: ApiException.Network) {
            Log.w(TAG, "Login network error: ${e.message}", e)
            fail(AddError.NETWORK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. storing the credentials failed: do not leave an unused app password on the server
            revokeNewPassword()
            throw e
        }
    }

    /**
     * The Login Flow result can be fetched only once, so short network hiccups right after returning
     * from the browser must not lose it. Some devices (Samsung) also block an app's network for a few
     * seconds after it comes to the foreground, which surfaces as a DNS failure.
     */
    private suspend fun <T> retryNetwork(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ApiException.Network) {
                if (++attempt >= NETWORK_RETRIES) throw e
                Log.i(TAG, "Network error during verification, retry $attempt: ${e.message}")
                delay(pollIntervalMs * attempt)
            }
        }
    }

    private fun fail(error: AddError) {
        addFlow.update { it?.copy(stage = AddStage.INPUT, error = error) }
    }

    // ---- remove ------------------------------------------------------------------------------------

    fun askRemove(account: Account) {
        removeCandidate.value = account
    }

    fun dismissRemove() {
        if (!removing.value) removeCandidate.value = null
    }

    fun dismissRevokeHint() {
        revokeFailedHint.value = false
    }

    /** Revokes the app password first; a failure does not prevent the local removal but shows a hint. */
    fun confirmRemove() {
        val account = removeCandidate.value ?: return
        if (removing.value) return
        removing.value = true
        viewModelScope.launch {
            val revoked = runCatching { apiClientFactory.forAccount(account.id).revokeAppPassword() }.isSuccess
            accountStore.remove(account.id)
            removing.value = false
            removeCandidate.value = null
            revokeFailedHint.value = !revoked
        }
    }

    fun sync(account: Account) = syncEngine.requestSync(account.id)
}

private const val TAG = "EbrAccounts"
private const val NETWORK_RETRIES = 6
