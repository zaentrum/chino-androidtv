package cloud.nalet.chino.tv.data.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Accounts the server has deleted (section 16.5.6 of the specification):
 * chino-api answers what a deleted account sends 401 `account_deleted`, and
 * the account is signed out on this TV as removing it does ([signOut]): its
 * tokens leave the account store, and what the TV keeps of it goes. No fresh
 * token is asked for it. Another account signed in here stays, and is not
 * made active: where the one deleted was active, nobody is, and the app starts
 * over on who's watching, or sign-in where nobody is left. [signedOut] has the
 * id of each account so signed out, once, for the toast.
 */
class DeletedAccounts(
    private val scope: CoroutineScope,
    /** Signs [accountId] out on this TV; false where it was not signed in
     *  here (any more). */
    private val signOut: suspend (accountId: String) -> Boolean,
) {
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** The id of each account signed out because it was deleted. */
    val signedOut: SharedFlow<String> = events.asSharedFlow()

    /** chino-api answered that [accountId] is deleted: it is signed out on
     *  this TV, and said where it was still signed in. Returns at once. */
    fun deleted(accountId: String) {
        scope.launch {
            val wasHere = try {
                signOut(accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (wasHere) events.emit(accountId)
        }
    }

    /** [accountId] was deleted from this TV (delete account) and is signed
     *  out already: said as one the server answered deleted. */
    fun deletedHere(accountId: String) {
        events.tryEmit(accountId)
    }
}
