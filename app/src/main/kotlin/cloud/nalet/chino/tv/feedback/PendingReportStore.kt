package cloud.nalet.chino.tv.feedback

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Crash report persisted by ChinoTvApp's uncaught-exception handler — the
 * process is dying, so the report is written to disk synchronously and
 * submitted by [BugReporter.flushPending] on the NEXT signed-in launch.
 */
@Serializable
data class PendingReport(
    val kind: String,
    val title: String? = null,
    val description: String,
    val fingerprint: String? = null,
    val context: Map<String, String> = emptyMap(),
)

/** Shared by the writer (crash handler) and reader (flushPending). */
internal val pendingReportJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

class PendingReportFile(val name: String, val content: String)

/**
 * Crash-report queue backed by `<filesDir>/bug_reports/<millis>.json`. One
 * instance lives in AppContainer: ChinoTvApp's uncaught-exception handler
 * calls [writeCrashSync] while the process is dying (synchronous on purpose
 * — there is no later), and BugReporter.flushPending drains the files once
 * the library mounts with a signed-in account. Mirrors chino-mobile's
 * FilePendingReportStore.
 */
class FilePendingReportStore(private val dir: File) {

    /**
     * Persist a crash report SYNCHRONOUSLY — called from the uncaught-
     * exception handler right before the previous handler kills the process.
     * Keeps at most [MAX_PENDING] files (oldest dropped) so a crash loop
     * can't grow the directory unbounded. Swallows everything: a failing
     * crash writer must never mask the original crash.
     */
    fun writeCrashSync(description: String, fingerprint: String, context: Map<String, String>) {
        try {
            dir.mkdirs()
            val report = PendingReport(
                kind = "crash",
                description = description,
                fingerprint = fingerprint,
                context = context,
            )
            File(dir, "${System.currentTimeMillis()}.json")
                .writeText(pendingReportJson.encodeToString(PendingReport.serializer(), report))
            val files = jsonFiles()
            if (files.size > MAX_PENDING) {
                files.take(files.size - MAX_PENDING).forEach { it.delete() }
            }
        } catch (_: Throwable) {
            // Dying process — nothing sane to do with a failed write.
        }
    }

    suspend fun list(): List<PendingReportFile> = withContext(Dispatchers.IO) {
        jsonFiles().mapNotNull { f ->
            runCatching { PendingReportFile(name = f.name, content = f.readText()) }.getOrNull()
        }
    }

    suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { File(dir, name).delete() }
    }

    /** Millis-named files sort lexicographically = chronologically. */
    private fun jsonFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    companion object {
        private const val MAX_PENDING = 5
    }
}
