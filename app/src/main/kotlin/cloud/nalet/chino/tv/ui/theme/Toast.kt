package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** How long a toast stays: five seconds (section 16.8 of the specification). */
internal const val TOAST_MS = 5_000L

/**
 * A toast: one line on a solid surface, over whatever screen is open, taking
 * no focus, and announced; it goes after [TOAST_MS], and [onGone] is called
 * then. [text] shown anew starts the time over.
 */
@Composable
fun Toast(text: String, modifier: Modifier = Modifier, onGone: () -> Unit) {
    LaunchedEffect(text) {
        delay(TOAST_MS)
        onGone()
    }
    Box(
        modifier = modifier
            .padding(bottom = 48.dp)
            .background(ChinoSurfaceHi, RectangleShape)
            .border(1.dp, ChinoBorderHi, RectangleShape)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text = text, color = ChinoFg, style = MaterialTheme.typography.bodyLarge)
    }
}
