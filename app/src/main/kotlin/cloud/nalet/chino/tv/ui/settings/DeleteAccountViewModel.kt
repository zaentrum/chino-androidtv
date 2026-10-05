package cloud.nalet.chino.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cloud.nalet.chino.tv.data.AccountDeletion
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.NoticesRepository
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.deleteAccountThenSignOut
import cloud.nalet.chino.tv.data.isFinal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DeleteAccountState {
    /** Asking: the person has not confirmed yet. */
    data object Asking : DeleteAccountState
    data object Deleting : DeleteAccountState
    /** The server answered and the account is still there; [answer] says why. */
    data class NotDeleted(val answer: AccountDeletion) : DeleteAccountState
    /** Deleted, and signed out on this TV. [othersRemain]: other accounts are
     *  still signed in here (the picker, else sign-in, comes next). */
    data class SignedOut(val othersRemain: Boolean) : DeleteAccountState
}

/**
 * Delete Account: asks chino-api to delete the account that was active when
 * the screen opened ([account]) and, once the server says it is gone — and
 * only then — signs it out on this TV: its tokens leave [AccountStore], and
 * the stream token and the lists, likes and notices cached for it go. Any
 * other answer leaves it signed in, with the reason.
 *
 * The request and the sign-out run on the app's scope, not this screen's: a
 * deleted account is signed out even should the screen be gone by the time
 * the answer comes.
 */
class DeleteAccountViewModel(
    private val api: ChinoApi,
    private val accounts: AccountStore,
    private val streamTokens: StreamTokenManager,
    private val userFlags: UserFlagsRepository,
    private val notices: NoticesRepository,
    private val appScope: CoroutineScope,
    /** The account to delete: the active one when the screen opened. */
    val account: Account?,
    /** The connected server's host, to name it. */
    val serverHost: String?,
) : ViewModel() {
    private val _state = MutableStateFlow<DeleteAccountState>(DeleteAccountState.Asking)
    val state: StateFlow<DeleteAccountState> = _state.asStateFlow()

    fun delete() {
        val target = account ?: return
        when (val s = _state.value) {
            DeleteAccountState.Deleting, is DeleteAccountState.SignedOut -> return
            // Refused, or a server that deletes no accounts: asking again
            // changes nothing.
            is DeleteAccountState.NotDeleted -> if (s.answer.isFinal) return
            DeleteAccountState.Asking -> Unit
        }
        _state.value = DeleteAccountState.Deleting
        appScope.launch {
            // The request carries the active account's bearer: never send it
            // for another account than the one on screen.
            val answer = if (accounts.snapshotBlocking().activeAccount?.id != target.id) {
                AccountDeletion.Failed("Another account is signed in now. Open Delete Account again.")
            } else {
                api.deleteAccountThenSignOut {
                    accounts.remove(target.id)
                    streamTokens.clear()
                    userFlags.clear()
                    notices.clear()
                }
            }
            _state.value = if (answer == AccountDeletion.Deleted) {
                DeleteAccountState.SignedOut(othersRemain = accounts.snapshotBlocking().accounts.isNotEmpty())
            } else {
                DeleteAccountState.NotDeleted(answer)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DeleteAccountViewModel(
                    api = container.chinoApi,
                    accounts = container.accountStore,
                    streamTokens = container.streamTokenManager,
                    userFlags = container.userFlags,
                    notices = container.notices,
                    appScope = container.appScope,
                    account = container.accountStore.snapshotBlocking().activeAccount,
                    // Same host derivation the NavHost uses for the Server row.
                    serverHost = container.serverConfig.baseUrl
                        .substringAfter("://")
                        .substringBefore("/")
                        .takeIf { it.isNotBlank() },
                ) as T
        }
    }
}
