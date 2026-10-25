package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.ui.theme.ChinoBg
import cloud.nalet.chino.tv.ui.theme.rememberItemFocus
import cloud.nalet.chino.tv.ui.theme.focusStyle
import cloud.nalet.chino.tv.ui.theme.ItemFocus
import cloud.nalet.chino.tv.ui.theme.FocusKind
import cloud.nalet.chino.tv.ui.theme.ChinoFocus
import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoDim
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.ChinoSurfaceHi
import cloud.nalet.chino.tv.ui.theme.ChinoBorderHi
import cloud.nalet.chino.tv.ui.theme.ChinoBorderControl
import cloud.nalet.chino.tv.ui.theme.ChinoFg
import cloud.nalet.chino.tv.ui.theme.ChinoText
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSignalAmber
import cloud.nalet.chino.tv.ui.theme.ChinoSignalGreen
import cloud.nalet.chino.tv.ui.theme.ChinoSignalRed
import cloud.nalet.chino.tv.ui.theme.paintToScreenEdges
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Captions
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import cloud.nalet.chino.tv.ui.theme.focusFirstOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.api.Segment

/**
 * Custom Compose-for-TV control chrome that replaces ExoPlayer's PlayerControlView.
 * Mirrors chino-web's PlayerPage layout:
 *
 *  Top:    [Back]  Title                              codec • size • profile • q=N
 *  Bottom: [Replay10] [Play/Pause] [Forward10]                [Prev] [Audio] [CC] [HD] [Info] [Next]
 *          00:42 ━━━━━━━●─────────────────────  21:13
 *
 * Auto-hide: 3s while playing, 8s while paused. Any key press wakes the chrome.
 * D-pad model: focus starts on Play; LEFT/RIGHT navigates buttons within a row;
 * UP/DOWN switches between scrubber row and button row; CENTER activates.
 *
 * Scrubber is its own focusable: when it owns focus, DPAD_LEFT/RIGHT does NOT
 * navigate to a sibling — instead it sends a scrubBy(±10s) event and previews
 * the new position. CENTER commits the seek (with web-style "seek-as-you-go"
 * via a debounced 350 ms commit timer, see PlayerScreen wiring).
 */

/** Visual state of a player control: focused / pressed / idle. Used to drive
 *  the focus ring + scale animation across all buttons. */
internal data class ControlVisualState(val focused: Boolean, val enabled: Boolean = true)

/** The player's menus, each opened by its button in the control bar - or
 *  the remote's own subtitles or audio key. */
internal enum class PlayerMenu { Audio, Subtitles, Speed, Quality }

/**
 * Where the focus goes in the player's control bar: to play/pause as the
 * controls come back, and, as a menu closes - a pick, BACK - to the button
 * that opened it, the menu's own: subtitles back on subtitles, never on
 * play/pause, where the next OK would pause the title ([ControlBarFocusEffect]).
 * It outlives the player a quality's pick rebuilds, so the focus comes back
 * to quality there too. The buttons carry its requesters ([button]).
 */
@Stable
internal class ControlBarFocus {
    val playPause = FocusRequester()
    private val buttons = PlayerMenu.entries.associateWith { FocusRequester() }

    /** The requester of [menu]'s button, to put on the button. */
    fun button(menu: PlayerMenu): FocusRequester = buttons.getValue(menu)

    /** The menu open last, until the focus is back on its button. */
    internal var lastOpen: PlayerMenu? = null
}

/**
 * The control bar's focus as its controls show and its menus open and close
 * ([ControlBarFocus]): while [openMenu] is open its rows have the focus; as
 * it closes the focus goes back to its button - or to play/pause where the
 * bar has none (the remote's audio key on a title of one track); as the
 * controls come back, [controlsVisible], to play/pause. An open menu keeps
 * the focus: the chrome coming back as the remote's subtitles key opens the
 * menu over hidden controls leaves it in the menu.
 */
