package cloud.nalet.chino.tv.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.R
import cloud.nalet.chino.tv.data.api.Episode
import cloud.nalet.chino.tv.data.api.LoadFailure
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.browse.PosterGridCard
import cloud.nalet.chino.tv.ui.detail.DetailActions
import cloud.nalet.chino.tv.ui.detail.EpisodeResume
import cloud.nalet.chino.tv.ui.detail.EpisodeRow
import cloud.nalet.chino.tv.ui.detail.PlayButton
import cloud.nalet.chino.tv.ui.library.FilterChip
import cloud.nalet.chino.tv.ui.library.PosterCard
import cloud.nalet.chino.tv.ui.library.RatingBadge
import cloud.nalet.chino.tv.ui.library.TvRailButton
import cloud.nalet.chino.tv.ui.notices.BellWithCount
import cloud.nalet.chino.tv.ui.player.ControlIconButton
import cloud.nalet.chino.tv.ui.player.PlayerBottomChrome
import cloud.nalet.chino.tv.ui.player.SpeedPanel
import cloud.nalet.chino.tv.ui.player.SubtitlePanel
import cloud.nalet.chino.tv.ui.player.SubtitleTrackInfo
import cloud.nalet.chino.tv.ui.player.subtitleMenu
import cloud.nalet.chino.tv.ui.settings.LanguageMenu
import cloud.nalet.chino.tv.ui.settings.LanguageSettingRow
import cloud.nalet.chino.tv.ui.settings.Section
import cloud.nalet.chino.tv.ui.settings.audioLanguageChoices
import cloud.nalet.chino.tv.ui.settings.subtitleLanguageChoices
import cloud.nalet.chino.tv.ui.watchlist.ConfirmDeleteListDialog
import cloud.nalet.chino.tv.data.api.Watchlist
import coil.Coil
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.test.FakeImageLoaderEngine
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Captions
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Tv
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The focus style, kind by kind, at rest and focused: a row (an episode, and
 * the one the page was opened on), a card (a poster), a filled and a plain
 * button, a chip and a nav item, each beside neighbours at rest, and
 * Settings' row of language chips with one focused; and the labels and icons
 * on filled colours, in the page's colour (the detail's toggles, the badges);
 * the player's menus of one choice, the picked row's mark, and its controls
 * on their solid surfaces over the picture. Rendered on
 * the JVM under Robolectric's native graphics, at the TV's 960 × 540 dp
 * (1920 × 1080 px), in the app's theme.
 *
 * The golden images are in src/test/screenshots: recordRoborazziDebug writes
 * them, verifyRoborazziDebug fails when a rendering no longer matches them -
 * when the focused look changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class FocusScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalCoilApi::class)
    @Before
    fun artwork() {
        // Every poster and still is the same painted artwork, blue to amber -
        // a blue near the ring's included - so no image comes from a network.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .default(BitmapDrawable(context.resources, artworkBitmap()))
            .build()
        Coil.setImageLoader(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @After
    fun noArtwork() = Coil.reset()

    @Test fun rowAtRest() = shot("focus_row_rest") { Episodes(focus = null) }
    @Test fun rowFocused() = shot("focus_row_focused", focus = 2) { Episodes(focus = it) }
    // The episode the page was opened on, where you came from: its bar stays
    // beside the ring.
    @Test fun rowCameFromFocused() = shot("focus_row_came_from_focused", focus = 1) { Episodes(focus = it) }

    @Test fun cardAtRest() = shot("focus_card_rest") { Posters(focus = null) }
    @Test fun cardFocused() = shot("focus_card_focused", focus = 1) { Posters(focus = it) }

    @Test fun buttonsAtRest() = shot("focus_button_rest") { Buttons(focus = null) }
    @Test fun filledButtonFocused() = shot("focus_button_filled_focused", focus = 0) { Buttons(focus = it) }
    @Test fun plainButtonFocused() = shot("focus_button_plain_focused", focus = 1) { Buttons(focus = it) }

    // The danger button, for what deletes something: an amber outline and
    // label at rest, filled amber with a dark label on focus, the ring around.
    @Test fun dangerButtonAtRest() = shot("focus_button_danger_rest") { Danger(focus = null) }
    @Test fun dangerButtonFocused() = shot("focus_button_danger_focused", focus = 1) { Danger(focus = it) }
    // The question before a list is deleted: cancel with the focus first,
    // the danger button beside it.
    @Test fun deleteListQuestion() = shot("watchlist_delete_list_focused") {
        Box(Modifier.width(1152.dp).height(600.dp)) {
            ConfirmDeleteListDialog(
                list = Watchlist(id = "l2", name = "Weekend", itemCount = 12),
                onConfirm = {},
                onDismiss = {},
            )
        }
    }

    @Test fun chipsAtRest() = shot("focus_chip_rest") { Chips(focus = null) }
    @Test fun chipFocused() = shot("focus_chip_focused", focus = 1) { Chips(focus = it) }
    @Test fun filledChipFocused() = shot("focus_chip_filled_focused", focus = 0) { Chips(focus = it) }

    @Test fun navAtRest() = shot("focus_nav_rest") { Nav(focus = null) }
    @Test fun navFocused() = shot("focus_nav_focused", focus = 1) { Nav(focus = it) }

    // Settings' audio language: a row with the language set, focused, the
    // text under it; and the subtitle language's menu of one choice, the
    // server's languages by the player's names, German picked.
    @Test fun languageSettingFocused() =
        shot("settings_language_focused", focusLabel = "Language") { Languages() }
    @Test fun languageMenu() = shot("settings_language_menu") {
        Box(Modifier.width(1152.dp).height(600.dp)) {
            LanguageMenu(
                header = "subtitle language",
                choices = subtitleLanguageChoices(listOf("en", "de", "zh-Hans", "zh-Hant"), "de"),
                value = "de",
                onPick = {},
                onDismiss = {},
            )
        }
    }

    // A label or an icon on a filled colour is the page's colour: the
    // detail's actions with every toggle on, and the liked one focused, its
    // fill kept; the badges on cards, the hero's rating and the bell's count.
    // Off, the toggles' icons outlined on the plain surface.
    @Test fun filledActionsAtRest() = shot("fills_detail_actions_rest") { Actions(on = true) }
    @Test fun filledActionFocused() = shot("fills_detail_actions_focused", focusLabel = "liked") { Actions(on = true) }
    @Test fun actionsOff() = shot("detail_actions_off") { Actions(on = false) }
    // The two parts of add to list, each focused while the title is in the
    // Watchlist: the page's colour between a part and its ring, which then
    // stands out where it lies on the other part's blue.
    @Test fun watchlistPartFocused() = shot("fills_detail_actions_watchlist_focused", focusLabel = "in your Watchlist") { Actions(on = true) }
    @Test fun listsPartFocused() = shot("fills_detail_actions_lists_focused", focusLabel = "choose lists") { Actions(on = true) }
    @Test fun badges() = shot("fills_badges") { Badges() }

    // The player's menus of one choice: the picked row in the accent blue
    // with a small filled square before it, every row - "off" and "more
    // languages" too - keeping the square's column, so the names line up.
    // The subtitles menu opens on the subtitle on; the speed menu on its
    // first row, the picked one further down.
    @Test fun subtitlesMenu() = shot("player_menu_subtitles") { SubtitlesMenu() }
    @Test fun speedMenu() = shot("player_menu_speed") { SpeedPanel(current = 1.25f, onPick = {}, onDismiss = {}) }

    // The player's controls over the picture, each on a solid surface, never
    // a see-through one: play focused, subtitles on in solid blue with a dark
    // icon, audio at rest, one disabled.
    @Test fun playerControlsOverPicture() = shot("player_controls_solid", focus = 0) { PlayerButtons(it) }

    // The seek row and the buttons on the solid band below the picture,
    // never on it: an opaque track, what is loaded ahead, the played part in
    // cloud-blue as the volume's level, the time labels whole.
    @Test fun playerSeekRow() = shot("player_seek_row") { SeekRow() }

    // Zap's channel info over the picture, the whole block on one solid
    // band inside the safe area, the band out to the screen's edges.
    @Test fun zapInfo() = shot("zap_info") { ZapInfo() }

    // The library, as any screen whose content did not load: its way on, try
    // again, at rest and focused - it takes the focus first. At rest without
    // the remote: with the remote, a screen whose one focusable is the button
    // has the focus there at once.
    @Test fun loadErrorAtRest() = shot("load_error_rest", remote = false) { LibraryError(focusFirst = false) }
    @Test fun loadErrorFocused() = shot("load_error_focused") { LibraryError(focusFirst = true) }
    // The server answered with an error: try again in a moment.
    @Test fun serverErrorFocused() = shot("load_error_server_focused") {
        LibraryError(focusFirst = true, failure = LoadFailure(LoadFailure.Kind.Server, "HTTP 503 Service Unavailable"))
    }
    // Not in the library any more: no try brings it back, so back, focused.
    @Test fun goneTitleFocused() = shot("gone_title_focused") { Gone("this title") }
    @Test fun gonePersonFocused() = shot("gone_person_focused") { Gone("this person") }
    // Signed out, where going to sign-in by itself could not spare a screen:
    // sign in, focused.
    @Test fun signedOutFocused() = shot("signed_out_focused") {
        LibraryError(focusFirst = true, failure = LoadFailure(LoadFailure.Kind.SignedOut, "HTTP 401 Unauthorized"))
    }

    /**
     * Renders [content] in the theme on the page background, moves the
     * remote's focus to item [focus], or to the item that reads or is named
     * [focusLabel] (neither for the look at rest), and takes the picture once
     * the ring and the growth have settled. [remote] false leaves the input in
     * touch mode, where nothing takes the focus by itself.
     */
    private fun shot(
        name: String,
        focus: Int? = null,
        focusLabel: String? = null,
        remote: Boolean = true,
        content: @Composable (Pair<Int, FocusRequester>?) -> Unit,
    ) {
        val target = FocusRequester()
        compose.setContent {
            ChinoTvTheme {
                if (remote) RemoteInput()
                Box(Modifier.background(ChinoBg).padding(24.dp)) {
                    content(focus?.let { it to target })
                }
            }
        }
        compose.waitForIdle()
        if (focus != null) {
            compose.runOnIdle { target.requestFocus() }
            compose.waitForIdle()
        }
        if (focusLabel != null) {
            compose.onNode(hasText(focusLabel) or hasContentDescription(focusLabel))
                .performSemanticsAction(SemanticsActions.RequestFocus)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = options)
    }

    @Composable
    private fun Episodes(focus: Pair<Int, FocusRequester>?) {
        // The season's surface, as on the detail page.
        Column(Modifier.width(900.dp).background(ChinoSurface)) {
            episodes.forEachIndexed { i, episode ->
                if (i > 0) Box(Modifier.width(900.dp).height(1.dp).background(ChinoBorderHi))
                EpisodeRow(
                    episode = episode,
                    backdropUrl = "https://artwork.test/${episode.id}",
                    resume = if (i == 1) EpisodeResume(positionSec = 600, durationSec = 2100) else null,
                    onClick = {},
                    onToggleWatched = {},
                    onAddToList = {},
                    inWatchlist = i == 0,
                    selected = i == 1,
                    focusRequester = focus?.second?.takeIf { focus.first == i },
                )
            }
        }
    }

    @Composable
    private fun Posters(focus: Pair<Int, FocusRequester>?) {
        Row(horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room)) {
            posters.forEachIndexed { i, item ->
                Box(Modifier.requester(focus, i)) {
                    PosterCard(item = item, posterUrl = "https://artwork.test/${item.id}", onItemSelected = {})
                }
            }
        }
    }

    @Composable
    private fun Buttons(focus: Pair<Int, FocusRequester>?) {
        // The detail page's actions: the filled Play, then plain ones.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.requester(focus, 0)) { PlayButton(label = "resume S01E02", onClick = {}) }
            Box(Modifier.requester(focus, 1)) {
                ChinoButton(onClick = {}) {
                    Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("start over")
                }
            }
            Box(Modifier.requester(focus, 2)) { ChinoButton(onClick = {}) { Text("trailer") } }
        }
    }

    @Composable
    private fun Danger(focus: Pair<Int, FocusRequester>?) {
        // Delete account's question: cancel, and the action it asks about.
        Row(horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room)) {
            Box(Modifier.requester(focus, 0)) { ChinoButton(onClick = {}) { Text("cancel") } }
            Box(Modifier.requester(focus, 1)) { ChinoDangerButton(onClick = {}) { Text("delete account") } }
        }
    }

    @Composable
    private fun Chips(focus: Pair<Int, FocusRequester>?) {
        // Browse's filters: one on (filled), the others off (plain).
        Row(horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room)) {
            listOf("all genres", "Drama", "Comedy", "Documentary").forEachIndexed { i, label ->
                Box(Modifier.requester(focus, i)) { FilterChip(label = label, active = i == 0, onClick = {}) }
            }
        }
    }

    @Composable
    private fun Nav(focus: Pair<Int, FocusRequester>?) {
        // The side rail: Home is where you are.
        Column(
            Modifier.width(80.dp).background(ChinoBg).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(
                Triple(Lucide.House, "Home", true),
                Triple(Lucide.Film, "Movies", false),
                Triple(Lucide.Tv, "Series", false),
                Triple(Lucide.Bookmark, "Watchlist", false),
            ).forEachIndexed { i, (icon, label, active) ->
                Box(Modifier.requester(focus, i)) {
                    TvRailButton(icon = icon, label = label, isActive = active, onClick = {})
                }
            }
        }
    }

    @Composable
    private fun Languages() {
        // As Settings lays it out: the section in the column beside the rail
        // (1280 dp less the safe area's 2 × 64, the rail's 80 and the
        // column's 2 × 24), on the canvas, its heading as Settings has it,
        // Original chosen.
        Box(Modifier.width(1024.dp)) {
            Section(
                title = "audio",
                subtitle = "Titles play with audio in the language you pick here, where they have it. " +
                    "With Original, each title plays in the language it was made in. " +
                    "A choice in the player applies only to what's playing now.",
            ) {
                LanguageSettingRow(choices = audioLanguageChoices("orig"), value = "orig", onOpen = {})
            }
        }
    }

    @Composable
    private fun PlayerButtons(focus: Pair<Int, FocusRequester>?) {
        // The picture behind: the painted artwork's blue and amber.
        Box(
            Modifier.width(600.dp).height(120.dp).background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(androidx.compose.ui.graphics.Color(0xFF1E88E5), androidx.compose.ui.graphics.Color(0xFFE0A040)),
                ),
            ).padding(40.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room)) {
                Box(Modifier.requester(focus, 0)) { ControlIconButton(icon = Lucide.Play, contentDescription = "Play", onClick = {}) }
                ControlIconButton(icon = Lucide.Captions, contentDescription = "Subtitles", onClick = {}, accent = true)
                ControlIconButton(icon = Lucide.AudioLines, contentDescription = "Audio track", onClick = {})
                ControlIconButton(icon = Lucide.Gauge, contentDescription = "Playback speed", onClick = {})
                ControlIconButton(icon = Lucide.Info, contentDescription = "Playback info", onClick = {}, enabled = false)
            }
        }
    }

    @Composable
    private fun SeekRow() {
        Column(Modifier.width(900.dp)) {
            // The picture above the band: the painted artwork's blue and amber.
            Box(
                Modifier.width(900.dp).height(160.dp).background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(androidx.compose.ui.graphics.Color(0xFF1E88E5), androidx.compose.ui.graphics.Color(0xFFE0A040)),
                    ),
                ),
            )
            PlayerBottomChrome(
                isPlaying = true, positionMs = 112_000, durationMs = 887_000, bufferedMs = 300_000,
                segments = listOf(cloud.nalet.chino.tv.data.api.Segment(kind = "intro", startMs = 20_000, endMs = 80_000)),
                trickplayCues = emptyList(), trickplayBaseUrl = "", streamToken = "",
                muted = false, volume = 0.6f, onPlayPause = {}, onToggleMute = {}, onVolumeChange = {},
                onScrubberSeek = {}, onAudio = {}, onSubtitles = {}, onQuality = {}, onSpeed = {},
                onInfo = {}, onPrevEpisode = null, onNextEpisode = null,
                playPauseFocusRequester = FocusRequester(), onUserInteraction = {},
            )
        }
    }

    @Composable
    private fun ZapInfo() {
        Box(
            Modifier.width(912.dp).height(492.dp).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(androidx.compose.ui.graphics.Color(0xFF1E88E5), androidx.compose.ui.graphics.Color(0xFFE0A040)),
                ),
            ),
        ) {
            SafeAreaOverlay {
                Box(Modifier.align(Alignment.BottomStart)) {
                    cloud.nalet.chino.tv.ui.zap.ChannelInfoOverlay(
                        item = posters[0].copy(
                            genres = listOf("Drama", "Mystery"),
                            overview = "A ship comes into the harbour at night, and the town wakes to find it empty. " +
                                "The harbour master keeps a list of every ship that came in, and one name is missing.",
                        ),
                        muted = false,
                        saved = true,
                    )
                }
            }
        }
    }

    @Composable
    private fun SubtitlesMenu() {
        val tracks = listOf(
            SubtitleTrackInfo("emb:2", "eng"),
            SubtitleTrackInfo("emb:3", "eng", "SDH"),
            SubtitleTrackInfo("emb:4", "ger"),
            SubtitleTrackInfo("side:de", "de", "AI"),
            SubtitleTrackInfo("emb:5", "fre"),
        )
        SubtitlePanel(menu = subtitleMenu(tracks, "en", listOf("en", "de")), selected = "side:de", onSelect = {})
    }

    @Composable
    private fun LibraryError(
        focusFirst: Boolean,
        failure: LoadFailure = LoadFailure(LoadFailure.Kind.Connection, "timeout"),
    ) {
        // The library's error, filling the screen on its surface; the reason
        // a timeout's, else the one given.
        Box(Modifier.fillMaxSize().background(ChinoSurface)) {
            LoadError(
                title = stringResource(R.string.library_error_title),
                failure = failure,
                onRetry = {},
                focusFirst = focusFirst,
            )
        }
    }

    @Composable
    private fun Gone(what: String) {
        // A detail page's or a person's, filling the screen; the server's 404.
        Box(Modifier.fillMaxSize().background(ChinoSurface)) {
            LoadError(
                title = "couldn't load $what",
                failure = LoadFailure(LoadFailure.Kind.NotFound, "HTTP 404 Not Found"),
                onRetry = {},
                what = what,
                onBack = {},
            )
        }
    }

    @Composable
    private fun Actions(on: Boolean) {
        // The detail's actions: Resume filled blue, then the toggles - on, in
        // the Watchlist (a dark bookmark on blue, its "⌄" in the same fill),
        // watched (a dark check on green), liked (a dark heart on red).
        DetailActions(
            item = Item(
                id = "p1", title = "Quiet Orchard", kind = "movie", year = 2021,
                watchedAt = if (on) "2026-10-01T20:00:00Z" else null,
            ),
            primaryLabel = "resume 12:34",
            canResume = true,
            inWatchlist = on,
            liked = on,
            onPlay = {},
            onToggleWatchlist = {},
            onOpenListPicker = {},
            onToggleLike = {},
            onToggleWatched = {},
            onTrailerLaunch = {},
            onPlayTrailer = {},
        )
    }

    @Composable
    private fun Badges() {
        // A browse card saved (blue) and watched (green), a Home shelf's
        // poster saved and watched too - the same marks on every card - the
        // hero's rating (blue) and the bell's unread count (blue).
        Row(horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room), verticalAlignment = Alignment.Top) {
            PosterGridCard(
                item = posters[0].copy(watchedAt = "2026-10-01T20:00:00Z"),
                posterUrl = "https://artwork.test/p1",
                onClick = {},
                saved = true,
                modifier = Modifier.width(180.dp),
            )
            PosterCard(
                item = posters[1].copy(watchedAt = "2026-10-01T20:00:00Z"),
                posterUrl = "https://artwork.test/p2",
                onItemSelected = {},
                saved = true,
            )
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                RatingBadge(rating = 7.4)
                BellWithCount(unread = 3, onClick = {})
            }
        }
    }

    private companion object {
        /** The rendering may differ by a hair between machines (font
         *  rasterising); a ring or a surface that changed is far more. */
        val options = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.02f),
                resultValidator = { it.pixelDifferences <= it.pixelCount * 0.005 },
            ),
        )

        val episodes = listOf(
            Episode(id = "e1", title = "Arrival", seasonNumber = 1, episodeNumber = 1, durationMs = 52 * 60_000L,
                overview = "A ship comes into the harbour at night, and the town wakes to find it empty."),
            Episode(id = "e2", title = "The Harbour", seasonNumber = 1, episodeNumber = 2, durationMs = 48 * 60_000L,
                overview = "The harbour master keeps a list of every ship that came in. One name is missing."),
            Episode(id = "e3", title = "Low Tide", seasonNumber = 1, episodeNumber = 3, durationMs = 50 * 60_000L,
                overview = "When the water goes out further than anyone remembers, the old pier comes back."),
        )

        val posters = listOf(
            Item(id = "p1", title = "Quiet Orchard", kind = "movie", year = 2021, rating = 7.4),
            Item(id = "p2", title = "Paper Station", kind = "movie", year = 2019, rating = 6.8),
            Item(id = "p3", title = "Lanterns", kind = "movie", year = 2023, rating = 8.1),
        )

        /** Poster-like artwork: a blue near the ring's at the top, amber below. */
        fun artworkBitmap(): Bitmap {
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, 600f,
                    intArrayOf(0xFF1E88E5.toInt(), 0xFF2B4C7E.toInt(), 0xFFE0A040.toInt()),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            Canvas(bitmap).drawRect(0f, 0f, 400f, 600f, paint)
            return bitmap
        }
    }
}

/** Focus where item [index] is the one [focus] names. */
private fun Modifier.requester(focus: Pair<Int, FocusRequester>?, index: Int): Modifier =
    if (focus != null && focus.first == index) focusRequester(focus.second) else this

/** The remote's input: a TV is in keyboard mode, where a clickable takes focus
 *  (Robolectric starts in touch mode). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RemoteInput() {
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
}
