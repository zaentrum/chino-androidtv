package cloud.nalet.chino.tv.data

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The player's progress saves on their way to the server, for a page that
 * reads the position again as the player closes - the title page's "resume
 * 3:10": it waits for them ([settled]), so it reads the position just saved,
 * never the one before it.
 *
 * The player's last save leaves as its screen goes, a frame after the page
 * under it resumes, and nothing orders the page's read after it on the
 * server. So a player holds the saves open while it is up ([hold]) and
 * closes them with its last one: a page resuming while the player goes waits
 * for that save to be answered, and a page resuming with no player up reads
 * at once.
 */
class ProgressSaves(
    /** How long a read waits at most: past it, it reads what the server has. */
    private val waitAtMostMs: Long = WAIT_AT_MOST_MS,
) {
    private val pending: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /** Sends [save] on [scope], counted until it is answered. */
    fun send(scope: CoroutineScope, save: suspend () -> Unit): Job = counted(scope.launch { save() })

    /** Held while a player is up: the player closes it as it goes
     *  ([Hold.closeAfter]). */
    fun hold(): Hold = Hold(counted(Job()))

    /** A player's hold on the saves. */
    class Hold internal constructor(private val job: CompletableJob) {
        /** The player went: the hold ends once [last], its last save, is
         *  answered - at once where it sent none. */
        fun closeAfter(last: Job?) {
            if (last == null) job.complete() else last.invokeOnCompletion { job.complete() }
        }
    }

    /** Waits until every save sent so far is answered and every player up
     *  so far has gone with its last one, [waitAtMostMs] at most. */
    suspend fun settled() {
        val now = pending.toList()
        if (now.isEmpty()) return
        withTimeoutOrNull(waitAtMostMs) { now.joinAll() }
    }

    private fun <J : Job> counted(job: J): J {
        pending += job
        // At once where it is done already.
        job.invokeOnCompletion { pending -= job }
        return job
    }

    companion object {
        /** A save answered later than this is a slow network: the page reads
         *  rather than keep its label waiting. */
        const val WAIT_AT_MOST_MS = 3_000L
    }
}
