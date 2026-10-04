package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ChinoApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * What chino-api answered to deleting the signed-in person's account
 * (DELETE /v1/me). chino-api deletes what it keeps of the person — their
 * watch progress, lists, likes and watch history — and their sign-in, all or
 * nothing:
 *  - 200 [Deleted]: the app signs the account out and goes back to the start;
 *  - 409 [Refused] (the last admin, an account the platform manages):
 *    `message` says why, for the person;
 *  - 501 [Unavailable]: not set up on this server;
 *  - 502, or any other answer, or no answer at all, [Failed]: nothing
 *    deleted — try again later, with the server's `message`.
 * Every answer but [Deleted] leaves the account signed in.
 */
sealed interface AccountDeletion {
    data object Deleted : AccountDeletion
    data class Refused(val message: String) : AccountDeletion
    data object Unavailable : AccountDeletion
    data class Failed(val message: String) : AccountDeletion
}

const val ACCOUNT_DELETION_UNAVAILABLE =
    "Deleting your account isn't available on this server — ask its administrator."

/** The heading an answer is shown under — short, title case; none for [AccountDeletion.Deleted]. */
val AccountDeletion.title: String?
    get() = when (this) {
        AccountDeletion.Deleted -> null
        is AccountDeletion.Refused -> "Not Deleted"
        AccountDeletion.Unavailable -> "Not Available"
        is AccountDeletion.Failed -> "Try Again Later"
    }

/** What is said under the heading. */
val AccountDeletion.text: String
    get() = when (this) {
        AccountDeletion.Deleted -> "Your account is deleted."
        is AccountDeletion.Refused -> message
        AccountDeletion.Unavailable -> ACCOUNT_DELETION_UNAVAILABLE
        is AccountDeletion.Failed -> message
    }

/** Asking again changes nothing: the server refused, or deletes no accounts. */
val AccountDeletion.isFinal: Boolean
    get() = this is AccountDeletion.Refused || this == AccountDeletion.Unavailable

/** What an answer means, from its status and the `message` of its body (null
 *  when it carried none a person can read). Only a 200 says the account is
 *  gone. */
fun accountDeletionAnswer(status: Int, message: String?): AccountDeletion = when (status) {
    200 -> AccountDeletion.Deleted
    409 -> AccountDeletion.Refused(message ?: "This server won't delete your account.")
    501 -> AccountDeletion.Unavailable
    401 -> AccountDeletion.Failed(
        message ?: "This server no longer accepts your sign-in. Sign in again, then try once more.",
    )
    else -> AccountDeletion.Failed(message ?: "Your account couldn't be deleted right now (HTTP $status).")
}

/** Longest server message shown: a sentence or two, not a page. */
private const val MAX_MESSAGE = 300

private val answerJson = Json { ignoreUnknownKeys = true }

/** The `message` of chino-api's JSON answer — written for the person — or
 *  null: an empty body, a proxy's page, a plain-text error. */
internal fun personMessage(body: String?): String? = runCatching {
    answerJson.parseToJsonElement(body.orEmpty()).jsonObject["message"]?.jsonPrimitive?.contentOrNull
}.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_MESSAGE)

/** Asks chino-api to delete the signed-in account. Never throws but for
 *  cancellation: a request that does not reach the server is a failure. */
suspend fun ChinoApi.deleteAccount(): AccountDeletion = try {
    val response = deleteMe()
    if (response.code() == 200) {
        response.body()?.close()
        AccountDeletion.Deleted
    } else {
        accountDeletionAnswer(response.code(), personMessage(response.errorBody()?.use { it.string() }))
    }
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    AccountDeletion.Failed("The server couldn't be reached.")
} catch (e: Exception) {
    AccountDeletion.Failed("Your account couldn't be deleted right now.")
}

/**
 * Deletes the signed-in account and, once the server says it is gone — and
 * only then — runs [signOut]. Returns the answer.
 */
suspend fun ChinoApi.deleteAccountThenSignOut(signOut: suspend () -> Unit): AccountDeletion {
    val answer = deleteAccount()
    if (answer == AccountDeletion.Deleted) signOut()
    return answer
}
