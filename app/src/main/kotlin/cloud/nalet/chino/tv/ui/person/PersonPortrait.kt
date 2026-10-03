package cloud.nalet.chino.tv.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.ChinoText
import coil.compose.AsyncImage

/**
 * A catalogue person's picture, as chino-web's PersonAvatar draws it: their
 * portrait when the catalog has one ([url], a profile_url with the stream
 * token on it), else their initials. The portrait is laid over the initials,
 * so they show while it loads and stay when it fails — chino-api answers 404
 * for a person without a portrait. The caller sizes the box (2:3 on the
 * person screen, square on a search chip); faces sit in the upper part of a
 * portrait, so a crop keeps the top.
 */
@Composable
fun PersonPortrait(
    name: String,
    url: String?,
    initialsSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RectangleShape)
            .background(ChinoSurface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = ChinoText,
            fontWeight = FontWeight.SemiBold,
            fontSize = initialsSize,
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.6f),
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
