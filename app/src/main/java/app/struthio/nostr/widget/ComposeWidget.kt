/*
 * The Write a post widget: one pill on the home screen that opens a new post in the account in
 * use. It shows nothing that changes, so drawing it only sets where the tap goes.
 */
package app.struthio.nostr.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import app.struthio.nostr.R
import app.struthio.nostr.Shortcuts

/** The home screen widget that opens a new post. */
class ComposeWidget : AppWidgetProvider() {
    /** Android asks for the widget to be drawn, as when it's placed or after a restart. */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = draw(context, manager, ids)

    companion object {
        private const val REQUEST = 7101

        /** Draws the widgets [ids], with the tap going to the account in use. */
        fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_compose)
            val open = Widgets.open(context, Shortcuts.COMPOSE, Widgets.account(context), REQUEST)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.compose_pill, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
