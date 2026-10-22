package cloud.nalet.chino.tv.ui.navigation

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app starts afresh after Connect: a TV has no plain launch intent for
 *  it, and a null one crashed the app. Under Robolectric, with the app's
 *  manifest. */
@RunWith(RobolectricTestRunner::class)
class AppRestartTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `the restart starts the app's activity on a task of its own`() {
        // The manifest declares the TV launcher only.
        assertEquals(null, context.packageManager.getLaunchIntentForPackage(context.packageName))
        val intent = appRestartIntent(context)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }
}
