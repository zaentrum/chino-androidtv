package cloud.nalet.chino.tv.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cloud.nalet.chino.tv.BuildConfig
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.BootstrapResult
import cloud.nalet.chino.tv.data.ServerBootstrap
import cloud.nalet.chino.tv.data.ServerConfigStore
import cloud.nalet.chino.tv.data.auth.AccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ServerSetupState {
    data object Idle : ServerSetupState
    data object Probing : ServerSetupState
    data class Error(val message: String) : ServerSetupState
    data object Done : ServerSetupState
}

/**
 * Drives the Add-Server screen: probes a user-entered server URL via
 * [ServerBootstrap] (healthz → /api/config → OIDC discovery) and, on success,
 * persists the resolved [cloud.nalet.chino.tv.data.ServerConfig] + records the
 * URL in recents, then signals Done so the host advances to device-flow
 * sign-in.
 */
class ServerSetupViewModel(
    private val store: ServerConfigStore,
    /** Build-flavor server origin offered as a one-tap preset (blank for a
     *  neutral build with no baked default). */
    val presetUrl: String,
    /** Settings "Change server" entry. When true the screen prefills
     *  [prefillUrl] (the currently-connected origin), shows a Cancel affordance,
     *  and on a successful connect wipes the existing accounts — their tokens
     *  belong to the OLD OIDC issuer — before pointing at the new server. The
     *  NavHost then restarts the process so the lazy graph re-reads the config. */
    val changeServer: Boolean = false,
    /** Address to seed the input with on first composition — the current server
     *  origin when changing servers, else the build preset. */
    val prefillUrl: String = presetUrl,
    /** Non-null only on the change-server path; used to clear the old accounts
     *  on connect. */
    private val accountStore: AccountStore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<ServerSetupState>(ServerSetupState.Idle)
    val state: StateFlow<ServerSetupState> = _state.asStateFlow()

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    init {
        viewModelScope.launch { _recents.value = store.recents() }
    }

    fun connect(rawUrl: String) {
        if (_state.value is ServerSetupState.Probing || rawUrl.isBlank()) return
        _state.value = ServerSetupState.Probing
        viewModelScope.launch {
            when (val r = ServerBootstrap.probe(rawUrl)) {
                is BootstrapResult.Ok -> {
                    // Changing to a DIFFERENT server: the existing accounts hold
                    // tokens for the OLD issuer, so wipe them before saving the
                    // new config (the restart that follows then routes to fresh
                    // sign-in). Re-confirming the SAME server must NOT log out.
                    val differentServer = changeServer &&
                        ServerBootstrap.normalize(rawUrl) != ServerBootstrap.normalize(prefillUrl)
                    if (differentServer) {
                        val acc = accountStore
                        if (acc != null) {
                            // Only swap the config once the old-issuer accounts
                            // are actually gone — otherwise the boot gate would
                            // route to LIBRARY with stale wrong-issuer tokens that
                            // fail on first call instead of forcing re-auth.
                            val wiped = runCatching {
                                acc.snapshotBlocking().accounts.forEach { acc.remove(it.id) }
                                acc.snapshotBlocking().accounts.isEmpty()
                            }.getOrDefault(false)
                            if (!wiped) {
                                _state.value = ServerSetupState.Error(
                                    "Couldn't sign out the current account — please try again.",
                                )
                                return@launch
                            }
                        }
                    }
                    store.save(r.config)
                    store.addRecent(ServerBootstrap.normalize(rawUrl))
                    _state.value = ServerSetupState.Done
                }
                is BootstrapResult.Fail -> _state.value = ServerSetupState.Error(messageFor(r))
            }
        }
    }

    private fun messageFor(f: BootstrapResult.Fail): String = when (f.kind) {
        BootstrapResult.Fail.Kind.UNREACHABLE ->
            "Couldn't reach that server. Check the address and that it's online."
        BootstrapResult.Fail.Kind.NOT_CHINO ->
            "That address responded, but it doesn't look like a Chino server."
        BootstrapResult.Fail.Kind.TLS ->
            "Secure connection failed — the server's certificate isn't trusted."
        BootstrapResult.Fail.Kind.NO_CONFIG ->
            "Server reachable, but it didn't return its configuration (/api/config)."
        BootstrapResult.Fail.Kind.DEVICE_GRANT_UNSUPPORTED ->
            "This server's login provider doesn't support TV sign-in (device flow)."
    }

    companion object {
        /** Build-flavor origin offered as the one-tap preset. Blank for the
         *  neutral store (prod) build, which ships with no baked operator URL;
         *  the beta channel surfaces its origin as a convenience. Distinct from
         *  API_BASE_URL (the internal non-UI fallback). */
        private fun presetOrigin(): String = BuildConfig.SERVER_PRESET
            .removeSuffix("/")
            .trim()

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                ServerSetupViewModel(
                    store = container.serverConfigStore,
                    presetUrl = presetOrigin(),
                )
            }
        }

        /** Settings "Change server" variant: prefills the currently-connected
         *  origin and clears accounts on connect. */
        fun changeServerFactory(container: AppContainer) = viewModelFactory {
            initializer {
                val preset = presetOrigin()
                val currentOrigin = container.serverConfigStore.currentBlocking()?.baseUrl
                    ?.removeSuffix("/")
                    ?.removeSuffix("/api")
                    ?.takeIf { it.isNotBlank() }
                    ?: preset
                ServerSetupViewModel(
                    store = container.serverConfigStore,
                    presetUrl = preset,
                    changeServer = true,
                    prefillUrl = currentOrigin,
                    accountStore = container.accountStore,
                )
            }
        }
    }
}