@Composable
internal fun ControlBarFocusEffect(controlsVisible: Boolean, openMenu: PlayerMenu?, focus: ControlBarFocus) {
    LaunchedEffect(controlsVisible, openMenu) {
        if (openMenu != null) {
            focus.lastOpen = openMenu
            return@LaunchedEffect
        }
        if (!controlsVisible) return@LaunchedEffect
        if (focus.lastOpen == null) {
            // Back from hidden: somewhere for the first key to land.
            runCatching { focus.playPause.requestFocus() }
            return@LaunchedEffect
        }
        // A frame for the menu to go. A pick that rebuilds the player - a
        // quality's - takes this screen with it first, and the next one's
        // bar gives the focus back.
        withFrameNanos { }
        val closed = focus.lastOpen ?: return@LaunchedEffect
        focus.lastOpen = null
        focusFirstOf(listOf(focus.button(closed), focus.playPause), frames = 4)
    }
}

/** Bottom-aligned chrome row with skip-back / play-pause / skip-forward at left,
 *  the scrubber in the middle, and the action buttons at right. */
@Composable
internal fun PlayerBottomChrome(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    segments: List<Segment>,
    trickplayCues: List<TrickplayCue>,
    trickplayBaseUrl: String,
    streamToken: String,
    muted: Boolean,
    volume: Float,
    onPlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onScrubberSeek: (positionMs: Long) -> Unit,
    onAudio: (() -> Unit)?,
    onSubtitles: (() -> Unit)?,
    captionsOn: Boolean = false,
    onQuality: (() -> Unit)?,
    onSpeed: () -> Unit,
    speedActive: Boolean = false,
    /** The quality's menu is open: its button marked, as the others' are. */
    qualityActive: Boolean = false,
    /** The audio's menu is open. */
    audioActive: Boolean = false,
    onInfo: () -> Unit,
    onPrevEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    playPauseFocusRequester: FocusRequester,
    onUserInteraction: () -> Unit,
    /** The menus' buttons' requesters: the focus comes back to the button
     *  of a menu that closes ([ControlBarFocus]). */
    menuButton: ((PlayerMenu) -> FocusRequester)? = null,
) {
    val opens: (PlayerMenu) -> Modifier = { m -> menuButton?.let { Modifier.focusRequester(it(m)) } ?: Modifier }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A solid band, out to the screen's edges, the seek row and the
            // buttons on it - never on the picture, never see-through
            // (docs/chino, playback); the controls inside the safe area, with
            // room for a focused one's ring.
            .paintToScreenEdges(ChinoBg, top = false)
            .padding(ChinoFocus.Room),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Scrubber row — focusable bar with time labels.
        ScrubberRow(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            segments = segments,
            trickplayCues = trickplayCues,
            trickplayBaseUrl = trickplayBaseUrl,
            streamToken = streamToken,
            onSeek = onScrubberSeek,
            onUserInteraction = onUserInteraction,
        )
        // Button row — chino-web / chino-mobile parity:
        //   LEFT:  [play/pause] [volume(mute + slider)]
        //   RIGHT: [audio] [subtitles] [speed] [quality] [prev] [next] [info]
        // No ±10s skip buttons (web has none — the scrubber handles seeking;
        // on D-pad the focused scrubber seeks ±10s, and so does LEFT/RIGHT
        // while the chrome is hidden).
        // Controls far enough apart for a focused one's growth and ring.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChinoFocus.ControlGap),
        ) {
            ControlIconButton(
                icon = if (isPlaying) Lucide.Pause else Lucide.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                onClick = { onUserInteraction(); onPlayPause() },
                modifier = Modifier.focusRequester(playPauseFocusRequester),
            )
            VolumeControl(
                muted = muted,
                volume = volume,
                onToggleMute = { onUserInteraction(); onToggleMute() },
                onVolumeChange = { onUserInteraction(); onVolumeChange(it) },
            )
            // Spacer pushes the right-side cluster to the edge — web's
            // `justify-between` bottom bar.
            Spacer(modifier = Modifier.weight(1f))
            if (onAudio != null) {
                ControlIconButton(
                    icon = Lucide.AudioLines,
                    contentDescription = "Audio track",
                    modifier = opens(PlayerMenu.Audio),
                    accent = audioActive,
                    onClick = { onUserInteraction(); onAudio() },
                )
            }
            if (onSubtitles != null) {
                ControlIconButton(
                    icon = Lucide.Captions,
                    contentDescription = "Subtitles",
                    modifier = opens(PlayerMenu.Subtitles),
                    accent = captionsOn,
                    onClick = { onUserInteraction(); onSubtitles() },
                )
            }
            ControlIconButton(
                icon = Lucide.Gauge,
                contentDescription = "Playback speed",
                modifier = opens(PlayerMenu.Speed),
                accent = speedActive,
                onClick = { onUserInteraction(); onSpeed() },
            )
            if (onQuality != null) {
                ControlIconButton(
                    icon = Lucide.Settings2,
                    contentDescription = "Quality",
                    modifier = opens(PlayerMenu.Quality),
                    accent = qualityActive,
                    onClick = { onUserInteraction(); onQuality() },
                )
            }
            if (onPrevEpisode != null) {
                ControlIconButton(
                    icon = Lucide.ChevronLeft,
                    contentDescription = "Previous episode",
                    onClick = { onUserInteraction(); onPrevEpisode() },
                )
            }
            if (onNextEpisode != null) {
                ControlIconButton(
                    icon = Lucide.ChevronRight,
                    contentDescription = "Next episode",
                    onClick = { onUserInteraction(); onNextEpisode() },
                )
            }
            ControlIconButton(
                icon = Lucide.Info,
                contentDescription = "Playback info",
                onClick = { onUserInteraction(); onInfo() },
            )
        }
    }
}

