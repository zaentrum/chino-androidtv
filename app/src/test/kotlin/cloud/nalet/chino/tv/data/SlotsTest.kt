package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What an addon's slot row may make the TV do: chino-web's
 * lib/extensions.test.ts, on a TV — which is on no page, so a link is a path
 * or an absolute URL on the server's origin, never one relative to a page.
 */
class SlotsTest {
    private val origin = serverOrigin("https://media.example/api/")!!

    private fun s(value: String) = JsonPrimitive(value)
    private fun link(raw: String, vars: Map<String, String> = emptyMap()) = slotLinkUrl(s(raw), origin, vars)
    private fun action(raw: String, method: JsonElement? = s("POST"), vars: Map<String, String> = emptyMap()) =
        slotActionUrl(s(raw), method, origin, vars)

    @Test
    fun `the server's origin is its API base without the path`() {
        assertEquals("https://media.example/", origin.toString())
        assertEquals("http://media.example.org:8080/", serverOrigin("http://media.example.org:8080/api")?.toString())
        assertNull(serverOrigin(""))
        assertNull(serverOrigin("media.example"))
    }

    @Test
    fun `a link on the server itself - a path, or absolute on the same origin`() {
        assertEquals("https://media.example/portal/app/sample?q=zz", link("/portal/app/sample?q=zz"))
        assertEquals("https://media.example/portal/app/sample", link("https://media.example/portal/app/sample"))
        // The default port is the same origin.
        assertEquals("https://media.example/portal/", link("https://media.example:443/portal/"))
        assertEquals("https://media.example/x", link("  /x  "))
        assertEquals("https://media.example/x", link("HTTPS://MEDIA.EXAMPLE/x"))
    }

    @Test
    fun `an address relative to a page renders nothing - the TV is on no page`() {
        for (raw in listOf("?q=other", "portal/app/sample", "#/x", "sample")) {
            assertNull(raw, link(raw))
        }
    }

    @Test
    fun `a script URL renders nothing, however it is spelled`() {
        for (raw in listOf(
            "javascript:void(document.title=\"x\")",
            "JavaScript:alert(1)",
            " javascript:alert(1)",
            "java\tscript:alert(1)",
            "java\nscript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
        )) {
            assertNull(raw, link(raw))
        }
    }

    @Test
    fun `another origin renders nothing - another host, scheme or port, protocol-relative, credentials`() {
        for (raw in listOf(
            "https://addon.invalid/landing",
            "//addon.invalid/landing",
            "\\\\addon.invalid/landing",
            "/\\addon.invalid/landing",
            "https://media.example.addon.invalid/landing",
            "http://media.example/portal/",
            "https://media.example:8443/portal/",
            "https://user:secret@media.example/portal/",
            "ftp://media.example/file",
            "mailto:someone@media.example",
        )) {
            assertNull(raw, link(raw))
        }
    }

    @Test
    fun `no URL, an empty one, or not a string at all - nothing`() {
        for (raw in listOf(null, JsonNull, s(""), s("   "), JsonPrimitive(42), JsonObject(emptyMap()), JsonArray(listOf(s("/x"))))) {
            assertNull(raw.toString(), slotLinkUrl(raw, origin))
        }
    }

    @Test
    fun `{q} is substituted encoded, before the URL is checked`() {
        assertEquals(
            "https://media.example/portal/app/sample?q=zz%26qq%3D1%20%3Cx%3E%20%22y%22%23w",
            link("/portal/app/sample?q={q}", mapOf("q" to "zz&qq=1 <x> \"y\"#w")),
        )
        // Whatever the query holds, it arrives as it was typed.
        val typed = "l'été & co / 100% ?"
        assertEquals(typed, link("/portal/app/sample?q={q}", mapOf("q" to typed))?.toHttpUrl()?.queryParameter("q"))
        // A query cannot turn a path into a script or another host.
        assertNull(link("{q}", mapOf("q" to "javascript:alert(1)")))
        assertNull(link("https://{q}/x", mapOf("q" to "addon.invalid")))
        assertEquals("https://media.example/%2F%2Faddon.invalid", link("/{q}", mapOf("q" to "//addon.invalid")))
        assertEquals("/x?a=1%202&b=", substitute("/x?a={a}&b={b}", mapOf("a" to "1 2")))
        // Only the names given: {constructor} is not one.
        assertEquals("/x?c=", substitute("/x?c={constructor}", emptyMap()))
        assertEquals("a-_.!~*'()%C3%A9%F0%9F%8E%AC", encodeUriComponent("a-_.!~*'()é🎬"))
    }

