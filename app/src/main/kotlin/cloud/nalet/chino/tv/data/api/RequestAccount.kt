package cloud.nalet.chino.tv.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The account a write was made under, carried by its request (a Retrofit
 * `@Tag`): the request goes out with that account's bearer, whichever account
 * is active by the time it is sent, so a press lands on the lists, likes or
 * progress of the account it was made on (change P.C16 of the specification).
 * Where that account is no longer signed in, the request is not sent.
 */
data class RequestAccount(val accountId: String)

/** The account a request was made under is no longer signed in on this
 *  device: the request was not sent. */
class AccountSignedOutException(accountId: String) :
    IOException("the account the request was made under is no longer signed in: $accountId")

/** The bearer of one account, for a request that carries it ([RequestAccount]). */
interface AccountBearers {
    /** [accountId]'s access token, renewed where it is close to expiry; null
     *  where it is no longer signed in on this device, or its sign-in lapsed
     *  (no refresh token, or the provider refused it). Blocking. */
    fun tokenOf(accountId: String): String?

    /** A fresh access token of [accountId] after chino-api refused one; null
     *  where none can be had. Blocking. */
    fun forceRefreshOf(accountId: String): String?

    /** chino-api refused [accountId]'s fresh token too. */
    fun refusedAfterRefresh(accountId: String) {}

    /** No account's bearer, for a client that sends no request carrying an
     *  account: one that does is not sent. */
    object None : AccountBearers {
        override fun tokenOf(accountId: String): String? = null
        override fun forceRefreshOf(accountId: String): String? = null
    }
}

/**
 * The account what is done on a screen goes out under - for the player, the
 * account the title plays for: the one active as the screen opened, read once
 * off the main thread, so a write sent after another account was chosen still
 * goes to it (change P.C16 of the specification). Where it could not be read
 * as the screen opened, a write goes out under the account active as it is
 * made, read then; where nobody is active, nothing is sent.
 */
class OpenedFor(
    scope: CoroutineScope,
    /** Who is active now; blocking, so it is read off the main thread. */
    private val activeAccount: () -> String?,
) {
    private val opened: Deferred<Result<String?>> = scope.async(Dispatchers.IO) { runCatching(activeAccount) }

    /** The account a write made now goes out under; null where there is none. */
    suspend fun account(): RequestAccount? {
        val id = opened.await().getOrElse { withContext(Dispatchers.IO) { runCatching(activeAccount).getOrNull() } }
        return id?.let(::RequestAccount)
    }
}
