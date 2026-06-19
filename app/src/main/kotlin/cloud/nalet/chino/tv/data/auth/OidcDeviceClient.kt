package cloud.nalet.chino.tv.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * RFC 8628 OAuth 2.0 Device Authorization Grant against Keycloak.
 *
 * Flow:
 *   1) startDeviceAuthorization() — POST {issuer}/protocol/openid-connect/auth/device
 *      → returns a user_code we display on the TV plus a verification_uri the user
 *        opens on their phone.
 *   2) pollForTokens(deviceCode) — POST {issuer}/protocol/openid-connect/token
 *        grant_type=urn:ietf:params:oauth:grant-type:device_code, every `interval` seconds,
 *        until we either get tokens, hit "expired_token", or the user cancels.
 *
 * Keycloak requires the realm to have "OAuth 2.0 Device Authorization Grant" enabled and
 * the client (chino-tv-beta) to be public (no client secret) with the device flow option
 * checked. See README — chino-tv-beta client provisioning is out-of-band.
 */
class OidcDeviceClient(
    private val deviceAuthEndpoint: String,
    private val tokenEndpoint: String,
    private val userinfoEndpoint: String,
    private val clientId: String,
) {
    /**
     * Builds the standard Keycloak openid-connect endpoint paths
     * (auth/device, token, userinfo) from a realm issuer. Used when OIDC discovery hasn't populated
     * the endpoint set yet — preserves the original baked-in behaviour.
     */
    constructor(issuer: String, clientId: String) : this(
        deviceAuthEndpoint = "$issuer/protocol/openid-connect/auth/device",
        tokenEndpoint = "$issuer/protocol/openid-connect/token",
        userinfoEndpoint = "$issuer/protocol/openid-connect/userinfo",
        clientId = clientId,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // PKCE for the device grant (the unified `chino` client enforces S256). The
    // verifier is minted at start and replayed on the token poll. Single-flight
    // (one sign-in at a time), so a plain field is fine.
    @Volatile private var codeVerifier: String? = null

    private fun newCodeVerifier(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }
    private fun codeChallengeS256(verifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }
    private fun base64Url(b: ByteArray): String =
        android.util.Base64.encodeToString(
            b,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )

    suspend fun startDeviceAuthorization(scope: String = DEFAULT_SCOPE): DeviceAuthorization =
        withContext(Dispatchers.IO) {
            val verifier = newCodeVerifier()
            codeVerifier = verifier
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", scope)
                .add("code_challenge", codeChallengeS256(verifier))
                .add("code_challenge_method", "S256")
                .build()
            val req = Request.Builder()
                .url(deviceAuthEndpoint)
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Device auth start failed: HTTP ${resp.code} — $text")
                json.decodeFromString(DeviceAuthorization.serializer(), text)
            }
        }

    /**
     * Polls /token at `interval` seconds. Returns once the user has approved.
     * Throws on any non-recoverable error (expired_token, access_denied, server errors).
     * The caller is responsible for cancelling the coroutine if the user backs out.
     */
    /**
     * Trades a refresh_token for a fresh access_token (+ rotated refresh_token).
     * Keycloak's access token TTL is 5 min by default; without this every API call
     * past that boundary will 401. Returns null on any error so the caller can
     * decide whether to drop to the AUTH screen or retry later.
     */
    suspend fun refresh(refreshToken: String): Tokens? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val req = Request.Builder()
            .url(tokenEndpoint)
            .post(body)
            .build()
        val (code, text) = http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        if (code !in 200..299) return@withContext null
        val tok = json.decodeFromString(TokenResponse.serializer(), text)
        Tokens(
            accessToken = tok.accessToken,
            refreshToken = tok.refreshToken ?: refreshToken,
            expiresAtEpochMillis = System.currentTimeMillis() + tok.expiresIn * 1000L,
        )
    }

    suspend fun pollForTokens(auth: DeviceAuthorization): Tokens = withContext(Dispatchers.IO) {
        var interval = auth.interval.coerceAtLeast(1)
        while (true) {
            delay(interval * 1000L)
            val b = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", DEVICE_GRANT)
                .add("device_code", auth.deviceCode)
            codeVerifier?.let { b.add("code_verifier", it) }   // PKCE
            val body = b.build()
            val req = Request.Builder()
                .url(tokenEndpoint)
                .post(body)
                .build()
            val (code, text) = http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
            if (code in 200..299) {
                val tok = json.decodeFromString(TokenResponse.serializer(), text)
                return@withContext Tokens(
                    accessToken = tok.accessToken,
                    refreshToken = tok.refreshToken,
                    expiresAtEpochMillis = System.currentTimeMillis() + tok.expiresIn * 1000L,
                )
            }
            // OAuth device-flow uses 400 with an `error` field for pending/slow-down/expired,
            // and 4xx/5xx with a different shape for everything else. Parse defensively.
            val err = runCatching { json.decodeFromString(OauthError.serializer(), text) }.getOrNull()
            when (err?.error) {
                "authorization_pending" -> { /* keep polling */ }
                "slow_down" -> { interval += 5 }
                "expired_token", "access_denied" -> throw DeviceAuthException(err.error)
                null -> throw IOException("Token poll failed: HTTP $code — $text")
                else -> throw DeviceAuthException(err.error)
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** Calls Keycloak's userinfo endpoint to extract identity claims. Used
     *  to populate the Account row after a fresh device-flow completion
     *  (sub → account id, name → displayName, email → gravatar). */
    suspend fun fetchUserInfo(accessToken: String): UserInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(userinfoEndpoint)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val (code, text) = http.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
        if (code !in 200..299) return@withContext null
        runCatching { json.decodeFromString(UserInfo.serializer(), text) }.getOrNull()
    }

    companion object {
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val DEFAULT_SCOPE = "openid profile email offline_access"
    }
}

@Serializable
data class UserInfo(
    val sub: String,
    val name: String? = null,
    @SerialName("preferred_username") val preferredUsername: String? = null,
    val email: String? = null,
    @SerialName("given_name") val givenName: String? = null,
    @SerialName("family_name") val familyName: String? = null,
) {
    /** Best-effort human label: name → preferred_username → email local-part → sub. */
    fun bestDisplayName(): String =
        name?.takeIf { it.isNotBlank() }
            ?: preferredUsername?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
            ?: sub
}

@Serializable
data class DeviceAuthorization(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("interval") val interval: Int = 5,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0L,
    @SerialName("token_type") val tokenType: String = "Bearer",
)

@Serializable
private data class OauthError(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
)

class DeviceAuthException(val errorCode: String) : RuntimeException("OIDC device-flow error: $errorCode")
