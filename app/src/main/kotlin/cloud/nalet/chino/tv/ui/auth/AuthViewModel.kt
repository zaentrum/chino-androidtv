package cloud.nalet.chino.tv.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.data.auth.DeviceAuthException
import cloud.nalet.chino.tv.data.auth.DeviceAuthorization
import cloud.nalet.chino.tv.data.auth.OidcDeviceClient
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Starting : AuthUiState
    data class WaitingForUser(val authorization: DeviceAuthorization) : AuthUiState
    data object Authenticated : AuthUiState
    data class Error(val message: String) : AuthUiState
}

class AuthViewModel(
    private val oidc: OidcDeviceClient,
    private val accounts: AccountStore,
    private val telemetry: Telemetry,
) : ViewModel() {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun start() {
        pollJob?.cancel()
        _state.value = AuthUiState.Starting
        telemetry.event("auth_device_flow_started")
        pollJob = viewModelScope.launch {
            try {
                val auth = oidc.startDeviceAuthorization()
                _state.value = AuthUiState.WaitingForUser(auth)
                val received = oidc.pollForTokens(auth)
                // Hit userinfo to populate displayName + email + the real
                // Keycloak sub claim (= account id). Without this we'd store
                // a synthetic id and re-running the device flow would create
                // a duplicate account row in the picker.
                val info = oidc.fetchUserInfo(received.accessToken)
                val account = Account(
                    id = info?.sub ?: "anon-${System.currentTimeMillis()}",
                    displayName = info?.bestDisplayName() ?: "Account",
                    email = info?.email ?: "",
                    accessToken = received.accessToken,
                    refreshToken = received.refreshToken,
                    expiresAtEpochMillis = received.expiresAtEpochMillis,
                    lastUsedAt = System.currentTimeMillis(),
                )
                accounts.addOrUpdate(account, setActive = true)
                _state.value = AuthUiState.Authenticated
                telemetry.event(
                    "auth_device_flow_completed",
                    extra = mapOf("account_sub" to account.id),
                )
            } catch (e: DeviceAuthException) {
                telemetry.event("auth_device_flow_failed", extra = mapOf("error" to e.errorCode))
                _state.value = AuthUiState.Error(
                    when (e.errorCode) {
                        "expired_token" -> "The code expired. Please try again."
                        "access_denied" -> "Sign-in was cancelled."
                        else -> "Sign-in failed: ${e.errorCode}"
                    },
                )
            } catch (e: Exception) {
                telemetry.event("auth_device_flow_failed", extra = mapOf("error" to (e.message ?: e::class.java.simpleName)))
                _state.value = AuthUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    fun cancel() {
        pollJob?.cancel()
        _state.value = AuthUiState.Idle
    }

    /** Resets the state so a returning DeviceCodeScreen re-runs `start()`.
     *  Needed when the user navigates to Add-Account, leaves, and comes back
     *  — the prior Authenticated state would otherwise skip the QR. */
    fun reset() {
        pollJob?.cancel()
        _state.value = AuthUiState.Idle
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AuthViewModel(container.oidcDeviceClient, container.accountStore, container.telemetry) as T
        }
    }
}
