/*
 * The application object. It creates Core, which holds the app's state, once for the whole
 * process, and starts the log.
 */
package app.struthio.nostr

import android.app.Application
import app.struthio.nostr.data.Core
import app.struthio.nostr.net.AppLog
import java.io.File

/** Holds Core for the whole app. */
class ZinoApp : Application() {
    val core: Core by lazy { Core(this) }

    /** Starts the log as the app starts. */
    override fun onCreate() {
        super.onCreate()
        AppLog.start(File(filesDir, "log.txt"))
    }
}
