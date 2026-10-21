package cloud.nalet.chino.tv.data.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The signed-in account's sign-in no longer holds: chino-api refused it
 * (401), and the silent refresh could not mend it - the identity provider
 * refused the refresh token, there was none, or chino-api refused the fresh
 * token too. The app then goes to sign-in for the account chosen, rather
 * than showing an error: the NavHost listens to [lapses]. A refresh that
 * could not reach the provider is no lapse: that is the connection's, and
 * the screen says so.
 */
class SignInLapses {
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** The id of each account whose sign-in lapsed, as it lapses. */
    val lapses: SharedFlow<String> = events.asSharedFlow()

    fun lapsed(accountId: String) {
        events.tryEmit(accountId)
    }
}
