package cloud.nalet.chino.tv.data.telemetry

import cloud.nalet.chino.tv.data.api.TelemetryEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's quality reports wait in a bounded queue and go together. */
class EventQueueTest {
    private fun event(n: Int) = TelemetryEvent(ts = n.toLong(), kind = "bandwidth", itemId = "item", payload = mapOf("bps" to "$n"))

    @Test
    fun `the first event after a batch schedules the next one, the others ride along`() {
        val queue = EventQueue()
        assertTrue(queue.add(event(1)))
        assertFalse(queue.add(event(2)))
        assertFalse(queue.add(event(3)))
        assertEquals(listOf(1L, 2L, 3L), queue.drain().map { it.ts })
        assertTrue(queue.drain().isEmpty())
        assertTrue(queue.add(event(4)))
    }

    @Test
    fun `past its bound the oldest go`() {
        val queue = EventQueue(max = 3)
        (1..5).forEach { queue.add(event(it)) }
        assertEquals(listOf(3L, 4L, 5L), queue.drain().map { it.ts })
    }

    @Test
    fun `the TV names its client as chino-api counts a TV`() {
        // chino-api's deviceClass: a client naming "tv" is a TV.
        assertTrue("tv" in TELEMETRY_CLIENT)
        assertEquals(30_000L, QUEUE_FLUSH_MS)
    }
}
