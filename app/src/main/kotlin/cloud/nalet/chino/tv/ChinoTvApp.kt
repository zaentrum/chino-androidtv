package cloud.nalet.chino.tv

import android.app.Application
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.feedback.bugFingerprint
import cloud.nalet.chino.tv.feedback.deviceStaticContext
import kotlinx.coroutines.launch

class ChinoTvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // AppContainer's constructor itself is cheap — every heavy
        // dependency (TokenStore + AccountStore EncryptedSharedPreferences,
        // Retrofit, OkHttp client, Telemetry) is `by lazy` and only
        // materialises on first use, off the main thread. Application.onCreate
        // must NOT touch any of those properties — the first touch will
        // round-trip through AndroidKeyStore and can stall multiple seconds
        // on AOSP x86 emulators, ANR'ing process startup.
        container = AppContainer(this)
        // Persist-then-rethrow crash reporter, installed right after the
        // container exists so even a crash during the first composition is
        // captured. The handler touches only container.pendingReports (a
        // cheap File wrapper) — never the heavy lazy graph above.
        installCrashReporter()
        // First-event-of-process telemetry runs on the appScope (IO
        // dispatcher), so the lazy graph (telemetry → chinoApi → retrofit →
        // tokenManager → accountStore → tokenStore) materialises off the
        // main thread. The event itself is fire-and-forget either way.
        container.appScope.launch {
            container.telemetry.event("app_start")
        }
        // Build the shared Zap on-disk cache off the main thread NOW, so the
        // ZapScreen player path (a main-thread LaunchedEffect) never triggers the
        // SimpleCache's first-open disk I/O itself and ANRs. See
        // AppContainer.warmZapCacheOffMainThread.
        container.warmZapCacheOffMainThread()
    }

    /** Persist a crash as a pending bug report, then delegate to the previous
     *  handler (which shows the system crash dialog / kills the process). The
     *  write is SYNCHRONOUS on purpose — the process dies right after, so a
     *  coroutine would never run. The report is submitted on the NEXT launch,
     *  once the library mounts with a signed-in account
     *  (BugReporter.flushPending keeps the file when submission fails, e.g.
     *  crash-before-first-login). Mirrors chino-mobile's crash reporter. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = throwable.stackTraceToString()
                container.pendingReports.writeCrashSync(
                    description = stack,
                    fingerprint = bugFingerprint(
                        name = throwable.javaClass.name,
                        message = throwable.message,
                        stack = stack,
                    ),
                    context = deviceStaticContext() + mapOf("thread" to thread.name),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
