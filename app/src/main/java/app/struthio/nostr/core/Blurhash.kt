/*
 * Blurhash decoding, for the soft, blurred preview shown while a picture loads. Posts can carry a
 * picture's blurhash in its imeta tag (NIP-92): a short text that holds a few colour waves, enough
 * to paint a tiny blurred version of the picture with no download at all.
 * The format is described at https://github.com/woltapp/blurhash.
 */
package app.struthio.nostr.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/** Turns a blurhash into a small picture. */
object Blurhash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    /**
     * The colours of a [width] by [height] picture, as ARGB pixels row by row, or null if the text
     * isn't a valid blurhash. Small sizes are enough: the picture is stretched and stays blurry.
     */
    fun decode(hash: String, width: Int, height: Int): IntArray? {
        if (hash.length < 6 || width <= 0 || height <= 0) return null
        val size = decode83(hash, 0, 1) ?: return null
        val numY = size / 9 + 1
        val numX = size % 9 + 1
        if (hash.length != 4 + 2 * numX * numY) return null
        val maxValue = ((decode83(hash, 1, 2) ?: return null) + 1) / 166f
        val colors = Array(numX * numY) { FloatArray(3) }
        colors[0] = dc(decode83(hash, 2, 6) ?: return null)
        for (i in 1 until numX * numY) {
            colors[i] = ac(decode83(hash, 4 + i * 2, 6 + i * 2) ?: return null, maxValue)
        }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numY) {
                    val cy = cos(PI * y * j / height).toFloat()
                    for (i in 0 until numX) {
                        val basis = cos(PI * x * i / width).toFloat() * cy
                        val c = colors[i + j * numX]
                        r += c[0] * basis
                        g += c[1] * basis
                        b += c[2] * basis
                    }
                }
                pixels[x + y * width] = (0xff shl 24) or (toSrgb(r) shl 16) or (toSrgb(g) shl 8) or toSrgb(b)
            }
        }
        return pixels
    }

    /** A base-83 number from part of the text, or null if a character isn't one of the 83. */
    private fun decode83(text: String, from: Int, to: Int): Int? {
        var value = 0
        for (i in from until to) {
            val digit = CHARS.indexOf(text[i])
            if (digit < 0) return null
            value = value * 83 + digit
        }
        return value
    }

    /** The average colour, in linear light. */
    private fun dc(value: Int) = floatArrayOf(toLinear(value shr 16), toLinear((value shr 8) and 255), toLinear(value and 255))

    /** One colour wave's strength for red, green and blue. */
    private fun ac(value: Int, maxValue: Float) = floatArrayOf(
        signedPow((value / (19 * 19) - 9) / 9f) * maxValue,
        signedPow(((value / 19) % 19 - 9) / 9f) * maxValue,
        signedPow((value % 19 - 9) / 9f) * maxValue,
    )

    // Squares a value, keeping its sign.
    private fun signedPow(v: Float) = (v * v).withSign(v)

    /** An sRGB channel (0 to 255) in linear light. */
    private fun toLinear(channel: Int): Float {
        val v = channel / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    /** A linear-light value back to an sRGB channel, 0 to 255. */
    private fun toSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val s = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1 / 2.4f) - 0.055f
        return (s * 255 + 0.5f).toInt().coerceIn(0, 255)
    }
}