@Composable
internal fun PlayerTopChrome(
    title: String,
    onBack: () -> Unit,
    onUserInteraction: () -> Unit,
    // Pops to the library/home root. Full-screen playback has no persistent nav
    // rail, so the player carries its own explicit Home affordance next to Back
    // (chino-web parity). Null hides the button. Back is unchanged.
    onHome: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The band out to the screen's edges; the controls inside the
            // safe area, with room for a focused one's ring.
            .paintToScreenEdges(
                Brush.verticalGradient(
                    0f to Color(0xCC000000),
                    1f to Color.Transparent,
                ),
                bottom = false,
            )
            .padding(ChinoFocus.Room),
        verticalAlignment = Alignment.CenterVertically,
        // Back and Home far enough apart for a focused one's growth and
        // ring, as the bottom bar's buttons.
        horizontalArrangement = Arrangement.spacedBy(ChinoFocus.ControlGap),
    ) {
        ControlIconButton(
            icon = Lucide.ArrowLeft,
            contentDescription = "Back",
            onClick = { onUserInteraction(); onBack() },
        )
        if (onHome != null) {
            ControlIconButton(
                icon = Lucide.House,
                contentDescription = "Home",
                onClick = { onUserInteraction(); onHome() },
            )
        }
        Text(
            text = title,
            color = ChinoFg,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Scrubber: a focusable bar with playhead position + buffered range.
 *  When focused, DPAD_LEFT/RIGHT seeks ±10 s (the wrapper composable's key
 *  handler converts that into a scrubBy call); CENTER toggles play/pause. */
@Composable
private fun ScrubberRow(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    segments: List<Segment>,
    trickplayCues: List<TrickplayCue>,
    trickplayBaseUrl: String,
    streamToken: String,
    onSeek: (Long) -> Unit,
    onUserInteraction: () -> Unit,
) {
    val focus = rememberItemFocus()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = formatPositionMs(positionMs),
            color = ChinoText,
            fontSize = 14.sp,
            modifier = Modifier.width(64.dp),
        )
        ScrubberBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            segments = segments,
            trickplayCues = trickplayCues,
            trickplayBaseUrl = trickplayBaseUrl,
            streamToken = streamToken,
            focus = focus,
            onFocusChange = { if (it) onUserInteraction() },
            onSeek = { delta ->
                val target = (positionMs + delta).coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
                onSeek(target)
                onUserInteraction()
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatPositionMs(durationMs),
            color = ChinoText,
            fontSize = 14.sp,
            modifier = Modifier.width(64.dp),
        )
    }
}

/** The seek bar's colours, all opaque and all the design system's tokens:
 *  the track, what is loaded ahead, the played part in cloud-blue as the
 *  volume's level, the thumb in fg. */
internal object SeekBarLook {
    val Track: Color = ChinoBorderHi
    val Buffered: Color = ChinoBorderControl
    val Played: Color = ChinoAccent
    val Thumb: Color = ChinoFg

