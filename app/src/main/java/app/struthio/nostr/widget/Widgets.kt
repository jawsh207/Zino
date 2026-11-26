/*
 * What Zino's home screen widgets share: redrawing them all at once, which account they show, and
 * what tapping one opens. The widgets are plain Android app widgets (AppWidgetProvider and
 * RemoteViews) rather than Jetpack Glance, so they need no extra library.
 *
 * Widgets never go to the network themselves. They draw what's already on the phone: the
 * database for the Following widget, and the last balance the wallet gave for the wallet widget.
 * They're redrawn when the app goes to the background, when the account changes, when the wallet
 * answers, and after each background check.
 */
package app.struthio.nostr.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import app.struthio.nostr.MainActivity
import app.struthio.nostr.R
import app.struthio.nostr.Shortcuts
import app.struthio.nostr.ZinoApp
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.Notifier
import app.struthio.nostr.net.AppLog

/** Shared helpers for the home screen widgets. */
object Widgets {
    /** Redraws every Zino widget on the home screen. Cheap, and safe from any thread. */
    fun update(context: Context) {
        val app = context.applicationContext
        try {
            val manager = AppWidgetManager.getInstance(app) ?: return
            ComposeWidget.draw(app, manager, ids(app, manager, ComposeWidget::class.java))
            WalletWidget.draw(app, manager, ids(app, manager, WalletWidget::class.java))
            val feed = ids(app, manager, FeedWidget::class.java)
            if (feed.isNotEmpty()) {
                FeedWidget.draw(app, manager, feed)
                manager.notifyAppWidgetViewDataChanged(feed, R.id.feed_list)
            }
        } catch (e: Exception) {
            // A launcher that's restarting can refuse for a moment; the next redraw catches up.
            AppLog.add("widget update failed: $e")
        }
    }

    /** True if a Following widget is on the home screen, so background checks fetch posts for it. */
    fun hasFeed(context: Context): Boolean = runCatching {
        val manager = AppWidgetManager.getInstance(context.applicationContext) ?: return false
        ids(context, manager, FeedWidget::class.java).isNotEmpty()
    }.getOrDefault(false)

    // The ids of the placed widgets of one kind.
    private fun ids(context: Context, manager: AppWidgetManager, kind: Class<*>): IntArray =
        manager.getAppWidgetIds(ComponentName(context, kind))

    /** The Core of the account the widgets show: the one in use. */
    fun core(context: Context): Core = (context.applicationContext as ZinoApp).core

    /** The id of the account in use, without opening its Core. */
    fun account(context: Context): String = (context.applicationContext as ZinoApp).accounts.activeId

    /** Settings that belong to the widgets rather than to an account, like hiding the balance. */
    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    /**
     * What tapping opens: Zino with [action] (one of [Shortcuts]), or just Zino when null, in
     * [account], so a tap still lands in the right account if it was switched meanwhile.
     */
    fun open(context: Context, action: String?, account: String, request: Int): PendingIntent {
        val intent = (if (action != null) Shortcuts.intent(context, action) else Intent(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Notifier.EXTRA_ACCOUNT, account)
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
