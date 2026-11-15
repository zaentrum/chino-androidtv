package cloud.nalet.chino.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Work done ahead for the active account, once per account: the app-start Zap
 * warm. It is the account's alone (change P.C16 of the specification): when
 * another account is active, the work for the one before stops, and so do the
 * downloads it handed on, so nothing goes on being asked with its stream token.
 *
 * The work hands its result on through `handOver`, which runs only while the
 * work is still the active account's, under the lock a change of account takes:
 * what is handed on before the change is stopped by it, and nothing is handed on
 * after.
 */
internal class AccountWarm(
    private val scope: CoroutineScope,
    /** Who is active now; blocking, so it is read on [scope]. */
    private val activeAccount: () -> String?,
    /** Stops the downloads the work handed on, and forgets what they were. */
    private val stopHandedOn: () -> Unit,
) {
    private val lock = Any()
    private var warmedFor: String? = null
    private var job: Job? = null

    /** Starts [work] for the active account, unless it has had it. */
    fun start(work: suspend (handOver: (() -> Unit) -> Boolean) -> Unit) {
        scope.launch {
            val account = runCatching(activeAccount).getOrNull() ?: return@launch
            synchronized(lock) {
                if (warmedFor == account) return@launch
                if (warmedFor != null) stopLocked()
                warmedFor = account
                job = scope.launch {
                    runCatching {
                        work { block ->
                            synchronized(lock) {
                                val still = isActive && warmedFor == account &&
                                    runCatching(activeAccount).getOrNull() == account
                                if (still) block()
                                still
                            }
                        }
                    }
                }
            }
        }
    }

    /** [account] is the active one now: work for another stops. */
    fun activeIs(account: String?) {
        synchronized(lock) {
            if (warmedFor != null && warmedFor != account) stopLocked()
        }
    }

    /** Must hold [lock]. */
    private fun stopLocked() {
        job?.cancel()
        job = null
        warmedFor = null
        runCatching(stopHandedOn)
    }
}
