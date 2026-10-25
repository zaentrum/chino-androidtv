package cloud.nalet.chino.tv.ui.theme

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.compose.runtime.staticCompositionLocalOf
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

/** Goes to sign-in for the account chosen: the NavHost's. */
val LocalSignIn = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * A screen whose content did not load, as the design system writes it, for
 * its [failure] - by the HTTP status the server answered, the technical
 * reason under details on each:
 *
 * - no answer, a timeout (408 too), an error of the server (5xx), the server
 *   busy (429), a request it did not take (another 4xx): [title], what
 *   couldn't load in the screen's own word ("couldn't load the movies"),
 *   the reason only ("Can't reach the server.") - "try again" is the
 *   button, which calls [onRetry]; only an app update, which no button
 *   offers, is said in words;
 * - not in the library any more (404, 410), where the screen knows [what]
 *   it shows ("this title", "this person"): it says so, and "back";
 *   elsewhere a request the server did not take;
 * - no access (403): "you don't have access to" [what], else "this", and
 *   "back";
 * - signed out (401), where going to sign-in by itself (SignInLapses) could
 *   not spare the screen: "you're signed out", and "sign in" (LocalSignIn).
 *
 * The one action of each, filled, with the focus first. "back" is [onBack],
 * else what the remote's BACK does. [focusFirst] false leaves the focus
 * where it is: the screenshot test's look at rest.
 */
@Composable
fun LoadError(
    title: String,
    failure: LoadFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    what: String? = null,
    onBack: (() -> Unit)? = null,
    focusFirst: Boolean = true,
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val back: () -> Unit = onBack ?: { dispatcher?.onBackPressed() }
    val retry = stringResource(R.string.load_error_retry)
    val backLabel = stringResource(R.string.load_error_back)
    when (failure.kind) {
        LoadFailure.Kind.SignedOut -> ErrorScreen(
            title = stringResource(R.string.signed_out_title),
            body = stringResource(R.string.signed_out_body),
            action = stringResource(R.string.signed_out_action),
            onAction = LocalSignIn.current,
            reason = failure.reason,
            modifier = modifier,
            focusFirst = focusFirst,
        )
        LoadFailure.Kind.Forbidden -> ErrorScreen(
            title = stringResource(R.string.forbidden_title, what ?: stringResource(R.string.forbidden_this)),
            body = null,
            action = backLabel,
            onAction = back,
            reason = failure.reason,
            modifier = modifier,
            focusFirst = focusFirst,
        )
        LoadFailure.Kind.NotFound -> if (what != null) {
            ErrorScreen(
                title = stringResource(R.string.gone_title, what),
                body = null,
                action = backLabel,
                onAction = back,
                reason = failure.reason,
                modifier = modifier,
                focusFirst = focusFirst,
            )
        } else {
            ErrorScreen(title, stringResource(R.string.load_error_body_rejected), retry, onRetry, failure.reason, modifier, focusFirst)
        }
        LoadFailure.Kind.Connection ->
            ErrorScreen(title, stringResource(R.string.load_error_body), retry, onRetry, failure.reason, modifier, focusFirst)
        LoadFailure.Kind.Server ->
            ErrorScreen(title, stringResource(R.string.load_error_body_server), retry, onRetry, failure.reason, modifier, focusFirst)
        LoadFailure.Kind.Busy ->
            ErrorScreen(title, stringResource(R.string.load_error_body_busy), retry, onRetry, failure.reason, modifier, focusFirst)
        LoadFailure.Kind.Rejected ->
            ErrorScreen(title, stringResource(R.string.load_error_body_rejected), retry, onRetry, failure.reason, modifier, focusFirst)
    }
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