    @Test
    fun `an action POSTs only to the portal's app proxy on this origin`() {
        assertEquals(
            "https://media.example/api/portal/apps/sample/collect?q=a%20b",
            action("/api/portal/apps/sample/collect?q={q}", vars = mapOf("q" to "a b")),
        )
        assertEquals("https://media.example/api/portal/apps/sample", action("https://media.example/api/portal/apps/sample", s("")))
        // No method means POST; the case does not matter.
        assertEquals("https://media.example/api/portal/apps/sample/x", action("/api/portal/apps/sample/x", null))
        assertEquals("https://media.example/api/portal/apps/sample/x", action("/api/portal/apps/sample/x", JsonNull))
        assertEquals("https://media.example/api/portal/apps/sample/x", action("/api/portal/apps/sample/x", s("post")))
    }

    @Test
    fun `an action with any other method renders nothing`() {
        for (method in listOf(s("GET"), s("DELETE"), s("PUT"), s("PATCH"), s("post ; DELETE"), JsonPrimitive(7))) {
            assertNull(method.toString(), action("/api/portal/apps/sample/x", method))
        }
    }

    @Test
    fun `the bearer never leaves the origin, nor goes to chino-api or the portal itself`() {
        for (raw in listOf(
            "https://addon-sim.invalid/collect",
            "//addon-sim.invalid/api/portal/apps/sample/x",
            "http://media.example/api/portal/apps/sample/x",
            "/api/v1/me/watchlists",
            "/api/portal/addons/sample",
            "/portal/app/sample",
            "/api/portal/apps/",
            "/api/portal/apps//x",
            // Climbing out of the proxy, plainly or encoded, is resolved before the check.
            "/api/portal/apps/sample/../../addons/sample",
            "/api/portal/apps/sample/%2e%2e/%2e%2e/addons",
            "/api/portal/apps/sample/..%2f..%2faddons",
            "/api/portal/apps/sample%5c..%5c..%5caddons",
            "/api/portal/apps/sample\\..\\..\\addons",
            "javascript:fetch(\"/api/portal/apps/sample/x\")",
        )) {
            assertNull(raw, action(raw))
        }
    }

    @Test
    fun `only links and actions are kinds`() {
        assertEquals(SlotKind.Link, slotKind(s("link")))
        assertEquals(SlotKind.Action, slotKind(s(" Action ")))
        for (raw in listOf(s("iframe"), s("script"), s(""), null, JsonNull, JsonPrimitive(1))) {
            assertNull(raw.toString(), slotKind(raw))
        }
    }

