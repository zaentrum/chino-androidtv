package cloud.nalet.chino.tv.feedback

import android.os.Build
import cloud.nalet.chino.tv.BuildConfig
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.FeedbackReport
import cloud.nalet.chino.tv.data.api.FeedbackResponse
import cloud.nalet.chino.tv.data.api.submitFeedback
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One funnel for bug reports — POST /v1/feedback on chino-api, which opens
 * (or dedup-appends to) a ticket on the connected server's issue tracker.
 * Mirrors chino-mobile's
 * BugReporter and [cloud.nalet.chino.tv.data.telemetry.Telemetry] in shape:
 * held by AppContainer, stamps the static device/app context onto every
 * report so call sites only pass what's specific to them.
 *
 * Two paths with opposite failure semantics:
 *  - [report] (auto: error/crash/player) is fire-and-forget and swallows ALL
 *    failures — an auto report must never crash, toast, or otherwise surface.
 *    Session-throttled: a fingerprint is sent at most once per process, hard
 *    cap [MAX_AUTO_PER_SESSION] auto reports per process (the server rate
 *    limits per user on top of this).
 *  - [reportManual] (the Settings category picker) is a plain suspend call
 *    that PROPAGATES errors so the UI can show an inline failure state.
 *
 * No screenshots on TV: PixelCopy of the video SurfaceView plane comes out
 * black, and the DPAD-driven chrome adds nothing the description doesn't
 * already say (the API still accepts one — see ChinoApi.submitFeedback).
 */
class BugReporter(
    private val api: ChinoApi,
    /** Static device/app fields the host supplies (same map Telemetry stamps
     *  on events) — device model, OS version, app version, flavor, client. */
    private val staticContext: Map<String, String>,
    /** Crash-report files ChinoTvApp's uncaught-exception handler wrote on a
     *  previous process's way down; [flushPending] drains them once the
     *  signed-in library mounts. */
    private val pendingStore: FilePendingReportStore,
) {
    /** Own supervisor scope (not appScope) so a failed auto report can never
     *  cancel siblings and reports outlive any screen's composition. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val throttle = Mutex()
    private val sentFingerprints = mutableSetOf<String>()
    private var autoSentCount = 0
    private var flushedPending = false

    /**
     * Auto report (kind = "error" | "crash" | "player"). Launched on the
     * internal scope; every failure (network, 401 pre-login, 429 rate limit,
     * 5xx) is swallowed silently.
     */
    fun report(
        kind: String,
        title: String? = null,
        description: String,
        fingerprint: String? = null,
        context: Map<String, String> = emptyMap(),
    ) {
        scope.launch {
            runCatching {
                val fp = fingerprint?.takeIf { it.isNotBlank() }
                val allowed = throttle.withLock {
                    when {
                        autoSentCount >= MAX_AUTO_PER_SESSION -> false
                        fp != null && fp in sentFingerprints -> false
                        else -> {
                            fp?.let { sentFingerprints.add(it) }
                            autoSentCount += 1
                            true
                        }
                    }
                }
                if (!allowed) return@launch
                api.submitFeedback(
                    report = FeedbackReport(
                        source = SOURCE,
                        kind = kind,
                        title = title,
                        description = description,
                        fingerprint = fp,
                        context = staticContext + context,
                    ),
                )
            }
        }
    }

    /**
     * Manual report from the Settings "Report a problem" picker. Returns the
     * server's response (id + url + duplicate flag) and PROPAGATES failures
     * (retrofit2.HttpException on 429/503 etc.) so the panel can show an
     * inline error. Not session-throttled — the server's per-user rate limit
     * is the backstop.
     */
    suspend fun reportManual(
        title: String? = null,
        description: String,
        context: Map<String, String> = emptyMap(),
    ): FeedbackResponse = api.submitFeedback(
        report = FeedbackReport(
            source = SOURCE,
            kind = "manual",
            title = title,
            description = description,
            context = staticContext + context,
        ),
    )

    /**
     * Drain crash reports a previous process wrote on its way down. Each file
     * is submitted, deleted on success, and KEPT on failure (e.g. 401 because
     * the crash predated sign-in) for the next launch. Corrupt files are
     * dropped. Once per process — call sites can fire it on every library
     * mount without re-walking the directory.
     */
    suspend fun flushPending() {
        throttle.withLock {
            if (flushedPending) return
            flushedPending = true
        }
        val files = runCatching { pendingStore.list() }.getOrElse { return }
        for (file in files) {
            val pending = runCatching {
                pendingReportJson.decodeFromString(PendingReport.serializer(), file.content)
            }.getOrNull()
            if (pending == null) {
                // Unreadable — drop it so it doesn't re-fail every launch.
                runCatching { pendingStore.delete(file.name) }
                continue
            }
            val sent = runCatching {
                api.submitFeedback(
                    report = FeedbackReport(
                        source = SOURCE,
                        kind = pending.kind,
                        title = pending.title,
                        description = pending.description,
                        fingerprint = pending.fingerprint?.takeIf { it.isNotBlank() },
                        context = pending.context,
                    ),
                )
            }.isSuccess
            if (sent) {
                runCatching { pendingStore.delete(file.name) }
                // Count flushed crashes against the session dedup so the
                // same signature can't also re-file via the live path.
                throttle.withLock {
                    pending.fingerprint?.takeIf { it.isNotBlank() }?.let { sentFingerprints.add(it) }
                }
            }
        }
    }

    companion object {
        private const val SOURCE = "tv"
        private const val MAX_AUTO_PER_SESSION = 3
    }
}

/**
 * Fingerprint of an error signature, shared in spirit with chino-web's and
 * chino-mobile's normalization (exact cross-platform equality is NOT
 * required): sha256Hex(errorClassOrName + "|" + message with digits/uuids
 * stripped + "|" + top 3 stack frames with line:column numbers and URL query
 * strings stripped). Stripping the volatile parts keeps "the same bug"
 * hashing to the same ticket across positions, ids and rebuilt URLs.
 */
fun bugFingerprint(name: String, message: String?, stack: String? = null): String {
    val normalizedMessage = (message ?: "")
        .replace(UUID_REGEX, "<uuid>")
        .replace(DIGITS_REGEX, "<n>")
    val frames = (stack ?: "")
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("at ") }
        .take(3)
        .map { frame ->
            frame
                .replace(QUERY_REGEX, "")
                .replace(LINE_COL_REGEX, "")
        }
        .joinToString(";")
    return sha256Hex("$name|$normalizedMessage|$frames")
}

private val UUID_REGEX =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val DIGITS_REGEX = Regex("\\d+")
private val QUERY_REGEX = Regex("\\?[^\\s)]*")
private val LINE_COL_REGEX = Regex(":\\d+(:\\d+)?")

/** Lowercase hex sha-256 — java.security only, no extra dependency. */
private fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }

/**
 * Static device/app fields stamped on every bug report — the same map
 * [cloud.nalet.chino.tv.data.telemetry.Telemetry] builds for events (its
 * copy is private, and the crash handler must work even before the lazy
 * container graph exists, so the duplication is deliberate). None of these
 * change for the life of the process.
 */
fun deviceStaticContext(): Map<String, String> = mapOf(
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
