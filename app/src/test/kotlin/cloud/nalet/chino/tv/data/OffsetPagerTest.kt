package cloud.nalet.chino.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OffsetPagerTest {
    /** A catalog of [size] ids served the way chino-api serves a list:
     *  `items[offset, offset + limit)`, recording every request it gets. */
    private class FakeList(size: Int) {
        var ids: List<String> = (1..size).map { "t$it" }
        val requests = mutableListOf<Pair<Int, Int>>()
        var failNext = false

        suspend fun fetch(offset: Int, limit: Int): List<String> {
            requests += offset to limit
            if (failNext) {
                failNext = false
                throw java.io.IOException("connection reset")
            }
            return ids.drop(offset).take(limit)
        }
    }

    private fun pager(list: FakeList, pageSize: Int = 48) =
        OffsetPager(pageSize = pageSize, keyOf = { it: String -> it }, fetch = list::fetch)

    @Test
    fun `pages past the first 48 by offset until a short page`() = runBlocking {
        val list = FakeList(130)
        val p = pager(list)

        val first = p.next()
        assertEquals(48, first.size)
        assertTrue(p.hasMore)
        val second = p.next()
        assertEquals((49..96).map { "t$it" }, second)
        val third = p.next()
        assertEquals(34, third.size)
        assertFalse(p.hasMore)

        assertEquals(listOf(0 to 48, 48 to 48, 96 to 48), list.requests)
    }

    @Test
    fun `no request once the end is reached`() = runBlocking {
        val list = FakeList(10)
        val p = pager(list)
        assertEquals(10, p.next().size)
        assertFalse(p.hasMore)
        assertEquals(emptyList<String>(), p.next())
        assertEquals(1, list.requests.size)
    }

    @Test
    fun `an exactly full last page costs one empty request`() = runBlocking {
        val list = FakeList(96)
        val p = pager(list)
        p.next()
        p.next()
        assertTrue(p.hasMore)
        assertEquals(emptyList<String>(), p.next())
        assertFalse(p.hasMore)
        assertEquals(listOf(0 to 48, 48 to 48, 96 to 48), list.requests)
    }

    @Test
    fun `a title the offset slip repeats is dropped, and the offset still advances`() = runBlocking {
        val list = FakeList(100)
        val p = pager(list, pageSize = 10)
        assertEquals((1..10).map { "t$it" }, p.next())
        // A title is added at the head (sort=newest): everything moves down
        // one place, so the next page starts with t10, already shown.
        list.ids = listOf("new") + list.ids
        val second = p.next()
        assertEquals((11..19).map { "t$it" }, second)
        // The offset moved by the ten titles the server sent, not the nine kept.
        p.next()
        assertEquals(listOf(0 to 10, 10 to 10, 20 to 10), list.requests)
    }

    @Test
    fun `a page of repeats only is skipped for the next one`() = runBlocking {
        val list = FakeList(30)
        val p = pager(list, pageSize = 10)
        p.next()
        // The whole first page shows up again at offset 10 …
        list.ids = list.ids.take(10) + list.ids
        val next = p.next()
        // … so the pager reads on and hands out what is new.
        assertEquals((11..20).map { "t$it" }, next)
        assertEquals(listOf(0 to 10, 10 to 10, 20 to 10), list.requests)
    }

    @Test
    fun `a failed page is fetched again from the same offset`() = runBlocking {
        val list = FakeList(60)
        val p = pager(list)
        p.next()
        list.failNext = true
        try {
            p.next()
            fail("the failure should reach the caller")
        } catch (_: java.io.IOException) {
        }
        assertTrue(p.hasMore)
        assertEquals(12, p.next().size)
        assertEquals(listOf(0 to 48, 48 to 48, 48 to 48), list.requests)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a page size chino-api would not honour is refused`() {
        OffsetPager(pageSize = 500, keyOf = { it: String -> it }) { _, _ -> emptyList() }
    }
}
