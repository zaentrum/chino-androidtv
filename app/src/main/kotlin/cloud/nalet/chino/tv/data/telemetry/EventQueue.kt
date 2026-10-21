package cloud.nalet.chino.tv.data.telemetry

import cloud.nalet.chino.tv.data.api.TelemetryEvent

/** The app's name in its events; chino-api counts what names a TV as a TV's. */
const val TELEMETRY_CLIENT = "chino-androidtv"

/** How long a queued event waits for others before its batch goes. */
const val QUEUE_FLUSH_MS = 30_000L

/**
 * The events waiting to go in one batch ([Telemetry.queue]): at most [max],
 * the oldest dropped first when more come, so a server that does not answer
 * costs no more than that. Thread-safe.
 */
class EventQueue(private val max: Int = 200) {
    private val events = ArrayDeque<TelemetryEvent>()

    /** Adds [event]; true when it is the first since the last [drain] - the
     *  caller schedules the batch then. */
    @Synchronized
    fun add(event: TelemetryEvent): Boolean {
        val first = events.isEmpty()
        if (events.size >= max) events.removeFirst()
        events.addLast(event)
        return first
    }

    /** Everything queued, in order, and the queue empty. */
    @Synchronized
    fun drain(): List<TelemetryEvent> = events.toList().also { events.clear() }
}
