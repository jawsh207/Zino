/*
 * The wallet widget: the last balance the connected wallet (NIP-47) gave Zino, and when. The
 * widget can't ask the wallet itself, since that means relays and a wait, so the balance is the
 * one Zino last saw in the app; the "As of" time says how old it is. Tapping opens the Wallet tab,
 * which asks again. The eye button hides the amount, for a home screen others can see.
 */
package app.struthio.nostr.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.struthio.nostr.R
import app.struthio.nostr.Shortcuts
import app.struthio.nostr.data.Wallet
import app.struthio.nostr.str
import java.text.DateFormat
import java.text.NumberFormat

/** The home screen widget showing the wallet balance. */
class WalletWidget : AppWidgetProvider() {
    /** Android asks for the widget to be drawn, as when it's placed or after a restart. */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = draw(context, manager, ids)

    /** Handles the eye button, then anything Android sends. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TOGGLE) {
            val prefs = Widgets.prefs(context)
            prefs.edit().putBoolean(HIDDEN, !prefs.getBoolean(HIDDEN, false)).apply()
            Widgets.update(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val TOGGLE = "app.struthio.nostr.widget.TOGGLE_BALANCE"
        private const val HIDDEN = "hide_balance"
        private const val REQUEST_OPEN = 7201
        private const val REQUEST_TOGGLE = 7202

        /** Draws the widgets [ids] from the account in use's saved balance. */
        fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val core = Widgets.core(context)
            val hidden = Widgets.prefs(context).getBoolean(HIDDEN, false)
            val msats = core.prefs.getLong(Wallet.BALANCE_KEY, -1)
            val at = core.prefs.getLong(Wallet.BALANCE_AT_KEY, 0)
            val connected = core.hasWallet()
            val views = RemoteViews(context.packageName, R.layout.widget_wallet)

            val balance = when {
                !connected -> str(R.string.widget_no_wallet)
                msats < 0 -> "—"
                hidden -> str(R.string.x_sats, "•••••")
                else -> str(R.string.x_sats, NumberFormat.getIntegerInstance().format(msats / 1000))
            }
            views.setTextViewText(R.id.wallet_balance, balance)
            views.setTextViewTextSize(R.id.wallet_balance, TypedValue.COMPLEX_UNIT_SP, if (connected) 22f else 15f)
            val time = if (connected && at > 0) {
                val shown = DateUtils.formatSameDayTime(at * 1000, System.currentTimeMillis(), DateFormat.MEDIUM, DateFormat.SHORT)
                str(R.string.widget_balance_as_of, shown)
            } else {
                ""
            }
            views.setTextViewText(R.id.wallet_time, time)
            views.setViewVisibility(R.id.wallet_time, if (time.isEmpty()) View.GONE else View.VISIBLE)

            views.setViewVisibility(R.id.wallet_toggle, if (connected) View.VISIBLE else View.GONE)
            views.setImageViewResource(R.id.wallet_toggle, if (hidden) R.drawable.ic_widget_eye_off else R.drawable.ic_widget_eye)
            views.setContentDescription(R.id.wallet_toggle, str(if (hidden) R.string.widget_show_balance else R.string.widget_hide_balance))
            val toggle = Intent(context, WalletWidget::class.java).setAction(TOGGLE)
            views.setOnClickPendingIntent(
                R.id.wallet_toggle,
                PendingIntent.getBroadcast(context, REQUEST_TOGGLE, toggle, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            views.setOnClickPendingIntent(R.id.widget_root, Widgets.open(context, Shortcuts.WALLET, core.accountId, REQUEST_OPEN))
            manager.updateAppWidget(ids, views)
        }
    }
}
