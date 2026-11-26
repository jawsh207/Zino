/*
 * The long-press shortcuts on Zino's launcher icon (New post, Messages, Scan QR code) and the
 * actions they and the home screen widgets open Zino with. Each action is turned into a "zino:"
 * link, which travels to the screens the same way a nostr: link does, so the screens have one
 * place that opens things.
 *
 * The shortcuts are added from code rather than from res/xml: a shortcut written in XML has to
 * name the app's package, and test builds have a ".debug" one of their own.
 */
package app.struthio.nostr

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/** The things Zino can be opened straight to from a shortcut or a widget. */
object Shortcuts {
    const val COMPOSE = "app.struthio.nostr.action.COMPOSE"
    const val MESSAGES = "app.struthio.nostr.action.MESSAGES"
    const val SCAN = "app.struthio.nostr.action.SCAN"
    const val WALLET = "app.struthio.nostr.action.WALLET"

    /** The in-app links the actions stand for. Only Zino's own screens read these. */
    const val LINK_COMPOSE = "zino:compose"
    const val LINK_MESSAGES = "zino:messages"
    const val LINK_SCAN = "zino:scan"
    const val LINK_WALLET = "zino:wallet"

    /** What to open for [intent]: one of the links above, a nostr: link, or nothing. */
    fun linkOf(intent: Intent?): String? = when (intent?.action) {
        COMPOSE -> LINK_COMPOSE
        MESSAGES -> LINK_MESSAGES
        SCAN -> LINK_SCAN
        WALLET -> LINK_WALLET
        else -> intent?.dataString
    }

    /** An intent that opens Zino with [action]. */
    fun intent(context: Context, action: String): Intent =
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Puts the shortcuts on the launcher icon. Android limits how often an app in the background
     * may change them, and this runs every time the app starts, background checks included, so
     * they're only replaced when their names differ, as after a language change.
     */
    fun install(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val wanted = listOf(
            Triple(COMPOSE, R.string.new_post, R.drawable.ic_shortcut_compose),
            Triple(MESSAGES, R.string.messages, R.drawable.ic_shortcut_messages),
            Triple(SCAN, R.string.scan_qr_code, R.drawable.ic_shortcut_scan),
        ).mapIndexed { rank, (action, label, icon) ->
            ShortcutInfo.Builder(context, action)
                .setShortLabel(str(label))
                .setIcon(Icon.createWithResource(context, icon))
                .setIntent(intent(context, action))
                .setRank(rank)
                .build()
        }
        val current = manager.dynamicShortcuts.map { it.id to it.shortLabel?.toString() }.toSet()
        if (current == wanted.map { it.id to it.shortLabel?.toString() }.toSet()) return
        runCatching { manager.setDynamicShortcuts(wanted) }
    }
}
