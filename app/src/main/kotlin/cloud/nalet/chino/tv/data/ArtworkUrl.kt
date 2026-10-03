package cloud.nalet.chino.tv.data

import java.net.URLEncoder

/**
 * An artwork path chino-api hands out — a person's `profile_url`
 * ("/api/v1/people/{id}/profile"), as an item's `poster_url` — as a URL an
 * image request can load: resolved against the server's API base [baseUrl]
 * (".../api", as AppContainer.baseUrl holds it) and carrying the stream token
 * as `?stream=` (or `&stream=`), as chino-web's withStreamToken does. An image
 * request sends no bearer; chino-api's artwork routes sit in its stream-token
 * group, and the token — the same for six hours — keeps the URL, and the
 * cached image, stable across OIDC renewals. Null without a path; without a
 * token yet, the URL goes without one.
 */
fun streamArtworkUrl(baseUrl: String, path: String?, streamToken: String?): String? {
    val p = path?.trim().orEmpty()
    if (p.isEmpty()) return null
    val base = baseUrl.trimEnd('/')
    val url = when {
        p.startsWith("http://") || p.startsWith("https://") -> p
        // chino-api writes its own routes from the server root ("/api/v1/…").
        p.startsWith("/api/") -> base + p.removePrefix("/api")
        p.startsWith("/") -> base.removeSuffix("/api") + p
        else -> "$base/$p"
    }
    if (streamToken.isNullOrEmpty()) return url
    val sep = if ('?' in url) '&' else '?'
    // encodeURIComponent, as the web: a space is %20, not the form-style "+".
    val token = URLEncoder.encode(streamToken, "UTF-8").replace("+", "%20")
    return "$url${sep}stream=$token"
}
