package app.struthio.nostr.net

import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Filter
import app.struthio.nostr.core.Keys
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.LocalSigner
import app.struthio.nostr.core.nowSeconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake relay that speaks just enough of the protocol to drive the pool. [onRequest] answers
 * REQs; [onOther] answers everything else, like EVENT and NEG-OPEN.
 */
internal class FakeRelay(
    private val onOther: (JSONArray, (String) -> Unit) -> Unit = { _, _ -> },
    private val onRequest: (JSONArray, (String) -> Unit, () -> Unit) -> Unit = { _, _, _ -> },
) {
    private val server = ServerSocket(0)
    val connections = AtomicInteger()
    val requests = AtomicInteger()
    val url = "ws://127.0.0.1:${server.localPort}"

    init {
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun OutputStream.text(message: String) {
        val data = message.toByteArray()
        write(0x81)
        if (data.size < 126) {
            write(data.size)
        } else {
            write(126); write(data.size ushr 8); write(data.size)
        }
        write(data)
        flush()
    }

    private fun serve(socket: Socket) = runCatching {
        socket.use { s ->
            val input = DataInputStream(s.getInputStream())
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) headers.append(input.read().toChar())
            val key = headers.lines().first { it.startsWith("Sec-WebSocket-Key") }.substringAfter(": ").trim()
            val out = s.getOutputStream()
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${WebSocket.acceptFor(key)}\r\n\r\n".toByteArray())
            out.flush()
            while (true) {
                val b0 = input.readUnsignedByte()
                val b1 = input.readUnsignedByte() and 0x7f
                val length = if (b1 == 126) input.readUnsignedShort() else b1
                val mask = ByteArray(4).also(input::readFully)
                val data = ByteArray(length).also(input::readFully)
                val text = String(ByteArray(length) { (data[it].toInt() xor mask[it and 3].toInt()).toByte() })
                if (b0 and 0x0f == 8) return@use
                val message = JSONArray(text)
                if (message.getString(0) == "REQ") {
                    requests.incrementAndGet()
                    onRequest(message, { out.text(it) }, { s.close() })
                } else {
                    onOther(message) { out.text(it) }
                }
            }
        }
    }

    fun close() = server.close()
}

class RelayPoolTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = RelayPool(scope) { null }.apply { activityStarted() }
    private val filter = listOf(Filter(kinds = listOf(Kind.FOLLOWS)))

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aHiddenPoolOpensNoConnections() = runBlocking {
        val relay = FakeRelay { message, send, _ -> send("""["EOSE","${message.getString(1)}"]""") }
        val hidden = RelayPool(scope) { null }
        assertTrue(hidden.query(setOf(relay.url), filter, 500) { _, _ -> }.isEmpty())
        assertEquals(0, relay.connections.get())
        relay.close()
    }

    @Test
    fun endOfStoredEventsCountsAsAnAnswer() = runBlocking {
        val relay = FakeRelay { message, send, _ -> send("""["EOSE","${message.getString(1)}"]""") }
        assertEquals(setOf(relay.url), pool.query(setOf(relay.url), filter, 3000) { _, _ -> })
        relay.close()
    }

    @Test
    fun aFailedConnectionIsNotAnAnswer() = runBlocking {
        val closedPort = ServerSocket(0).run { close(); localPort }
        assertTrue(pool.query(setOf("ws://127.0.0.1:$closedPort"), filter, 3000) { _, _ -> }.isEmpty())
    }

    @Test
    fun aRefusedRequestIsNotAnAnswerAndIsNotRepeated() = runBlocking {
        val relay = FakeRelay { message, send, _ -> send("""["CLOSED","${message.getString(1)}","blocked: no"]""") }
        assertTrue(pool.query(setOf(relay.url), filter, 3000) { _, _ -> }.isEmpty())
        delay(300)
        assertEquals(1, relay.requests.get())
        relay.close()
    }

    @Test
    fun aRelayThatDropsUsIsNotHammered() = runBlocking {
        val relay = FakeRelay { _, _, drop -> drop() }
        val sub = pool.subscribe(setOf(relay.url), filter) { _, _ -> }
        delay(6500)
        pool.close(sub)
        // Without backoff this reconnects constantly; with it, at most a couple of tries fit in 6.5s.
        assertTrue("connections: ${relay.connections.get()}", relay.connections.get() <= 3)
        relay.close()
    }

    @Test
    fun rateLimitingPausesTheRelay() = runBlocking {
        val relay = FakeRelay { message, send, _ -> send("""["CLOSED","${message.getString(1)}","rate-limited: slow down"]""") }
        assertTrue(pool.query(setOf(relay.url), filter, 3000) { _, _ -> }.isEmpty())
        val before = relay.requests.get()
        assertTrue(pool.query(setOf(relay.url), filter, 1000) { _, _ -> }.isEmpty())
        delay(200)
        assertEquals(before, relay.requests.get())
        relay.close()
    }

    @Test
    fun burstsAreSpacedAndCapped() = runBlocking {
        val relay = FakeRelay { _, _, _ -> }
        val subs = List(30) { pool.subscribe(setOf(relay.url), filter) { _, _ -> } }
        delay(2500)
        assertEquals(12, relay.requests.get())
        subs.take(5).forEach(pool::close)
        delay(1200)
        assertEquals(17, relay.requests.get())
        subs.drop(5).forEach(pool::close)
        relay.close()
    }
}

/** What a failed post tells Core: whether keeping it and trying later can help. */
class PublishRetryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = RelayPool(scope) { null }.apply { activityStarted() }
    private val signer = LocalSigner(Keys.generate())

    @After
    fun tearDown() = scope.cancel()

    /** A signed note to post. */
    private fun note() = signer.signNow(Draft(signer.pubkey, nowSeconds(), Kind.NOTE, emptyList(), "hello"))

    /** A relay that answers every post with [accepted] and [message]. */
    private fun answering(accepted: Boolean, message: String) = FakeRelay(onOther = { m, send ->
        if (m.getString(0) == "EVENT") send("""["OK","${m.getJSONObject(1).getString("id")}",$accepted,"$message"]""")
    })

    @Test
    fun noAnswerIsWorthRetrying() = runBlocking {
        val closedPort = ServerSocket(0).run { close(); localPort }
        val result = pool.publishFirst(note(), setOf("ws://127.0.0.1:$closedPort"), timeoutMs = 1500)
        assertFalse(result.ok)
        assertTrue(result.retryable)
    }

    @Test
    fun anOutrightRefusalIsNot() = runBlocking {
        val relay = answering(false, "blocked: not on the list")
        val result = pool.publishFirst(note(), setOf(relay.url), timeoutMs = 3000)
        assertFalse(result.ok)
        assertFalse(result.retryable)
        assertEquals("blocked: not on the list", result.message)
        relay.close()
    }

    @Test
    fun rateLimitingIsWorthRetrying() = runBlocking {
        val relay = answering(false, "rate-limited: slow down")
        assertTrue(pool.publishFirst(note(), setOf(relay.url), timeoutMs = 3000).retryable)
        relay.close()
    }

    @Test
    fun anAcceptedPostIsOk() = runBlocking {
        val relay = answering(true, "")
        assertTrue(pool.publishFirst(note(), setOf(relay.url), timeoutMs = 3000).ok)
        relay.close()
    }
}
