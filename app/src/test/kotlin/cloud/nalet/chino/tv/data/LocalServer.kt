package cloud.nalet.chino.tv.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * An HTTP server on the loopback for a test: each request answered by
 * [answer] from its method and path (with the query), as a status and a JSON
 * body; null is a 404. Every request is noted in [seen] as "METHOD path".
 */
internal class LocalServer(private val answer: (method: String, path: String) -> Pair<Int, String>?) {
    val seen = CopyOnWriteArrayList<String>()

    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** Where it answers: http://127.0.0.1:port, no slash at the end. */
    val origin = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val conn = runCatching { socket.accept() }.getOrNull() ?: break
                conn.use { c ->
                    val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                    val (method, path) = (reader.readLine() ?: return@use).split(' ').let { it[0] to it[1] }
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            length = line.substringAfter(':').trim().toInt()
                        }
                    }
                    if (length > 0) reader.read(CharArray(length), 0, length)
                    seen += "$method $path"
                    val (status, body) = answer(method, path) ?: (404 to "{}")
                    c.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 $status X\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                        )
                        flush()
                    }
                }
            }
        }
    }

    fun stop() = socket.close()
}
