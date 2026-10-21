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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.api.Episode
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.detail.EpisodeResume
import cloud.nalet.chino.tv.ui.detail.EpisodeRow
import cloud.nalet.chino.tv.ui.detail.PlayButton
import cloud.nalet.chino.tv.ui.library.FilterChip
import cloud.nalet.chino.tv.ui.library.PosterCard
import cloud.nalet.chino.tv.ui.library.TvRailButton
import coil.Coil
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.test.FakeImageLoaderEngine
import com.composables.icons.lucide.Bookmark
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
 * button, a chip and a nav item, each beside neighbours at rest. Rendered on
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

    @Test fun chipsAtRest() = shot("focus_chip_rest") { Chips(focus = null) }
    @Test fun chipFocused() = shot("focus_chip_focused", focus = 1) { Chips(focus = it) }
    @Test fun filledChipFocused() = shot("focus_chip_filled_focused", focus = 0) { Chips(focus = it) }

    @Test fun navAtRest() = shot("focus_nav_rest") { Nav(focus = null) }
    @Test fun navFocused() = shot("focus_nav_focused", focus = 1) { Nav(focus = it) }

    /**
     * Renders [content] in the theme on the page background, moves the
     * remote's focus to item [focus] (none for the look at rest) and takes
     * the picture once the ring and the growth have settled.
     */
    private fun shot(
        name: String,
        focus: Int? = null,
        content: @Composable (Pair<Int, FocusRequester>?) -> Unit,
    ) {
        val target = FocusRequester()
        compose.setContent {
            ChinoTvTheme {
                RemoteInput()
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
            Box(Modifier.requester(focus, 0)) { PlayButton(label = "Resume S01E02", onClick = {}) }
            Box(Modifier.requester(focus, 1)) {
                ChinoButton(onClick = {}) {
                    Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start over")
                }
            }
            Box(Modifier.requester(focus, 2)) { ChinoButton(onClick = {}) { Text("Trailer") } }
        }
    }

    @Composable
    private fun Chips(focus: Pair<Int, FocusRequester>?) {
        // Browse's filters: one on (filled), the others off (plain).
        Row(horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room)) {
            listOf("All genres", "Drama", "Comedy", "Documentary").forEachIndexed { i, label ->
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
