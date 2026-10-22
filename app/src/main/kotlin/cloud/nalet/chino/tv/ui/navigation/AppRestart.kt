package cloud.nalet.chino.tv.ui.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import cloud.nalet.chino.tv.MainActivity

/**
 * The intent that starts the app afresh, on a task of its own: its TV
 * launcher entry - the app declares only LEANBACK_LAUNCHER, so the plain
 * launch intent is null on a TV - else its launcher entry, else its activity
 * by name. A null one crashed the app right after Connect.
 */
fun appRestartIntent(context: Context): Intent {
    val pm = context.packageManager
    val launch = pm.getLeanbackLaunchIntentForPackage(context.packageName)
        ?: pm.getLaunchIntentForPackage(context.packageName)
    val component = launch?.component ?: ComponentName(context, MainActivity::class.java)
    return Intent.makeRestartActivityTask(component)
}

/**
 * Restarts the app: the AppContainer's lazy clients (OIDC, Retrofit, the
 * server's subtitle languages) were built for the server before, and a
 * saved one does not rebuild them - the new process reads the new server,
 * and the boot gate goes on to sign-in.
 */
fun restartApp(context: Context) {
    context.startActivity(appRestartIntent(context))
    Runtime.getRuntime().exit(0)
}
