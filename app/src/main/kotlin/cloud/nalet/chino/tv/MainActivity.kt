package cloud.nalet.chino.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.tv.material3.Surface
import cloud.nalet.chino.tv.ui.navigation.ChinoTvNavHost
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme

/**
 * Process-wide hook so PlayerScreen can claim DPAD_UP regardless of which
 * View has focus inside MainActivity. PlayerView grabs focus when its
 * controller hides and consumes DPAD keys before they reach Compose or any
 * View.OnKeyListener; intercepting at the Activity's dispatchKeyEvent — the
 * first point in the dispatch chain — sidesteps that race. Callers register
 * a handler when the player mounts and clear it on unmount.
 */
object KeyEventBus {
    @Volatile private var handler: ((KeyEvent) -> Boolean)? = null
    fun register(h: (KeyEvent) -> Boolean) { handler = h }
    fun clear(h: (KeyEvent) -> Boolean) { if (handler === h) handler = null }
    fun dispatch(e: KeyEvent): Boolean = handler?.invoke(e) ?: false
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ChinoTvApp).container
        setContent {
            ChinoTvTheme {
                Surface { ChinoTvNavHost(container = container) }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        KeyEventBus.dispatch(event) || super.dispatchKeyEvent(event)
}
