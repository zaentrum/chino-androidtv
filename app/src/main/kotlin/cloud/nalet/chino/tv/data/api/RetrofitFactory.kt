package cloud.nalet.chino.tv.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object RetrofitFactory {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * @param tokenProvider returns the current valid access token (auto-refreshed when close to expiry).
     * @param forceRefresh called by the Authenticator on a 401 — returns a freshly minted token, or
     *        null if the refresh failed (server returns 401, the caller drops to AUTH).
     */
    fun create(
        baseUrl: String,
        tokenProvider: () -> String?,
        forceRefresh: () -> String?,
    ): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder().apply {
                    tokenProvider()?.let { header("Authorization", "Bearer $it") }
                    header("Accept", "application/json")
                }.build()
                chain.proceed(req)
            }
            // OkHttp Authenticator: invoked only on a 401 from the server. We trade
            // the refresh_token for a fresh access_token and replay the request once.
            // If forceRefresh returns null (refresh itself failed), returning null
            // makes OkHttp give up and propagate the 401 to the app, which sends the
            // user back to AUTH.
            .authenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    if (response.request.header("X-Auth-Retry") != null) return null
                    val fresh = forceRefresh() ?: return null
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $fresh")
                        .header("X-Auth-Retry", "1")
                        .build()
                }
            })
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