    /** A segment's stripe ahead of the played part, on the loaded part or
     *  on the track - each one a token that stands out from both
     *  (SeekBarLookTest): a recap in fg-2, light on the loaded grey, as it
     *  opens an episode where the buffer covers it at once; an intro in
     *  signal amber; the credits in signal red, near the end and so on the
     *  track for all but their last minutes; an intermission in fg-muted,
     *  the other grey - in a film's middle, far from a recap at an
     *  episode's start - and a chapter or any other kind in signal green.
     *  An episode's recap, intro and credits are never two greys. */
    fun segment(kind: String): Color = when (kind.lowercase()) {
        "intro" -> ChinoSignalAmber
        "recap" -> ChinoText
        "credits" -> ChinoSignalRed
        "intermission" -> ChinoMuted
        else -> ChinoSignalGreen
    }
}

@Composable
private fun ScrubberBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    segments: List<Segment>,
    trickplayCues: List<TrickplayCue>,
    trickplayBaseUrl: String,
    streamToken: String,
    focus: ItemFocus,
    onFocusChange: (Boolean) -> Unit,
    onSeek: (deltaMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focused = focus.focused
    val safeDuration = if (durationMs > 0) durationMs else 1L
    val playProgress = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val bufferProgress = (bufferedMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    // The bar, as a row in the focus style: its look on focus is the style's,
    // and its own stays the same focused or not.
    val barHeight = 6.dp
    val thumbSize = 14.dp
    BoxWithConstraints(
        modifier = modifier
            .height(40.dp)
            .focusRequester(remember { FocusRequester() })
            .focusStyle(focus, FocusKind.Row, surface = Color.Transparent)
            .onFocusChanged { onFocusChange(it.isFocused) }
            // Intercept DPAD_LEFT/RIGHT to scrub instead of letting focus
            // travel to a sibling control. Each press = ±10s, matching the
            // arrow-key bindings in chino-web's PlayerPage. Auto-repeat
            // (long-press) scales naturally because Android fires the same
            // key event repeatedly.
            .onPreviewKeyEvent { evt ->
                if (evt.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (evt.key) {
                    Key.DirectionLeft -> { onSeek(-10_000L); true }
                    Key.DirectionRight -> { onSeek(+10_000L); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The track, opaque.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .background(SeekBarLook.Track),
        )
        // What is loaded ahead, behind the playhead.
        Box(
            modifier = Modifier
                .fillMaxWidth(bufferProgress)
                .height(barHeight)
                .background(SeekBarLook.Buffered),
        )
        // Segment markers (intro / credits / recap / etc.) rendered as
        // coloured stripes ON the scrubber, between buffered and playhead
        // layers. Per-segment Row with two flex spacers — left pads to
        // segment start, segment own width fills the kind-coloured stripe,
        // right spacer fills the remainder. Stacking is fine because each
        // stripe takes only its own width.
        if (durationMs > 0) {
            segments.forEach { seg ->
                val startFrac = (seg.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                val endFrac = (seg.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
                val widthFrac = (endFrac - startFrac).coerceAtLeast(0f)
                if (widthFrac > 0f) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Left spacer up to segment start
                        Box(modifier = Modifier.fillMaxWidth(startFrac))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(widthFrac / (1f - startFrac).coerceAtLeast(0.001f))
                                .height(barHeight)
                                .background(SeekBarLook.segment(seg.kind)),
                        )
                    }
                }
            }
        }
        // The played part, in cloud-blue, as the volume's level.
        Box(
            modifier = Modifier
                .fillMaxWidth(playProgress)
                .height(barHeight)
                .background(SeekBarLook.Played),
        )
        // Thumb. Positioned by a horizontally-offset Box at the playProgress
        // fraction; we render it inside a fillMaxWidth wrapper so the parent's
        // alignment math handles the centering for us.
        Box(modifier = Modifier.fillMaxWidth(playProgress), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .background(SeekBarLook.Thumb),
            )
        }
        // Trickplay preview — a sprite-cropped thumbnail floating above the bar
        // at the playhead, shown while the scrubber owns focus (the DPAD-scrub
        // analogue of web's hover / mobile's drag). Only when we have cues for
        // this item; otherwise the bar shows the coloured segment stripes only
        // (graceful degrade). The cue lookup is O(log n); the sprite is a single
        // Coil load reused across cue tiles in the same sheet.
        if (focused && durationMs > 0 && trickplayCues.isNotEmpty()) {
            val cue = findTrickplayCue(trickplayCues, positionMs)
            if (cue != null) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val trackWidthPx = with(density) { maxWidth.toPx() }
                val tileWDp = with(density) { cue.w.toDp() }
                val tileHDp = with(density) { cue.h.toDp() }
                val halfTilePx = with(density) { (tileWDp / 2).toPx() }
                // Clamp the preview's center so the tile stays inside the track
                // (web does the same half-tile margin trick).
                val centerPx = (trackWidthPx * playProgress)
                    .coerceIn(halfTilePx, (trackWidthPx - halfTilePx).coerceAtLeast(halfTilePx))
                val leftDp = with(density) { centerPx.toDp() } - tileWDp / 2
                val seg = segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = leftDp, y = -(tileHDp + 36.dp)),
                ) {
                    TrickplayPreview(
                        cue = cue,
                        spriteUrl = "$trickplayBaseUrl/${cue.sprite}?stream=$streamToken",
                        timeLabel = formatPositionMs(positionMs),
                        segmentLabel = seg?.let { segmentDisplayLabel(it) },
                    )
                }
            }
        }
    }
}

