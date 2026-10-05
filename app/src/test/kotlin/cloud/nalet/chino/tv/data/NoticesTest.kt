package cloud.nalet.chino.tv.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Reading the signed-in person's notices as chino-api answers them, and the
 * list after each change: zaentrum-portal's src/lib/notices.test.ts, on a TV
 * — which follows no link and opens an itemId on its detail screen.
 */
class NoticesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = rfc3339Millis("2026-10-05T08:00:00Z")!!

    private fun notice(
        id: String = "00000001-0000-4000-8000-000000000001",
        readAt: String? = null,
        itemId: String = "",
        addon: String = "example",
        addonTitle: String = "Example",
    ) = Notice(
        id = id,
        addon = addon,
        addonTitle = addonTitle,
        addonIcon = "puzzle",
        title = "Your title is ready",
        body = "It is in your library now.",
        link = "",
        itemId = itemId,
        createdAt = "2026-10-05T07:58:00Z",
        readAt = readAt,
    )

    private fun list(json: String) = noticeListOf(Json.parseToJsonElement(json))

    @Test
    fun `a list is read defensively`() {
        val got = list(
            """{"notices":[
                 {"id":"00000001-0000-4000-8000-000000000001","addon":"example","addonTitle":"Example",
                  "addonIcon":"puzzle","title":"Your title is ready","body":"It is in your library now.",
                  "link":"","itemId":"m1","createdAt":"2026-10-05T07:58:00Z","readAt":null},
                 {"id":"x"},
                 {"title":"no id"},
                 null,
                 "a string",
                 {"id":"y","title":"bare","readAt":"2026-10-05T07:59:00Z","link":7}
               ],"unread":1,"available":true}""",
        )
        assertEquals(listOf("00000001-0000-4000-8000-000000000001", "y"), got.notices.map { it.id })
        assertEquals("m1", got.notices[0].itemId)
        assertTrue(got.notices[0].isUnread)
        assertEquals("", got.notices[1].link)
        assertEquals("2026-10-05T07:59:00Z", got.notices[1].readAt)
        assertEquals("", got.notices[1].addonTitle)
        assertEquals(1, got.unread)
        assertTrue(got.available)
    }

    @Test
    fun `the unread count is never below the unread notices listed`() {
        val two = """[{"id":"a","title":"A","readAt":null},{"id":"b","title":"B"}]"""
        assertEquals(2, list("""{"notices":$two,"unread":0,"available":true}""").unread)
        assertEquals(2, list("""{"notices":$two,"unread":-3,"available":true}""").unread)
        assertEquals(2, list("""{"notices":$two,"unread":"5","available":true}""").unread)
        // More unread than listed: some are not on the list, the count is believed.
        assertEquals(7, list("""{"notices":[],"unread":7,"available":true}""").unread)
    }

    @Test
    fun `available false, or no answer to speak of, is not available`() {
        assertEquals(NoticeList(), list("""{"notices":[],"unread":0,"available":false}"""))
        assertFalse(list("""{"notices":[],"unread":0}""").available)
        assertFalse(list("""{"notices":[],"unread":0,"available":"true"}""").available)
        assertEquals(NoticeList(), noticeListOf(null))
        assertEquals(NoticeList(), list("""[]"""))
        assertEquals(NoticeList(available = true), list("""{"notices":"no","available":true}"""))
    }

    @Test
    fun `the bell says how many are unread`() {
        assertEquals("", badgeText(0))
        assertEquals("", badgeText(-1))
        assertEquals("1", badgeText(1))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
        assertEquals("Notices", bellLabel(0))
        assertEquals("Notices, 3 unread", bellLabel(3))
    }

    @Test
    fun `a notice says whom it is from and when`() {
        assertEquals("Example", fromText(notice()))
        assertEquals("example", fromText(notice(addonTitle = "  ")))
        assertEquals("An addon", fromText(notice(addonTitle = "", addon = "")))
        assertEquals("now", ageText("2026-10-05T07:59:30Z", now, utc))
        assertEquals("5m", ageText("2026-10-05T07:55:00Z", now, utc))
        assertEquals("3h", ageText("2026-10-05T05:00:00Z", now, utc))
        assertEquals("2d", ageText("2026-10-03T08:00:00Z", now, utc))
        assertEquals("Sep 12", ageText("2026-09-12T08:00:00Z", now, utc))
        // The date is the viewer's: late on the 12th in UTC is the 13th in Tokyo.
        assertEquals("Sep 13", ageText("2026-09-12T23:30:00Z", now, TimeZone.getTimeZone("Asia/Tokyo")))
        // A clock a little ahead of the server's is now, not "-1m".
        assertEquals("now", ageText("2026-10-05T08:00:40Z", now, utc))
        assertEquals("", ageText("not a time", now, utc))
        assertEquals("", ageText("", now, utc))
    }

    @Test
    fun `RFC 3339 by hand - as portal-api and Go write it, offsets and all`() {
        assertEquals(1_791_187_080_000L, rfc3339Millis("2026-10-05T07:58:00Z"))
        assertEquals(1_791_187_080_123L, rfc3339Millis("2026-10-05T07:58:00.123456789Z"))
        assertEquals(1_791_187_080_500L, rfc3339Millis("2026-10-05T07:58:00.5Z"))
        assertEquals(rfc3339Millis("2026-10-05T07:58:00Z"), rfc3339Millis("2026-10-05T09:58:00+02:00"))
        assertEquals(rfc3339Millis("2026-10-05T07:58:00Z"), rfc3339Millis("2026-10-04T22:58:00-09:00"))
        assertEquals(0L, rfc3339Millis("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_000L, rfc3339Millis("2000-02-29T00:00:00Z"))
        for (bad in listOf("", "2026-10-05", "2026-10-05T07:58:00", "2026-13-05T07:58:00Z", "2026-10-05T24:00:00Z", "yesterday")) {
            assertNull(bad, rfc3339Millis(bad))
        }
    }

    @Test
    fun `plain text - a body keeps its line breaks, a title is one line`() {
        assertEquals("It is ready.\nEnjoy.", plainText("It is ready.\r\nEnjoy.", lines = true))
        assertEquals("Ready now", plainText("Ready\nnow", lines = false))
        // No control characters, nothing that turns the text around.
        assertEquals("abc", plainText("a\u0000b\u0007c", lines = true))
        assertEquals("evil.exe", plainText("‮evil‬.exe", lines = true))
        assertEquals("ab", plainText("a⁦b⁩", lines = true))
        assertEquals("<b>bold</b> & co", plainText("<b>bold</b> & co", lines = true))
        assertEquals("x", plainText("  x \n", lines = true))
    }

    @Test
    fun `an itemId opens a title only when it is one`() {
        assertEquals("m1", noticeItemId(notice(itemId = "m1")))
        assertEquals("tt0123456:s1.e2_x-y", noticeItemId(notice(itemId = " tt0123456:s1.e2_x-y ")))
        for (bad in listOf("", "..", ".x", "-x", "a/b", "a b", "a?b", "../items", "x".repeat(129))) {
            assertNull(bad, noticeItemId(notice(itemId = bad)))
        }
        assertEquals("x".repeat(128), noticeItemId(notice(itemId = "x".repeat(128))))
    }

    @Test
    fun `a change is sent only for an id chino-api forwards`() {
        assertTrue(isNoticeId("3f2b9c1e-0b7a-4c55-9d1e-2a4f6b8c0d1e"))
        for (bad in listOf("", "a/b", "a.b", "../x", "x".repeat(65), "a b")) {
            assertFalse(bad, isNoticeId(bad))
        }
    }

    @Test
    fun `reading, reading all and deleting keep the count`() {
        val list = NoticeList(
            notices = listOf(notice(id = "a"), notice(id = "b"), notice(id = "c", readAt = "2026-10-05T07:00:00Z")),
            unread = 2,
            available = true,
        )
        val read = markRead(list, "a", "2026-10-05T08:00:00Z")
        assertEquals(1, read.unread)
        assertEquals("2026-10-05T08:00:00Z", read.notices[0].readAt)
        assertNull("the list it was given stays as it was", list.notices[0].readAt)
        assertEquals("reading again changes nothing", 1, markRead(read, "a", "later").unread)
        assertEquals("2026-10-05T07:00:00Z", markRead(read, "c", "later").notices[2].readAt)
        assertEquals(2, markRead(list, "none", "later").unread)
        val all = markAllRead(list, "now")
        assertEquals(0, all.unread)
        assertTrue(all.notices.none { it.isUnread })
        assertEquals("2026-10-05T07:00:00Z", all.notices[2].readAt)
        assertEquals(listOf("a", "c"), removeNotice(list, "b").notices.map { it.id })
        assertEquals(1, removeNotice(list, "b").unread)
        assertEquals("a read one takes nothing off the count", 2, removeNotice(list, "c").unread)
        assertEquals(0, removeNotice(NoticeList(listOf(notice(id = "a")), unread = 0), "a").unread)
        assertTrue(removeNotice(list, "b").available)
    }
}
