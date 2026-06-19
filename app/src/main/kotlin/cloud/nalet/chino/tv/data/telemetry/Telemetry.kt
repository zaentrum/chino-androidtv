package cloud.nalet.chino.tv.data.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import cloud.nalet.chino.tv.BuildConfig
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.TelemetryBatch
import cloud.nalet.chino.tv.data.api.TelemetryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * One observability funnel for the whole app. Every event is auto-stamped
 * with device / app / session / network context so the cluster log
 * aggregator can slice by hardware model + ABI + flavor + OS without each
 * call site re-passing the same fields.
 *
 * chino-api's /v1/play/events handler logs each event as a JSON line with
 * `p_<key>` prefixes (see chino-api/internal/http/telemetry.go), so every
 * field we put in the payload becomes its own queryable column.
 *
 * No batching for now — events are low-volume and the existing per-call
 * `runCatching { api.postTelemetry(...) }` pattern is fine. If volume
 * grows (e.g. 1-second player ticks), wrap in a queue + 10s flush here.
 */
class Telemetry(
    context: Context,
    private val api: ChinoApi,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    /** UUID per app process — flips on every cold launch. */
    val sessionId: String = UUID.randomUUID().toString()
    private val sessionStartMs: Long = System.currentTimeMillis()
    /** The active account's id at the moment an event fires. AppContainer
     *  wires this from AccountStore.activeAccountId so cross-account
     *  switches inside the same process are reflected in every subsequent
     *  event. Volatile because reads happen on the OkHttp dispatcher
     *  threads while writes happen on the Compose Main thread. */
    @Volatile private var accountIdSnapshot: String? = null
    fun setActiveAccount(id: String?) { accountIdSnapshot = id }

    /** Cached on construction — none of these change for the life of the process. */
    private val staticContext: Map<String, String> = mapOf(
        "device_model" to Build.MODEL,
        "device_manufacturer" to Build.MANUFACTURER,
        "device_brand" to Build.BRAND,
        "device_abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
        "device_64bit" to Build.SUPPORTED_64_BIT_ABIS.isNotEmpty().toString(),
        "os_version" to Build.VERSION.RELEASE,
        "os_sdk_int" to Build.VERSION.SDK_INT.toString(),
        "app_version" to BuildConfig.VERSION_NAME,
        "app_version_code" to BuildConfig.VERSION_CODE.toString(),
        "app_flavor" to BuildConfig.FLAVOR_NAME,
        "app_build_type" to BuildConfig.BUILD_TYPE,
        "client" to "chino-androidtv",
    )

    /**
     * Fire an event. Doesn't block — schedules a fire-and-forget POST that
     * swallows failures (chino-api/v1/play/events is best-effort logging).
     *
     * @param kind   canonical event name in snake_case (e.g. "screen_view",
     *               "auth_device_flow_started", "playback_buffering_end")
     * @param itemId optional catalogue id when the event is item-scoped
     * @param extra  per-event payload; merged onto the static context
     */
    fun event(kind: String, itemId: String? = null, extra: Map<String, String> = emptyMap()) {
        val payload = buildMap<String, String> {
            putAll(staticContext)
            put("network_type", currentNetworkType())
            put("session_uptime_ms", (System.currentTimeMillis() - sessionStartMs).toString())
            // Account identity — present once an account is active. Lets
            // the backend correlate behaviour across the same Keycloak
            // user even when they switch between devices, and across
            // multiple profiles on a shared TV.
            accountIdSnapshot?.let { put("account_id", it) }
            putAll(extra)
        }
        scope.launch {
            runCatching {
                api.postTelemetry(
                    TelemetryBatch(
                        sessionId = sessionId,
                        events = listOf(
                            TelemetryEvent(
                                ts = System.currentTimeMillis(),
                                kind = kind,
                                itemId = itemId,
                                payload = payload,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Cheap snapshot of the current network transport. Doesn't follow
     * connectivity changes — re-queried on every event so a Wi-Fi → Ethernet
     * switch mid-session reflects in the next event.
     */
    private fun currentNetworkType(): String {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val net = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(net) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }
}
