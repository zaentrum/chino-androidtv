package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Chino brand mark — `>c` glyph (cloud-blue chevron + light-grey c) in
 *  JetBrains Mono Bold per the nalet design system §01. Same mark
 *  the mobile app uses for its rail header — visual cross-platform tie.
 *  Used in the LibraryScreen top action row so the brand is always
 *  visible on the canvas. */
@Composable
fun LogoMark(sizeDp: Int = 28) {
    Box(
        modifier = Modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = chinoGlyph(),
            fontFamily = ChinoMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (sizeDp * 0.72).sp,
        )
    }
}

private fun chinoGlyph(): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = Color(0xFF58A6FF))) { append(">") }
    withStyle(SpanStyle(color = Color(0xFFC9D1D9))) { append("c") }
}
