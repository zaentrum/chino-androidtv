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
        /** No answer: no connection, the server not reached, a timeout. */
        Connection,

        /** The server answered with an error of its own (5xx), or with what
         *  the app could not use. */
        Server,

        /** 404: what the screen shows is not in the library (any more). */
        NotFound,
    }
}

/** The failure [e] is: an HTTP status chino-api answered (retrofit's
 *  HttpException), else no answer (an IOException: no connection, a
 *  timeout), else an answer the app could not use. */
fun loadFailureOf(e: Throwable): LoadFailure {
    val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
    val kind = when {
        e is HttpException && e.code() == 404 -> LoadFailure.Kind.NotFound
        e is HttpException -> LoadFailure.Kind.Server
        e is IOException -> LoadFailure.Kind.Connection
        else -> LoadFailure.Kind.Server
    }
    return LoadFailure(kind, reason)
}
