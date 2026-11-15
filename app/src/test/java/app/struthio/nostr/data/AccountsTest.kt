package app.struthio.nostr.data

import app.struthio.nostr.core.Keys
import app.struthio.nostr.core.LocalSigner
import app.struthio.nostr.core.Nip19
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Several accounts on one phone: kept apart, listed, and removed cleanly. */
@RunWith(RobolectricTestRunner::class)
class AccountsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val accounts = Accounts(context)
    private val opened = ArrayList<Core>()

    @After
    fun tearDown() = opened.forEach { it.close() }

    /** A Core for one account, closed after the test. */
    private fun open(id: String) = Core(context, id, accounts).also { opened += it }

    @Test
    fun thereIsAlwaysAFirstAccount() {
        assertEquals(listOf(Accounts.FIRST), accounts.list.value.map { it.id })
        assertEquals(Accounts.FIRST, accounts.activeId)
        // Its files keep the names from before there were accounts, so updating keeps everything.
        assertEquals("struthio.db", Accounts.databaseName(Accounts.FIRST))
        assertEquals("app", Accounts.settingsName(Accounts.FIRST))
        assertEquals("secure", Accounts.secretsName(Accounts.FIRST))
    }

    @Test
    fun accountsAreRememberedAndRemoved() {
        val added = accounts.add()
        accounts.setActive(added.id)
        accounts.update(added.id, "a".repeat(64), "Alice", null)
        val again = Accounts(context)
        assertEquals(added.id, again.activeId)
        assertEquals("Alice", again.list.value.last().name)
        assertEquals(added.id, again.holding("a".repeat(64), except = Accounts.FIRST)?.id)
        assertNull(again.holding("a".repeat(64), except = added.id))
        assertTrue(again.remove(added.id))
        assertEquals(Accounts.FIRST, again.activeId)
        // The last account is emptied rather than removed.
        again.update(Accounts.FIRST, "b".repeat(64))
        assertFalse(again.remove(Accounts.FIRST))
        assertEquals(listOf(Account(Accounts.FIRST)), again.list.value)
    }

    @Test
    fun eachAccountHasItsOwnFiles() {
        val second = accounts.add()
        val one = open(Accounts.FIRST)
        val two = open(second.id)
        one.prefs.edit().putString("probe", "one").commit()
        one.store.addWrap("only-in-one")
        assertNull(two.prefs.getString("probe", null))
        assertFalse(two.store.hasWrap("only-in-one"))
        accounts.deleteFiles(second.id)
        assertFalse(context.getDatabasePath(Accounts.databaseName(second.id)).exists())
    }

    @Test
    fun theSamePersonCannotBeAddedTwice() = runBlocking {
        val pubkey = LocalSigner(Keys.generate()).pubkey
        val second = accounts.add()
        accounts.update(second.id, pubkey)
        val first = open(Accounts.FIRST)
        try {
            first.signIn(Nip19.npub(pubkey))
            fail("signed in twice as the same person")
        } catch (e: UserError) {
            assertTrue(e.message!!.contains("already"))
        }
        assertNull(first.signer.value)
    }
}
