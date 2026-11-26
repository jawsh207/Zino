package app.struthio.nostr

import android.content.Intent
import android.content.pm.ShortcutManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Launcher shortcuts: which links their actions open, and that they're put on the icon. */
@RunWith(RobolectricTestRunner::class)
class ShortcutsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun actionsBecomeZinoLinks() {
        assertEquals(Shortcuts.LINK_COMPOSE, Shortcuts.linkOf(Intent(Shortcuts.COMPOSE)))
        assertEquals(Shortcuts.LINK_MESSAGES, Shortcuts.linkOf(Intent(Shortcuts.MESSAGES)))
        assertEquals(Shortcuts.LINK_SCAN, Shortcuts.linkOf(Intent(Shortcuts.SCAN)))
        assertEquals(Shortcuts.LINK_WALLET, Shortcuts.linkOf(Intent(Shortcuts.WALLET)))
    }

    @Test
    fun otherIntentsKeepTheirLink() {
        val link = "nostr:npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
        assertEquals(link, Shortcuts.linkOf(Intent(Intent.ACTION_VIEW, Uri.parse(link))))
        assertNull(Shortcuts.linkOf(Intent(Intent.ACTION_MAIN)))
        assertNull(Shortcuts.linkOf(null))
    }

    @Test
    fun threeShortcutsGoOnTheIcon() {
        Shortcuts.install(context)
        val manager = context.getSystemService(ShortcutManager::class.java)
        assertEquals(
            setOf(Shortcuts.COMPOSE, Shortcuts.MESSAGES, Shortcuts.SCAN),
            manager.dynamicShortcuts.map { it.id }.toSet(),
        )
        // Installing again with the same names leaves them as they are.
        Shortcuts.install(context)
        assertEquals(3, manager.dynamicShortcuts.size)
    }
}
