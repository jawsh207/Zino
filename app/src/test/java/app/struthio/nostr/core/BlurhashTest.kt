package app.struthio.nostr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blurred previews: decoding blurhashes, and finding them in imeta tags. */
class BlurhashTest {
    private val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    /** A number in base 83, [length] characters long. */
    private fun encode83(value: Int, length: Int): String {
        var v = value
        val out = CharArray(length)
        for (i in length - 1 downTo 0) {
            out[i] = chars[v % 83]
            v /= 83
        }
        return String(out)
    }

    @Test
    fun aSingleColourDecodesToThatColour() {
        // One component, so every pixel is the average colour.
        val hash = "00" + encode83(0xFF8000, 4)
        val pixels = Blurhash.decode(hash, 4, 4)!!
        assertTrue(pixels.all { it == 0xFFFF8000.toInt() })
    }

    @Test
    fun theReferenceExampleDecodes() {
        val pixels = Blurhash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 8, 6)
        assertNotNull(pixels)
        assertEquals(48, pixels!!.size)
        assertTrue(pixels.all { it ushr 24 == 0xFF })
        // Not all one colour: the waves add detail.
        assertTrue(pixels.toSet().size > 1)
    }

    @Test
    fun brokenHashesAreRefused() {
        assertNull(Blurhash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdn", 8, 6))
        assertNull(Blurhash.decode("", 8, 6))
        assertNull(Blurhash.decode("00\"\"\"\"", 4, 4))
    }

    @Test
    fun imetaCarriesTheBlurhash() {
        val tags = listOf(listOf("imeta", "url https://a.example/p.jpg", "m image/jpeg", "blurhash LEHV6nWB2yk8pyo0adR*.7kCMdnj"))
        val note = Event("0".repeat(64), "1".repeat(64), 1, Kind.NOTE, tags, "look https://a.example/p.jpg", "")
        val picture = Event("0".repeat(64), "1".repeat(64), 1, Kind.PICTURE, tags, "", "")
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", noteImages(note).single().blurhash)
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", pictures(picture).single().blurhash)
    }
}
