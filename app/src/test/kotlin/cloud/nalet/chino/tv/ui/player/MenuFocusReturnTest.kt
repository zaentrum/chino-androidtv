package cloud.nalet.chino.tv.ui.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.api.QualityRung
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A player menu that closes - BACK or a pick - gives the focus back to the
 * button that opened it, never to play/pause, where the next OK would pause
 * the title: subtitles to subtitles, speed to speed, quality to quality even
 * where its pick rebuilds the player; the remote's own subtitles key's menu
 * to the subtitles button. The controls coming back from hidden put it on
 * play/pause, as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class MenuFocusReturnTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var open by mutableStateOf<PlayerMenu?>(null)
    private var controlsVisible by mutableStateOf(true)
    private var speed by mutableStateOf(1f)
    private var quality by mutableStateOf("auto")

    /** How many players were built: a quality's pick rebuilds it. */
    private var builds by mutableIntStateOf(0)

    private val rungs = listOf(
        QualityRung(name = "auto", label = "Auto"),
        QualityRung(name = "v1", label = "High (1080p)"),
        QualityRung(name = "v2", label = "Medium (720p)"),
    )

    /** The player as its screen puts it together: the control bar, its
     *  menus over it, BACK closing the one open; a quality's pick reloads
     *  it - a moment of "Preparing…" with no controls, then a new player -
     *  where the bar's focus outlives the player, as on the screen. */
    private fun player(audioButton: Boolean = true) {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                val focus = remember { ControlBarFocus() }
                var preparing by remember { mutableStateOf(false) }
                BackHandler(enabled = open != null) { open = null }
                LaunchedEffect(preparing) {
                    if (preparing) {
                        kotlinx.coroutines.delay(300)
                        preparing = false
                        builds++
                    }
                }
                if (preparing) {
                    Text("Preparing…")
                } else {
                    key(builds) {
                        ControlBarFocusEffect(controlsVisible = controlsVisible, openMenu = open, focus = focus)
                        Box(Modifier.fillMaxSize()) {
                            if (controlsVisible) {
                                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                                    PlayerBottomChrome(
                                        isPlaying = true, positionMs = 112_000, durationMs = 887_000, bufferedMs = 300_000,
                                        segments = emptyList(),
                                        trickplayCues = emptyList(), trickplayBaseUrl = "", streamToken = "",
                                        muted = false, volume = 0.6f, onPlayPause = {}, onToggleMute = {}, onVolumeChange = {},
                                        onScrubberSeek = {},
                                        onAudio = if (audioButton) ({ open = PlayerMenu.Audio }) else null,
                                        onSubtitles = { open = PlayerMenu.Subtitles },
                                        onQuality = { open = PlayerMenu.Quality },
                                        onSpeed = { open = PlayerMenu.Speed },
                                        onInfo = {}, onPrevEpisode = null, onNextEpisode = null,
                                        playPauseFocusRequester = focus.playPause, onUserInteraction = {},
                                        menuButton = focus::button,
                                    )
                                }
                            }
                            when (open) {
                                PlayerMenu.Subtitles -> SubtitlePanel(
                                    menu = SubtitleMenu(top = emptyList(), more = emptyList()),
                                    selected = null,
                                    onSelect = { open = null },
                                )
                                PlayerMenu.Speed -> SpeedPanel(current = speed, onPick = { speed = it; open = null }, onDismiss = { open = null })
                                PlayerMenu.Quality -> QualityPanel(
                                    rungs = rungs,
                                    active = quality,
                                    playing = null,
                                    onPick = { picked ->
                                        open = null
                                        if (picked != quality) {
                                            quality = picked
                                            preparing = true
                                        }
                                    },
                                    onDismiss = { open = null },
                                )
                                PlayerMenu.Audio -> AudioPanel(tracks = emptyList(), onSelect = { open = null }, onDismiss = { open = null })
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun button(name: String) = compose.onNode(hasContentDescription(name))

    private fun focused(name: String) = compose.onNode(isFocused()).assert(hasContentDescription(name))

    /** OK on the control bar's button [name]: it opens its menu. */
    private fun openFrom(name: String) {
        button(name).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionCenter)
        settle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The remote's BACK, as a TV sends it: down and up, through the
     *  activity - the focused item first, then the screen's BACK. */
    private fun back() {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        settle()
    }

    @Test
    fun `the controls open with the focus on play`() {
        player()
        focused("Pause")
    }

    @Test
    fun `BACK out of the subtitles menu gives the focus back to subtitles, never to play`() {
        player()
        openFrom("Subtitles")
        compose.onNode(isFocused()).assert(hasText("off"))
        back()
        compose.onNode(hasText("subtitles")).assertDoesNotExist()
        focused("Subtitles")
    }

    @Test
    fun `a pick in the subtitles menu gives the focus back to subtitles`() {
        player()
        openFrom("Subtitles")
        press(Key.DirectionCenter)
        settle()
        compose.onNode(hasText("subtitles")).assertDoesNotExist()
        focused("Subtitles")
    }

    @Test
    fun `a speed picked gives the focus back to speed`() {
        player()
        openFrom("Playback speed")
        compose.onNode(isFocused()).assert(hasText("normal"))
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        settle()
        compose.onNode(hasText("speed")).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1.25f, speed) }
        focused("Playback speed")
    }

    @Test
    fun `BACK out of the quality and audio menus gives the focus back to their buttons`() {
        player()
        openFrom("Quality")
        back()
        focused("Quality")
        openFrom("Audio track")
        back()
        focused("Audio track")
    }

    @Test
    fun `a quality picked rebuilds the player and the focus comes back to quality`() {
        player()
        openFrom("Quality")
        compose.onNode(isFocused()).assert(hasText("Auto"))
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        settle()
        compose.waitUntil(5_000) { builds == 1 }
        settle()
        compose.onNode(hasText("Preparing…")).assertDoesNotExist()
        focused("Quality")
        // Back from hidden later, the controls put it on play as ever.
        compose.runOnIdle { controlsVisible = false }
        settle()
        compose.runOnIdle { controlsVisible = true }
        settle()
        focused("Pause")
    }

    @Test
    fun `the remote's subtitles key opens the menu over the controls, and closing it the focus is on subtitles`() {
        player()
        focused("Pause")
        // The remote's subtitles key: the menu opens without its button.
        compose.runOnIdle { open = PlayerMenu.Subtitles }
        settle()
        compose.onNode(isFocused()).assert(hasText("off"))
        back()
        focused("Subtitles")
    }

    @Test
    fun `the audio key's menu on a title with one audio track gives the focus to play, the bar having no audio button`() {
        player(audioButton = false)
        compose.runOnIdle { open = PlayerMenu.Audio }
        settle()
        compose.onNode(hasText("No audio tracks")).assertExists()
        back()
        focused("Pause")
    }

    @Test
    fun `the controls coming back from hidden put the focus on play`() {
        player()
        openFrom("Playback speed")
        back()
        focused("Playback speed")
        compose.runOnIdle { controlsVisible = false }
        settle()
        compose.runOnIdle { controlsVisible = true }
        settle()
        focused("Pause")
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
