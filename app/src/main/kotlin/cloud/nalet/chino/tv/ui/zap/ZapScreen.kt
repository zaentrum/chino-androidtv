package cloud.nalet.chino.tv.ui.zap

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.ChinoTvApp
import coil.compose.AsyncImage
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.BookmarkCheck
import com.composables.icons.lucide.Lucide

/** How long the channel-info overlay lingers before fading (old-TV info-bar feel). */
private const val OVERLAY_LINGER_MS = 60_000L

/** Auto-skip unplayable channels (e.g. a 404 master.m3u8 / no packaged asset),
 *  but stop after this many in a row so a bad pool can't spin forever. */
private const val MAX_DEAD_CHANNELS_IN_A_ROW = 6

/**
 * Full-screen channel-surf ("Zap") experience — TV only. A single reused
 * ExoPlayer plays a random scene of each card WITH SOUND (channel-surf, not a
 * silent teaser); DPAD-DOWN or CH+ flips to the next channel (and hides the
 * info), CH- goes back a channel, DPAD-UP brings the info overlay back, LEFT
 * toggles mute, CENTER expands into the real player at the current scene, BACK
 * returns home. The info overlay auto-fades after ~60s.
 */
@Composable
fun ZapScreen(
    viewModel: ZapViewModel,
    onExpand: (itemId: String, resumeSec: Int) -> Unit,
    onHome: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val savedItems by viewModel.savedItems.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = remember { (context.applicationContext as ChinoTvApp).container }

    // One long-lived player reused across every channel — swapping the media
    // source (NOT rebuilding the player) avoids decoder churn while surfing.
    // Plays with sound by default (channel-surf); LEFT toggles mute.
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 1f
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    // Info overlay + dead-channel guard declared above the listener so it can drive them.
    var overlayVisible by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    // Cold-start artwork gate: the item's backdrop fills the screen UNDER the
    // PlayerView until the decoder renders its first frame (web's ZapCard
    // cold-start). Reset on every channel change so the next channel shows its
    // own backdrop while it spins up.
    var firstFrameRendered by remember { mutableStateOf(false) }
    val consecutiveErrors = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) consecutiveErrors.intValue = 0
                if (playbackState == Player.STATE_ENDED) viewModel.onComplete()
            }
            override fun onRenderedFirstFrame() {
                // Decoder produced its first frame — drop the cold-start
                // backdrop so the live video shows through.
                firstFrameRendered = true
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Dead channel (e.g. master.m3u8 404 — no playable asset). Auto-skip
                // to the next, bounded so a run of unplayable items can't loop forever.
                if (consecutiveErrors.intValue < MAX_DEAD_CHANNELS_IN_A_ROW) {
                    consecutiveErrors.intValue += 1
                    overlayVisible = false
                    viewModel.zapNext()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val active = state as? ZapUiState.Active

    // Swap the source + client-side random-seek whenever the channel changes.
    LaunchedEffect(active?.masterUrl) {
        val a = active ?: return@LaunchedEffect
        // New channel cold-starts — show its backdrop until the first frame
        // of THIS channel renders.
        firstFrameRendered = false
        // Read THROUGH the shared Zap cache the prefetcher warmed — when the
        // upcoming card's init + seek-window segments are already on disk this
        // starts instantly with no network round-trip; misses fall through to
        // the network and get cached for next time. Same per-device variant the
        // master URL's caps resolve to, so no quality is hardcoded.
        val src = HlsMediaSource.Factory(
            ZapCache.dataSourceFactory(context, container.streamHttpClient, "chino-tv/0.1 (Android)"),
        ).createMediaSource(
            MediaItem.Builder().setUri(a.masterUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
        )
        player.setMediaSource(src)
        if (a.seekSec > 0) player.seekTo(a.seekSec * 1000L) // start mid-scene (before prepare)
        player.volume = if (muted) 0f else 1f
        player.prepare()
    }

    // Apply mute toggles live (LEFT) without restarting the current channel.
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    // Info overlay: shown on land + on UP, hidden on channel-change (DOWN),
    // and auto-faded after OVERLAY_LINGER_MS while visible. (declared above)
    LaunchedEffect(overlayVisible, active?.index) {
        if (overlayVisible) {
            kotlinx.coroutines.delay(OVERLAY_LINGER_MS)
            overlayVisible = false
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    BackHandler { onHome() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || e.nativeKeyEvent.repeatCount != 0) return@onKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    // Next channel — DPAD-down (doom-scroll) or CH+ on the TV remote.
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_CHANNEL_UP -> {
                        overlayVisible = false
                        viewModel.zapNext()
                        true
                    }
                    // Previous channel — CH- on the TV remote (history-backed).
                    android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        overlayVisible = false
                        viewModel.zapPrev()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        overlayVisible = true
                        true
                    }
                    // Toggle sound — surf plays with audio by default.
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        muted = !muted
                        true
                    }
                    // Save the current channel to the watchlist (web + mobile
                    // parity). RIGHT is otherwise unused on this surface, so
                    // it's the DPAD-reachable save without colliding with the
                    // channel-surf keys (DOWN/UP/CH±/LEFT/CENTER). Also surface
                    // the info overlay so the bookmark state is visible.
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        overlayVisible = true
                        viewModel.onSaveToggle()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        active?.let { a ->
                            val posSec = (player.currentPosition / 1000L).toInt()
                            onExpand(a.item.id, viewModel.onExpand(posSec))
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        when (state) {
            is ZapUiState.Loading -> ZapMessage("Tuning in…")
            is ZapUiState.Empty -> ZapMessage("Nothing left to zap. Come back later.")
            is ZapUiState.Active -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            keepScreenOn = true
                            this.player = player
                        }
                    },
                )
                // Cold-start backdrop: full-screen artwork layered OVER the
                // PlayerView (so the not-yet-rendered black surface is hidden)
                // until the decoder's first frame fires onRenderedFirstFrame.
                // Fades out so the cut to live video isn't jarring. Backdrop
                // first, poster as the error fallback (portrait items).
                val a = active!!
                AnimatedVisibility(
                    visible = !firstFrameRendered,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AsyncImage(
                        model = a.backdropUrl,
                        fallback = null,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                    )
                }
                AnimatedVisibility(
                    visible = overlayVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomStart),
                ) {
                    ChannelInfoOverlay(
                        item = a.item,
                        muted = muted,
                        saved = a.item.id in savedItems,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelInfoOverlay(
    item: cloud.nalet.chino.tv.data.model.Item,
    muted: Boolean,
    saved: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.6f to Color(0xCC000000),
                    1f to Color(0xF2000000),
                ),
            )
            .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 56.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                SaveChip(saved = saved)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item.year?.let { Text(it.toString(), color = Color(0xFFC9D1D9), fontSize = 18.sp) }
                item.rating?.let {
                    Text("★ ${((it * 10).toInt() / 10.0)}", color = Color(0xFF58A6FF), fontSize = 18.sp)
                }
                item.genres.take(3).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), color = Color(0xFF8B949E), fontSize = 16.sp)
                }
            }
            item.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = Color(0xFFC9D1D9),
                    fontSize = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.6f),
                )
            }
            Text(
                text = "OK · Watch from here     ▼ / CH+ Next     CH- Back     ▲ Info     ◀ ${if (muted) "Unmute" else "Mute"}     ▶ ${if (saved) "Saved" else "Save"}",
                color = Color(0xFF8B949E),
                fontSize = 14.sp,
            )
        }
    }
}

/** Save-state pill in the channel-info overlay. Bookmark when unsaved,
 *  BookmarkCheck (emerald) when in the watchlist. DPAD-RIGHT toggles it (see
 *  the root key handler); this just reflects the current membership so the
 *  surf keys stay unblocked. Mirrors web/mobile's Zap save affordance. */
@Composable
private fun SaveChip(saved: Boolean) {
    Row(
        modifier = Modifier
            .background(
                color = if (saved) Color(0xFF2EA043).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (saved) Lucide.BookmarkCheck else Lucide.Bookmark,
            contentDescription = if (saved) "Saved to watchlist" else "Save to watchlist",
            tint = if (saved) Color(0xFF3FB950) else Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = if (saved) "Saved" else "Save",
            color = if (saved) Color(0xFF3FB950) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ZapMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}
