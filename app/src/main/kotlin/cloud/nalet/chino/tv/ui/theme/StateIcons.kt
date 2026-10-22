package cloud.nalet.chino.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/*
 * The "on" icons of the title actions (docs/chino, title actions): Lucide's
 * bookmark and heart with their shapes filled as well as stroked - a title in
 * the Watchlist, a title liked. Lucide ships outlines only; the paths are
 * Lucide's own (ISC-licensed SVG source, lucide.dev), as chino-mobile draws
 * them. The colour is the Icon's tint.
 */

private const val BOOKMARK_PATH =
    "M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z"
private const val HEART_PATH =
    "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5"

private fun filled(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = "Chino.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

object StateIcons {
    /** A title in the Watchlist. */
    val BookmarkFilled: ImageVector by lazy { filled("BookmarkFilled", BOOKMARK_PATH) }

    /** A title liked. */
    val HeartFilled: ImageVector by lazy { filled("HeartFilled", HEART_PATH) }
}