    @Test
    fun `slotButtons - what a review would see rendered, and what is left of it`() {
        val rows = Json.parseToJsonElement(
            """[
              {"key":"sim.kebab","kind":"link","label":"Open Sample","icon":"list-video","url":"/portal/app/sim?q={q}","enabled":true},
              {"key":"sim.js","kind":"link","label":"javascript: link","icon":"zap","url":"javascript:void(document.title=\"x\")","enabled":true},
              {"key":"sim.offorigin","kind":"action","label":"action to another origin","url":"https://addon-sim.invalid/collect?q={q}","method":"DELETE","enabled":true},
              {"key":"sim.iframe","kind":"iframe","label":"kind iframe","url":"/api/portal/apps/sim/frame","enabled":true},
              {"key":"sim.offsite","kind":"link","label":"off-site link","url":"https://addon-sim.invalid/landing","enabled":true},
              {"key":"sim.action","kind":"action","label":"Notify Me","icon":"bell","url":"/api/portal/apps/sim/notify?q={q}","method":"POST","enabled":true},
              {"key":"sim.off","kind":"link","label":"disabled","url":"/portal/app/sim","enabled":false},
              {"key":"sim.nolabel","kind":"link","label":"  ","url":"/portal/app/sim","enabled":true},
              {"key":"sim.numlabel","kind":"link","label":7,"url":"/portal/app/sim","enabled":true},
              {"key":"sim.strenabled","kind":"link","label":"Enabled as a string","url":"/portal/app/sim","enabled":"true"},
              {"kind":"link","label":"No Key","url":"/portal/app/sim","enabled":true},
              null,
              "not a row"
            ]""",
        )
        assertEquals(
            listOf(
                SlotButton("sim.kebab", SlotKind.Link, "Open Sample", "https://media.example/portal/app/sim?q=zz%20top"),
                SlotButton("sim.action", SlotKind.Action, "Notify Me", "https://media.example/api/portal/apps/sim/notify?q=zz%20top"),
                SlotButton("row-10", SlotKind.Link, "No Key", "https://media.example/portal/app/sim"),
            ),
            slotButtons(rows, origin, mapOf("q" to "zz top")),
        )
        assertEquals(emptyList<SlotButton>(), slotButtons(Json.parseToJsonElement("""{"not":"an array"}"""), origin))
        assertEquals(emptyList<SlotButton>(), slotButtons(null, origin))
    }

    @Test
    fun `the slot is read with GET v1 extensions, the bearer in the header`() {
        val seen = mutableListOf<Request>()
        val api = RetrofitFactory.retrofit(
            "https://media.example/api/",
            RetrofitFactory.httpClient(tokenProvider = { "tok-1" }, forceRefresh = { null })
                .addInterceptor { chain ->
                    seen += chain.request()
                    answer(chain.request(), 200, """[{"key":"k","kind":"link","label":"L","url":"/portal/app/x","enabled":true}]""")
                }
                .build(),
        ).create(ChinoApi::class.java)

        val rows = runBlocking { api.extensions(SLOT_SEARCH_EMPTY) }
        assertEquals(listOf("k"), slotButtons(rows, origin).map { it.key })
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("https://media.example/api/v1/extensions?slot=search.empty", request.url.toString())
        assertEquals("Bearer tok-1", request.header("Authorization"))
    }

    /** An addon behind the portal's proxy, answering in place of the network. */
    private class FakeAddon(private val status: Int, private val unreachable: Boolean = false) {
        val requests = mutableListOf<Request>()
        val actions = SlotActions(
            RetrofitFactory.httpClient(tokenProvider = { "tok-1" }, forceRefresh = { null })
                .addInterceptor { chain ->
                    requests += chain.request()
                    if (unreachable) throw IOException("connect timed out")
                    answer(chain.request(), status, "")
                }
                .build(),
        )

        fun send(url: String = "https://media.example/api/portal/apps/sim/notify?q=zz") = runBlocking { actions.send(url) }
    }

    @Test
    fun `an action is a POST with an empty body and the bearer`() {
        val addon = FakeAddon(204)
        assertTrue(addon.send())
        val request = addon.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://media.example/api/portal/apps/sim/notify?q=zz", request.url.toString())
        assertEquals("Bearer tok-1", request.header("Authorization"))
        assertEquals(0L, Buffer().also { request.body!!.writeTo(it) }.size)
    }

    @Test
    fun `only a 2xx counts as taken - a redirect, a refusal or no answer is not`() {
        assertTrue(FakeAddon(200).send())
        assertTrue(FakeAddon(202).send())
        for (status in listOf(301, 302, 307, 400, 401, 403, 404, 500, 502)) {
            assertFalse("$status", FakeAddon(status).send())
        }
        assertFalse(FakeAddon(200, unreachable = true).send())
    }

    @Test
    fun `the action client follows no redirect`() {
        val client = RetrofitFactory.actionClient(tokenProvider = { null }, forceRefresh = { null })
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    private companion object {
        fun answer(request: Request, status: Int, body: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("fake")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