/**
 * Sprite-cropped trickplay thumbnail + time/segment caption. Mirrors
 * chino-web's hover preview and chino-mobile's TrickplayPreview: a fixed `w×h`
 * window with the full sprite sheet placed at `-x, -y`, so only the cue's tile
 * shows. Compose has no `background-position`, so we render the full sprite via
 * Coil's AsyncImage at its intrinsic pixel size (wrapContentSize unbounded,
 * ContentScale.None, TopStart) inside a `clipToBounds` window and offset it by
 * `(-x, -y)`. Sprite px -> dp via the local density so the crop window and the
 * offset share one scale (keeps the tile aligned on any dpi). Coil caches the
 * sheet, so moving across tiles in the same sheet reuses the decoded bitmap.
 */
@Composable
private fun TrickplayPreview(
    cue: TrickplayCue,
    spriteUrl: String,
    timeLabel: String,
    segmentLabel: String?,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tileWDp = with(density) { cue.w.toDp() }
    val tileHDp = with(density) { cue.h.toDp() }
    val offX = with(density) { -cue.x.toDp() }
    val offY = with(density) { -cue.y.toDp() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(tileWDp, tileHDp)
                .clip(RectangleShape)
                .background(Color.Black)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)), RectangleShape)
                .clipToBounds(),
        ) {
            coil.compose.AsyncImage(
                model = spriteUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.None,
                alignment = Alignment.TopStart,
                modifier = Modifier
                    // Lay out at intrinsic sprite size (unbounded) so the
                    // negative offset can shift the right tile into the clip
                    // window. Without `unbounded` the image is bounded to the
                    // tile and the offset clips to black.
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                    .offset(x = offX, y = offY),
            )
        }
        Box(
            modifier = Modifier
                .clip(RectangleShape)
                // Solid: nothing over the picture is see-through.
                .background(ChinoBg)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (segmentLabel.isNullOrBlank()) timeLabel else "$segmentLabel · $timeLabel",
                color = ChinoFg,
                fontSize = 11.sp,
            )
        }
    }
}

/** Friendly scrub-preview label for a segment — mirrors web's / mobile's
 *  segmentDisplayLabel: prefer a human chapter label, else the kind
 *  capitalised. Auto-detector labels (numeric tags, dash-joined ranges) fall
 *  back to the kind so the preview doesn't surface raw analyzer noise. */
