package app.struthio.nostr.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayStatsTest {
    @Test
    fun countsDataAndTriesAndSurvivesSaving() {
        var saved: String? = null
        val stats = RelayStats(load = { null }, save = { saved = it })
        assertNull(stats.summary("wss://a").uptime)
        stats.traffic("wss://a", 3_000, 200)
        stats.traffic("wss://a", 1_000, 0)
        stats.attempt("wss://a", true)
        stats.attempt("wss://a", true)
        stats.attempt("wss://a", true)
        stats.attempt("wss://a", false)
        stats.flush()
        val again = RelayStats(load = { saved }, save = {})
        val s = again.summary("wss://a")
        assertEquals(4_000L, s.down)
        assertEquals(200L, s.up)
        assertEquals(75, s.uptime)
        assertEquals(0, again.summary("wss://b").tries)
    }

    @Test
    fun restsGrowEachTimeSurviveSavingAndEndOnceReached() {
        var saved: String? = null
        val stats = RelayStats(load = { null }, save = { saved = it })
        stats.attempt("wss://gone", false)
        val now = System.currentTimeMillis()
        val first = stats.restLonger("wss://gone", 60_000) - now
        val second = stats.restLonger("wss://gone", 60_000) - now
        assertTrue("first rest $first", first in 59_000..61_000)
        assertTrue("second rest $second", second in 119_000..121_000)
        stats.flush()
        val again = RelayStats(load = { saved }, save = { saved = it })
        assertTrue(again.restingUntil("wss://gone") > now + 100_000)
        // The day figures still read back next to the rests.
        assertEquals(1, again.summary("wss://gone").tries)
        again.reached("wss://gone")
        assertEquals(0L, again.restingUntil("wss://gone"))
        // Reaching it ends the streak too, so the next rest starts short again.
        val fresh = again.restLonger("wss://gone", 60_000) - System.currentTimeMillis()
        assertTrue("fresh rest $fresh", fresh in 59_000..61_000)
    }

    @Test
    fun aRelayWithoutNegentropyIsRemembered() {
        var saved: String? = null
        val stats = RelayStats(load = { null }, save = { saved = it })
        assertFalse(stats.noNegentropy("wss://a"))
        stats.refuseNegentropy("wss://a")
        stats.flush()
        val again = RelayStats(load = { saved }, save = {})
        assertTrue(again.noNegentropy("wss://a"))
        assertFalse(again.noNegentropy("wss://b"))
    }

    @Test
    fun sizesReadNaturally() {
        assertEquals("1 KB", formatBytes(10))
        assertEquals("98 KB", formatBytes(100_000))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("2.00 GB", formatBytes(2_147_483_648))
    }
}
