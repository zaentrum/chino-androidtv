package cloud.nalet.chino.tv.data.api

import retrofit2.HttpException
import java.io.IOException

/**
 * Why a screen's content did not load, as the error screen says it: by what
 * the server answered - its HTTP status - or that it could not be reached,
 * never by the words of an error. [reason] is the technical one, for the
 * screen's details.
 */
data class LoadFailure(val kind: Kind, val reason: String) {
    enum class Kind {
        /** No answer - no connection, the server not reached, a timeout - or
         *  the server's own timeout (408). */
        Connection,

        /** The server answered with an error of its own (5xx), or with what
         *  the app could not use. */
        Server,

        /** 429: the server is busy for now. */
        Busy,

        /** Another 4xx (400, 409, 415, 422 …): the server did not take the
         *  request as the app sent it - an app older or newer than it. */
        Rejected,

        /** 404 or 410: what the screen shows is not in the library (any
         *  more). */
        NotFound,

        /** 403: the account may not see what the screen shows. */
        Forbidden,

        /** 401: the sign-in no longer holds, the silent refresh did not mend
         *  it - the app goes to sign-in (SignInLapses). */
        SignedOut,
    }
}

/** The failure [e] is: an HTTP status chino-api answered (retrofit's
 *  HttpException), else no answer (an IOException: no connection, a
 *  timeout), else an answer the app could not use. */
fun loadFailureOf(e: Throwable): LoadFailure {
    val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
    val kind = when (e) {
        is HttpException -> kindOfStatus(e.code())
        is IOException -> LoadFailure.Kind.Connection
        else -> LoadFailure.Kind.Server
    }
    return LoadFailure(kind, reason)
}

/** What an HTTP [status] that is no success says of a load. */
internal fun kindOfStatus(status: Int): LoadFailure.Kind = when (status) {
    401 -> LoadFailure.Kind.SignedOut
    403 -> LoadFailure.Kind.Forbidden
    404, 410 -> LoadFailure.Kind.NotFound
    408 -> LoadFailure.Kind.Connection
    429 -> LoadFailure.Kind.Busy
    in 400..499 -> LoadFailure.Kind.Rejected
    else -> LoadFailure.Kind.Server
}
