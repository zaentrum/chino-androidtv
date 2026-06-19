package cloud.nalet.chino.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Global UI down-scale. The AOSP TV emulator is 1920x1080 @ density 2.0, i.e.
 * only 960dp wide, so fixed-dp components render ~25% larger than on the
 * tablet (a 2560px landscape ≈ 1280dp wide). Scaling the effective density by
 * 0.75 widens the TV canvas to ~1280dp so the layout reads at the same
 * density as the tablet. Scales dp AND sp uniformly
 * (sp px = sp · fontScale · density).
 */
private const val UI_SCALE = 0.75f

private val ChinoDarkColors = darkColorScheme(
    primary = ChinoAccent,
    onPrimary = ChinoBg,
    background = ChinoBg,
    onBackground = ChinoText,
    surface = ChinoSurface,
    onSurface = ChinoText,
    surfaceVariant = ChinoSurfaceHi,
    onSurfaceVariant = ChinoMuted,
    border = ChinoBorder,
    error = ChinoError,
    onError = ChinoBg,
)

@Composable
fun ChinoTvTheme(content: @Composable () -> Unit) {
    // Typography reflow + LocalTextStyle override DISABLED on TV — applying
    // them causes the app to hang during startup and ANR. The fonts ship in
    // res/font/ for future use (LogoMark uses jetbrains_mono_extrabold
    // directly which works fine) but global typography is back on the
    // platform default until we figure out the TV-specific bottleneck.
    MaterialTheme(colorScheme = ChinoDarkColors) {
        // Down-scale the whole UI to tablet-like density (see UI_SCALE).
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = base.density * UI_SCALE,
                fontScale = base.fontScale,
            ),
            content = content,
        )
    }
}
