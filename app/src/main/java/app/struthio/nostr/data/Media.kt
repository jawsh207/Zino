/*
 * Uploading photos and files to Blossom media servers (NIP-B7 and BUD-02). Photos are turned
 * upright and re-encoded first, which also removes hidden details like where they were taken.
 */
package app.struthio.nostr.data

import app.struthio.nostr.str
import app.struthio.nostr.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.struthio.nostr.core.Draft
import app.struthio.nostr.core.Hex
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.blossomAuthTags
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.sha256
import app.struthio.nostr.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64

/**
 * What a server returns for an upload: its address, hash, type, size and, for images, dimensions.
 */
data class Upload(val url: String, val sha256: String, val type: String, val size: Int, val width: Int, val height: Int)

/** A file ready to upload: its bytes and type, and dimensions if it's an image. */
class Prepared(val bytes: ByteArray, val type: String, val width: Int, val height: Int)

/** Blossom uploads (NIP-B7). Photos are re-encoded first, which drops EXIF data such as location. */
class MediaUploader(private val core: Core, private val context: Context) {

    /** Reads a picked file's bytes. */
    private fun read(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw UserError(str(R.string.could_not_open_that_file))

    /** Which way up the photo was taken, from its EXIF data. */
    private fun orientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** The photo turned the right way up. */
    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Turns a picked photo into an upright, size-limited JPEG with no EXIF data. */
    suspend fun prepareImage(uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val bytes = read(uri)
        if (bytes.size > MAX_BYTES) throw UserError(str(R.string.that_file_is_too_large))
        val mime = context.contentResolver.getType(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (mime == "image/gif") return@withContext Prepared(bytes, mime, bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw UserError(str(R.string.that_image_could_not_be_read))
        val straight = upright(decoded, orientation(bytes))
        val scale = MAX_EDGE.toFloat() / maxOf(straight.width, straight.height)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(straight, (straight.width * scale).toInt(), (straight.height * scale).toInt(), true)
        } else {
            straight
        }
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Prepared(out.toByteArray(), "image/jpeg", sized.width, sized.height)
    }

    /** Reads a picked file to send as it is; photos go through [prepareImage] instead. */
    suspend fun prepareFile(uri: Uri): Prepared = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (type.startsWith("image/") && type != "image/gif") return@withContext prepareImage(uri)
        val bytes = read(uri)
        if (bytes.size > MAX_BYTES) throw UserError(str(R.string.that_file_is_too_large))
        Prepared(bytes, type, 0, 0)
    }

    /** Uploads a prepared file and keeps the image dimensions with the result. */
    suspend fun upload(prepared: Prepared): Upload {
        val done = upload(prepared.bytes, prepared.type)
        return done.copy(width = prepared.width, height = prepared.height)
    }

    /** Tries each server in turn: blobs are addressed by hash, so any of them will do. */
    suspend fun upload(bytes: ByteArray, type: String): Upload = withContext(Dispatchers.IO) {
        val active = core.signer.value ?: throw UserError(str(R.string.sign_in_first))
        val hash = Hex.encode(sha256(bytes))
        var failure: String? = null
        for (server in core.mediaServers()) {
            try {
                val host = URI(server).host.lowercase()
                val auth = active.sign(Draft(active.pubkey, nowSeconds(), Kind.BLOSSOM_AUTH, blossomAuthTags(hash, host, nowSeconds()), "Upload"))
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(auth.toJson().toByteArray())
                val result = Http.put(
                    server.trimEnd('/') + "/upload",
                    bytes,
                    type,
                    mapOf("Authorization" to "Nostr $token", "X-SHA-256" to hash),
                )
                if (result.optString("sha256") != hash) throw UserError(str(R.string.the_server_changed_the_file))
                val url = result.getString("url")
                require(url.startsWith("https://"))
                return@withContext Upload(url, hash, type, bytes.size, 0, 0)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                failure = e.message
            }
        }
        throw UserError(failure?.let { str(R.string.upload_failed_x, it) } ?: str(R.string.upload_failed))
    }

    private companion object {
        const val MAX_EDGE = 2048
        const val MAX_BYTES = 25 * 1024 * 1024
    }
}
