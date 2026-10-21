package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.R

/**
 * A screen whose content did not load, as the design system writes it: what
 * is true ([title], "couldn't load …"), what to do (check the connection to
 * the server, then try again), the way on - "try again", which has the focus
 * first and calls [onRetry] - and the technical [reason] under details. The
 * one action of the screen, so filled.
 *
 * [focusFirst] false leaves the focus where it is: the screenshot test's look
 * at rest.
 */
@Composable
fun LoadError(
    title: String,
    reason: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    focusFirst: Boolean = true,
) {
    val retry = remember { FocusRequester() }
    if (focusFirst) {
        LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
    }
    Box(modifier = modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = ChinoFg)
            Text(
                text = stringResource(R.string.load_error_body),
                style = MaterialTheme.typography.bodyLarge,
                color = ChinoText,
            )
            Spacer(Modifier.height(4.dp))
            ChinoButton(
                onClick = onRetry,
                modifier = Modifier.focusRequester(retry),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                filled = true,
            ) {
                Text(text = stringResource(R.string.load_error_retry))
            }
            if (!reason.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.load_error_details),
                        style = MaterialTheme.typography.titleSmall,
                        color = ChinoMuted,
                    )
                    Text(text = reason, style = MaterialTheme.typography.bodySmall, color = ChinoMuted)
                }
            }
        }
    }
}
