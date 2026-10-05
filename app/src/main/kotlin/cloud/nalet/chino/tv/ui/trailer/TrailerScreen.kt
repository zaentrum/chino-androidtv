package cloud.nalet.chino.tv.ui.trailer

import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.ChinoTvApp
import cloud.nalet.chino.tv.KeyEventBus
import cloud.nalet.chino.tv.data.model.Trailer
import cloud.nalet.chino.tv.ui.player.StreamLoadErrorPolicy
import cloud.nalet.chino.tv.ui.player.streamDataSourceFactory
import cloud.nalet.chino.tv.ui.player.streamPlayerBuilder
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Youtube

/**
 * A title's trailer from this server, full screen: it plays from the start
 * with sound on the stream player (StreamPlayer.kt) under PlayerView's own
 * controller, which the remote drives — CENTER shows it and presses its
 * buttons, LEFT/RIGHT move between them and scrub the time bar, the media
 * keys play, pause and skip. It closes at the end, and BACK closes it as any
 * screen. Nothing here writes progress or watched: a trailer leaves Continue
 * Watching alone. A trailer that is not there (404) says so and offers the
 * title's trailer link when it has one.
 */
@Composable
fun TrailerScreen(viewModel: TrailerViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            TrailerUiState.Loading -> Message("Loading…")
            is TrailerUiState.Ready -> TrailerPlayback(
                ready = s,
                onStarted = viewModel::reportStarted,
                onEnded = onClose,
                onNotFound = viewModel::notFound,
                onFailed = viewModel::failed,
            )
            is TrailerUiState.NotAvailable -> NotAvailable(
                link = s.link,
                onOpenLink = { link ->
                    viewModel.reportLinkLaunch()
                    launchTrailerLink(context, link)
                },
            )
            is TrailerUiState.Failed -> Failed(message = s.message, onRetry = viewModel::load)
        }
    }
}

@Composable
private fun TrailerPlayback(
    ready: TrailerUiState.Ready,
    onStarted: () -> Unit,
    onEnded: () -> Unit,
    onNotFound: () -> Unit,
    onFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    val started by rememberUpdatedState(onStarted)
    val ended by rememberUpdatedState(onEnded)
    val notFound by rememberUpdatedState(onNotFound)
    val failed by rememberUpdatedState(onFailed)
    val player = remember(ready.masterUrl) {
        val container = (context.applicationContext as ChinoTvApp).container
        // The full player's data source and retry rules: a segment's 404 is
        // retried, the master's fails at once.
        val sources = HlsMediaSource.Factory(streamDataSourceFactory(container))
            .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy())
        streamPlayerBuilder(context, sources).build().apply {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(ready.masterUrl)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build(),
            )
            prepare()
            // From 0:00, with sound.
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) started()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended()
            }

            override fun onPlayerError(error: PlaybackException) {
                val http = error.cause as? HttpDataSource.InvalidResponseCodeException
                if (http?.responseCode == 404 && http.dataSpec.uri.toString().contains(".m3u8")) {
                    notFound()
                } else {
                    failed("Playback failed: ${error.errorCodeName}")
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // The remote reaches the controller wherever focus is: while nothing in
    // the PlayerView holds it (as the screen opens), the keys go to the view
    // itself, which shows the controller and so focuses its play/pause
    // button; from then on they reach it as any focused view's. BACK is never
    // taken: it closes the screen.
    val playerView = remember { arrayOfNulls<PlayerView>(1) }
    DisposableEffect(Unit) {
        val handler: (KeyEvent) -> Boolean = handler@{ e ->
            val view = playerView[0] ?: return@handler false
            if (e.keyCode == KeyEvent.KEYCODE_BACK || view.hasFocus()) return@handler false
            view.dispatchKeyEvent(e)
        }
        KeyEventBus.register(handler)
        onDispose { KeyEventBus.clear(handler) }
    }

    var controllerVisible by remember { mutableStateOf(true) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                // One video: nothing before or after it.
                setShowPreviousButton(false)
                setShowNextButton(false)
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { visibility ->
                        controllerVisible = visibility == View.VISIBLE
                    },
                )
                // No screensaver over a playing trailer.
                keepScreenOn = true
                playerView[0] = this
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
    )
    // The title over the top while the controller shows, as the full
    // player's chrome has it.
    AnimatedVisibility(visible = controllerVisible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 48.dp),
        ) {
            Column {
                Text(
                    text = ready.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = ready.label, style = MaterialTheme.typography.bodyMedium, color = ChinoMuted)
            }
        }
    }
}

/** "Trailer not available", and the title's trailer link when it has one. */
@Composable
private fun NotAvailable(link: Trailer?, onOpenLink: (Trailer) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(link) { if (link != null) runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Trailer not available",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        if (link != null) {
            Button(
                onClick = { onOpenLink(link) },
                modifier = Modifier.focusRequester(focus),
                shape = ButtonDefaults.shape(shape = RectangleShape),
            ) {
                Icon(Lucide.Youtube, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = trailerLinkLabel(link))
            }
        }
    }
}

/** The trailer did not load or play: why, and Try again. */
@Composable
private fun Failed(message: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "The trailer didn't play",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = ChinoMuted)
        Button(
            onClick = onRetry,
            modifier = Modifier.focusRequester(focus),
            shape = ButtonDefaults.shape(shape = RectangleShape),
        ) {
            Text(text = "Try again")
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