private fun segmentDisplayLabel(seg: Segment): String {
    val friendlyKind = when (seg.kind.lowercase()) {
        "intro" -> "Intro"
        "credits" -> "Credits"
        "recap" -> "Recap"
        "chapter" -> "Chapter"
        else -> seg.kind.replaceFirstChar { it.uppercase() }
    }
    val raw = seg.label?.trim().orEmpty()
    if (seg.kind.equals("chapter", ignoreCase = true) && raw.isNotEmpty()) return raw
    if (raw.isEmpty()) return friendlyKind
    // Dash-joined ("00:00-01:20") or numeric-tag ("seg_12") detector noise.
    if (raw.contains('-')) return friendlyKind
    if (Regex("""^\d""").containsMatchIn(raw)) return friendlyKind
    return raw
}

/** Volume control matching web/mobile (speaker icon + horizontal slider).
 *  One focusable element so it doesn't fragment D-pad navigation: CENTER
 *  toggles mute; UP/DOWN nudge the level ±5%. LEFT/RIGHT are deliberately NOT
 *  consumed so focus can still cross to the neighbouring controls (a slider
 *  that ate LEFT/RIGHT would trap focus before the right-hand cluster). The
 *  slider drives ExoPlayer's player.volume (app-level), independent of the
 *  remote's system volume. */
@Composable
private fun VolumeControl(
    muted: Boolean,
    volume: Float,
    onToggleMute: () -> Unit,
    onVolumeChange: (Float) -> Unit,
) {
    // A plain control in the focus style, on the solid surface the other
    // controls over the picture sit on.
    val focus = rememberItemFocus()
    val level = if (muted) 0f else volume.coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .height(40.dp)
            .focusStyle(focus, FocusKind.Control, surface = ChinoSurfaceHi)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter -> { onToggleMute(); true }
                    Key.DirectionUp -> { onVolumeChange((level + 0.05f).coerceIn(0f, 1f)); true }
                    Key.DirectionDown -> { onVolumeChange((level - 0.05f).coerceIn(0f, 1f)); true }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (muted || level == 0f) Lucide.VolumeX else Lucide.Volume2,
            contentDescription = "Volume",
            tint = ChinoFocus.title(ChinoText, focus.focused),
            modifier = Modifier.size(20.dp),
        )
        // The seek bar's look: its track, the level in cloud-blue.
        Box(
            modifier = Modifier
                .width(80.dp)
                .height(4.dp)
                .background(SeekBarLook.Track),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(level.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(SeekBarLook.Played),
            )
        }
    }
}

@Composable
internal fun ControlIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    focus: ItemFocus = rememberItemFocus(),
) {
    // Web/mobile ChromeButton metrics (36px / 20px icon), bumped to 40dp for
    // 10-ft legibility. Over the picture on a solid surface, never a
    // see-through one (docs/chino, playback): the raised surface at rest, a
    // plain control in the focus style; the accent (subtitles on, a speed
    // set) a solid blue it keeps on focus, its icon in the page's colour as
    // on every filled blue control; disabled, the plain surface and a dim
    // icon.
    val size = 40.dp
    val iconSize = 20.dp
    val bg = when {
        !enabled -> ChinoSurface
        accent -> ChinoAccent
        else -> ChinoSurfaceHi
    }
    // A plain control's icon fg-2, fg on focus.
    val fg = when {
        !enabled -> ChinoDim
        accent -> ChinoBg
        else -> ChinoFocus.title(ChinoText, focus.focused)
    }
    Box(
        modifier = modifier
            .size(size)
            .focusStyle(focus, FocusKind.Control, surface = bg, filled = accent)
            .focusable(enabled)
            // Explicit DPAD-CENTER/ENTER handler — a bare clickable does NOT
            // reliably fire on a TV remote's select key (the rail/search/menu
            // rows all wire this the same way). Without it, focusing Subtitles
            // / Audio / Speed and pressing OK did nothing.
            .onKeyEvent { e ->
                if (enabled && e.type == KeyEventType.KeyDown &&
                    (e.key == Key.DirectionCenter || e.key == Key.Enter)
                ) {
                    onClick(); true
                } else {
                    false
                }
            }
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(iconSize),
        )
    }
}

private fun formatPositionMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Wraps the player surface: gradients fade in/out via AnimatedVisibility so the
 *  bare video frame is visible when chrome is hidden. */
@Composable
internal fun PlayerChromeOverlay(
    visible: Boolean,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            top()
            Spacer(modifier = Modifier.weight(1f))
            bottom()
        }
    }
}
