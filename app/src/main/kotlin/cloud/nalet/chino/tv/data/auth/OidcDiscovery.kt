package cloud.nalet.chino.tv.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Concrete OIDC endpoint set resolved from a server's issuer. */
data class OidcEndpoints(
    val issuer: String,
    val deviceAuthEndpoint: String,
    val tokenEndpoint: String,
    val userinfoEndpoint: String,
)

/**
 * OIDC Authorization-Server metadata discovery (RFC 8414 + OIDC Discovery
 * 1.0). Turns an issuer URL into its concrete endpoint set so Chino works
 * against any compliant OIDC provider, not just Keycloak's fixed path
 * layout.
 *
 * For an issuer WITH a path component (Keycloak issuers look like
 * https://host/realms/name), RFC 8414 inserts the well-known segment before
 * the path (https://host/.well-known/oauth-authorization-server/realms/name)
 * while OIDC Discovery 1.0 appends it (https://host/realms/name plus
 * /.well-known/openid-configuration). Keycloak answers the appended form, so
 * we try the RFC 8414 form first and fall back to the appended one.
 *
 * device_authorization_endpoint is required for Chino's RFC 8628 sign-in;
 * a provider that does not advertise it cannot drive the TV flow.
 */
object OidcDiscovery {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(issuer: String): OidcEndpoints? = withContext(Dispatchers.IO) {
        val trimmed = issuer.trimEnd('/')
        for (url in candidateUrls(trimmed)) {
            val doc = fetch(url) ?: continue
            val device = doc.deviceAuthorizationEndpoint ?: continue
            val token = doc.tokenEndpoint ?: continue
            return@withContext OidcEndpoints(
                issuer = doc.issuer ?: trimmed,
                deviceAuthEndpoint = device,
                tokenEndpoint = token,
                userinfoEndpoint = doc.userinfoEndpoint
                    ?: "$trimmed/protocol/openid-connect/userinfo",
            )
        }
        null
    }

    /** Both well-known forms for an issuer; collapses to one when the issuer
     *  has no path component. */
    private fun candidateUrls(issuer: String): List<String> {
        val schemeEnd = issuer.indexOf("://")
        if (schemeEnd < 0) return listOf("$issuer/.well-known/openid-configuration")
        val afterScheme = schemeEnd + 3
        val slash = issuer.indexOf('/', afterScheme)
        return if (slash < 0) {
            listOf("$issuer/.well-known/openid-configuration")
        } else {
            val origin = issuer.substring(0, slash)
            val path = issuer.substring(slash)
            listOf(
                "$origin/.well-known/oauth-authorization-server$path",
                "$issuer/.well-known/openid-configuration",
            )
        }
    }

    private fun fetch(url: String): ProviderMetadata? {
        val req = Request.Builder().url(url).get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                json.decodeFromString(ProviderMetadata.serializer(), resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }
}

@Serializable
private data class ProviderMetadata(
    val issuer: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
    @SerialName("userinfo_endpoint") val userinfoEndpoint: String? = null,
    @SerialName("device_authorization_endpoint") val deviceAuthorizationEndpoint: String? = null,
    @SerialName("jwks_uri") val jwksUri: String? = null,
)
