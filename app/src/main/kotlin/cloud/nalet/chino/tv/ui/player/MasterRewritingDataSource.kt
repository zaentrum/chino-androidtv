package cloud.nalet.chino.tv.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A data source that hands Media3 chino-stream's master playlists
 * (…/play/master.m3u8) as [rewrite] makes them, and every other request —
 * media playlists, segments, sidecar subtitles — as it comes.
 *
 * The rewrite sits here rather than in an HLS playlist parser because the
 * player builds its source with DefaultMediaSourceFactory, which merges the
 * sidecar subtitles in (PGS among them) but takes no parser for the HLS
 * source it creates.
 */
class MasterRewritingDataSource(
    private val upstream: DataSource,
    private val rewrite: (String) -> String,
) : DataSource {
    private var body: ByteArray? = null
    private var readPosition = 0
    private var bodyUri: Uri? = null
    private var bodyHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.lastPathSegment != MASTER) return upstream.open(dataSpec)
        // Errors (a 404 master among them) reach the player as they are.
        upstream.open(dataSpec)
        val bytes = try {
            bodyUri = upstream.uri
            bodyHeaders = upstream.responseHeaders
            DataSourceUtil.readToEnd(upstream)
        } finally {
            upstream.close()
        }
        val rewritten = rewrite(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        body = rewritten
        readPosition = 0
        return rewritten.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val b = body ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (readPosition >= b.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, b.size - readPosition)
        System.arraycopy(b, readPosition, buffer, offset, n)
        readPosition += n
        return n
    }

    override fun getUri(): Uri? = if (body != null) bodyUri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (body != null) bodyHeaders else upstream.responseHeaders

    override fun close() {
        if (body != null) {
            body = null
            bodyUri = null
            bodyHeaders = emptyMap()
        } else {
            upstream.close()
        }
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val rewrite: (String) -> String,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            MasterRewritingDataSource(upstream.createDataSource(), rewrite)
    }

    private companion object {
        const val MASTER = "master.m3u8"
    }
}
