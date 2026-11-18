package cloud.nalet.chino.tv.data.api

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

/** The code of chino-api's 401 for an account whose deletion has begun
 *  (section 16.5.6 of the specification). */
internal const val ACCOUNT_DELETED = "account_deleted"

/** The most of a 401's body read for its code: chino-api's
 *  `{"error":…,"detail":…}` is one line. */
private const val CODE_BYTES = 4_096L

/**
 * Whether this answer is chino-api's 401 `{"error":"account_deleted",…}`: the
 * account the request went out for is deleted on the server, and no fresh
 * token mends that. Read from a copy of the body, so the caller still reads
 * it; a plain-text 401 ("invalid token") or a body that is no JSON is not.
 */
internal fun Response.saysAccountDeleted(): Boolean =
    code == 401 && runCatching {
        RetrofitFactory.json.parseToJsonElement(peekBody(CODE_BYTES).string())
            .jsonObject["error"]?.jsonPrimitive?.contentOrNull == ACCOUNT_DELETED
    }.getOrDefault(false)
