/*
 * Looking up the app's text in the phone's language. Every word Zino shows lives in
 * res/values/strings.xml, with translations beside it in res/values-xx folders, and code asks for
 * it with str(R.string.name). Unlike Compose's stringResource, this works anywhere: in click
 * handlers, in Core's error messages and in notifications. A change of language restarts the screen,
 * so nothing shown keeps the old language.
 */
package app.struthio.nostr

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/** Where the app's text comes from, set as the app starts. */
object Texts {
    @Volatile
    private var resources: Resources? = null

    /** Uses this context's text, unless the app already set it up. */
    fun use(context: Context) {
        if (resources == null) resources = context.applicationContext.resources
    }

    /** The resources to read text from. */
    internal fun get(): Resources = resources ?: error("Texts.use was not called")
}

/** A piece of the app's text, in the phone's language, with any [args] filled in. */
fun str(@StringRes id: Int, vararg args: Any?): String =
    if (args.isEmpty()) Texts.get().getString(id) else Texts.get().getString(id, *args)

/** Text that changes with a number, like "1 vote" and "5 votes", in the phone's language. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = Texts.get().getQuantityString(id, count, *args)
