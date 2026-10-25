package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** The dim behind a menu or a question over the page. */
val ScrimColor = Color(0xCC000000)

/** The dim behind a menu or a question over the page, over all of it. */
fun Modifier.scrim(): Modifier = background(ScrimColor)
