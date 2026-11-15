package app.struthio.nostr.data

import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Keys
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.LocalSigner
import app.struthio.nostr.core.nowSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Core's own logic, run without any relay: private messages, and posts kept for later. */
@RunWith(RobolectricTestRunner::class)
class CoreTest {
    private val core = Core(RuntimeEnvironment.getApplication(), "coretest")
    private val alice = LocalSigner(Keys.generate())
    private val bob = LocalSigner(Keys.generate())
    private val room = roomKey(setOf(alice.pubkey, bob.pubkey))

    @After
    fun tearDown() = core.close()

    /** A rumor from Alice to Bob, sealed and wrapped for Bob. */
    private suspend fun fromAlice(kind: Int, content: String, tags: List<List<String>> = emptyList()) =
        core.wrapFor(Draft(alice.pubkey, nowSeconds(), kind, listOf(listOf("p", bob.pubkey)) + tags, content).toJson(), bob.pubkey, alice)

    @Test
    fun aMessageIsOpenedStoredAndNotOpenedTwice() = runBlocking {
        core.signer.value = bob
        val earlier = "e".repeat(64)
        val wrap = fromAlice(Kind.DM, "hi Bob", listOf(listOf("e", earlier)))
        // The outside reveals only the recipient.
        assertEquals(listOf(listOf("p", bob.pubkey)), wrap.tags)
        assertTrue(wrap.pubkey != alice.pubkey)
        assertEquals(alice.pubkey, core.receiveWrap(wrap))
        val message = core.store.messages(room, 10).single()
        assertEquals("hi Bob", message.content)
        assertEquals(alice.pubkey, message.sender)
        assertEquals(earlier, message.replyTo)
        assertNull(core.receiveWrap(wrap))
        assertEquals(1, core.store.messages(room, 10).size)
    }

    @Test
    fun reactionsInsideWrapsGoOnTheirMessage() = runBlocking {
        core.signer.value = bob
        val target = "f".repeat(64)
        core.receiveWrap(fromAlice(Kind.REACTION, "🔥", listOf(listOf("e", target), listOf("k", "14"))))
        assertEquals("🔥", core.store.dmReactions(room).getValue(target).single().content)
        // A dislike isn't shown.
        core.receiveWrap(fromAlice(Kind.REACTION, "-", listOf(listOf("e", "d".repeat(64)))))
        assertNull(core.store.dmReactions(room)["d".repeat(64)])
    }

    @Test
    fun aWrapForSomeoneElseCannotBeOpened() = runBlocking {
        val carol = LocalSigner(Keys.generate())
        core.signer.value = carol
        assertNull(core.receiveWrap(fromAlice(Kind.DM, "not for Carol")))
        assertTrue(core.store.rooms(carol.pubkey).isEmpty())
    }

    @Test
    fun aPostWithNoConnectionIsKeptAndShown() = runBlocking {
        // The app isn't on screen, so the relays never connect: just like having no network.
        core.signer.value = alice
        val event = core.publish(Draft(alice.pubkey, nowSeconds(), Kind.NOTE, emptyList(), "sent later"))
        assertEquals(1, core.waiting.value)
        assertEquals(event.id, core.store.outgoing().single().first.id)
        delay(500)
        assertNotNull(core.store.event(event.id))
        // Still no connection: trying again keeps it.
        core.sendWaiting()
        assertEquals(1, core.waiting.value)
    }
}
