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
import cloud.nalet.chino.tv.data.api.LoadFailure

/**
 * A screen whose content did not load, as the design system writes it: what
 * is true ([title], "couldn't load …"), what to do - for its [failure]: check
 * the connection where the server was not reached, try again in a moment
 * where it answered with an error - the way on, "try again", which has the
 * focus first and calls [onRetry], and the technical reason under details.
 * The one action of the screen, so filled.
 *
 * [focusFirst] false leaves the focus where it is: the screenshot test's look
 * at rest.
 */
@Composable
fun LoadError(
    title: String,
    failure: LoadFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    focusFirst: Boolean = true,
) {
    ErrorScreen(
        title = title,
        body = stringResource(
            if (failure.kind == LoadFailure.Kind.Connection) R.string.load_error_body else R.string.load_error_body_server,
        ),
        action = stringResource(R.string.load_error_retry),
        onAction = onRetry,
        reason = failure.reason,
        modifier = modifier,
        focusFirst = focusFirst,
    )
}

/**
 * What a screen shows is not in the library any more (the server's 404):
 * trying again cannot bring it back. [title] says so; no more to read, and
 * one action, "back", with the focus first, which calls [onBack]; the
 * technical [reason] under details.
 */
@Composable
fun GoneError(
    title: String,
    reason: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    focusFirst: Boolean = true,
) {
    ErrorScreen(
        title = title,
        body = null,
        action = stringResource(R.string.load_error_back),
        onAction = onBack,
        reason = reason,
        modifier = modifier,
        focusFirst = focusFirst,
    )
}

@Composable
private fun ErrorScreen(
    title: String,
    body: String?,
    action: String,
    onAction: () -> Unit,
    reason: String?,
    modifier: Modifier,
    focusFirst: Boolean,
) {
    val first = remember { FocusRequester() }
    if (focusFirst) {
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
    Box(modifier = modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = ChinoFg)
            if (body != null) {
                Text(text = body, style = MaterialTheme.typography.bodyLarge, color = ChinoText)
            }
            Spacer(Modifier.height(4.dp))
            ChinoButton(
                onClick = onAction,
                modifier = Modifier.focusRequester(first),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                filled = true,
            ) {
                Text(text = action)
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
