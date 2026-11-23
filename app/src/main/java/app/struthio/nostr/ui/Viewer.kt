/*
 * Media in posts: the full-screen picture viewer with pinch to zoom, the video player, and link
 * preview cards. The video player is our own rather than Android's VideoView: VideoView resets
 * its player on the main thread when it leaves the screen, and on a bad network that reset waits
 * for a stuck name lookup and freezes the app until Android closes it.
 */
package app.struthio.nostr.ui

import app.struthio.nostr.str
import app.struthio.nostr.R
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.MediaController
import java.util.concurrent.Executors
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.struthio.nostr.core.LinkInfo
import app.struthio.nostr.core.formatDuration

/** One picture that can be pinched, zoomed and moved, and swiped down to close. */
@Composable
private fun ZoomableImage(url: String, onDismiss: () -> Unit) {
    val bitmap by rememberImage(url, 1100.dp)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dragY = 0f
                    do {
                        val event = awaitPointerEvent()
                        val zooming = event.changes.size > 1
                        if (zooming || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else {
                            dragY += event.calculatePan().y
                        }
                    } while (event.changes.any { it.pressed })
                    if (dragY > DISMISS_DISTANCE) onDismiss()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            Loading(Modifier.fillMaxSize())
        } else {
            Image(
                image.asImageBitmap(),
                str(R.string.image),
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private const val DISMISS_DISTANCE = 260f

/** Full screen images: pinch to zoom, swipe between them, swipe down to close. */
@Composable
fun ImageViewer(urls: List<String>, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(start) { urls.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page -> ZoomableImage(urls[page], onClose) }
            FilledIconButton(
                onClose,
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xB3000000), contentColor = Color.White),
            ) { Icon(Icons.Rounded.Close, str(R.string.close)) }
        }
    }
}

/**
 * Tap to play with the platform's media player. Before that the card shows the note's thumbnail,
 * or, with link previews on, a still taken from the start of the video.
 */
@Composable
fun VideoCard(url: String, thumbnail: String? = null, aspect: Float = 16f / 9f, seconds: Double? = null) {
    val context = LocalContext.current
    val core = LocalCore.current
    val frame by produceState<android.graphics.Bitmap?>(null, url, thumbnail) {
        if (thumbnail == null && core.linkPreviewsEnabled()) value = core.videoFrame(url)
    }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val active = LocalScreenActive.current
    LaunchedEffect(active) { if (!active) playing = false }
    val openExternally = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")) }
        Unit
    }
    Box(
        Modifier.padding(top = 10.dp).fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(20.dp)).background(Color.Black)
            // A tap anywhere on the video belongs to the video, never to the note it's in.
            .clickable(interactionSource = null, indication = null) { if (!playing && !failed) playing = true },
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> TextButton(openExternally) { Text(str(R.string.open_in_another_app), color = Color.White) }
            playing -> {
                AndroidView(
                    factory = {
                        NetVideoView(it, url).apply {
                            onReady = { loading = false }
                            onBuffering = { buffering -> loading = buffering }
                            onFailed = { failed = true }
                        }
                    },
                    onRelease = { it.release() },
                    modifier = Modifier.fillMaxSize(),
                )
                if (loading) CircularProgressIndicator(color = Color.White)
            }
            else -> {
                if (thumbnail != null) {
                    NetImage(thumbnail, 380.dp, Modifier.fillMaxSize())
                } else {
                    frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                FilledIconButton(
                    { playing = true },
                    Modifier.size(64.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xB3000000), contentColor = Color.White),
                ) { Icon(Icons.Rounded.PlayArrow, str(R.string.play_video), Modifier.size(36.dp)) }
                seconds?.let {
                    Text(
                        formatDuration(it),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .background(Color(0xB3000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** A card for the first ordinary link in a note, loaded when it scrolls into view. */
@Composable
fun LinkPreview(url: String) {
    val core = LocalCore.current
    val context = LocalContext.current
    val info by produceState<LinkInfo?>(null, url) { value = core.linkInfo(url) }
    val card = info ?: return
    var viewing by remember { mutableStateOf(false) }
    when (card.media) {
        "video" -> {
            VideoCard(url)
            return
        }
        "image" -> {
            NetImage(
                url,
                380.dp,
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { viewing = true },
            )
            if (viewing) ImageViewer(listOf(url), 0) { viewing = false }
            return
        }
    }
    Surface(
        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            card.image?.let { NetImage(it, 380.dp, Modifier.fillMaxWidth().height(150.dp)) }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (card.description.isNotEmpty()) {
                    Text(
                        card.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    Uri.parse(url).host.orEmpty().removePrefix("www."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * A video from the network, drawn on a TextureView, with the platform's playback controls.
 * Everything that may wait on the network (opening the address, attaching the picture, and the
 * final release) runs on a thread of its own. MediaPlayer.reset and release on the main thread
 * wait for the media server to close its HTTP connection, which waits in turn for whatever read
 * is under way, including a name lookup that can hang for many seconds.
 */
private class NetVideoView(context: Context, url: String) :
    FrameLayout(context), TextureView.SurfaceTextureListener, MediaController.MediaPlayerControl {
    /** Called once the video can play. */
    var onReady: () -> Unit = {}

    /** Called with true while the video waits for more data, then false. */
    var onBuffering: (Boolean) -> Unit = {}

    /** Called when the video can't be played here. */
    var onFailed: () -> Unit = {}

    // One thread per video: a release stuck behind a hung lookup mustn't hold up the next video.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "video") }

    // Made here, on the main thread, so its callbacks arrive on the main thread.
    private val player = MediaPlayer()
    private val texture = TextureView(context)
    private val controller = MediaController(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0 && isPlaying()) pause() }
        .build()
    private var surface: Surface? = null
    private var prepared = false
    private var released = false
    private var buffered = 0
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = this
        // Claims the tap that shows the controls, so the note doesn't open its comments too.
        isClickable = true
        setOnClickListener { if (prepared && !released) controller.show() }
        controller.setMediaPlayer(this)
        controller.setAnchorView(this)
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener {
            if (released) return@setOnPreparedListener
            prepared = true
            onReady()
            start()
            controller.show()
        }
        player.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> onBuffering(true)
                MediaPlayer.MEDIA_INFO_BUFFERING_END, MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> onBuffering(false)
            }
            false
        }
        player.setOnBufferingUpdateListener { _, percent -> buffered = percent }
        player.setOnVideoSizeChangedListener { _, w, h ->
            videoWidth = w
            videoHeight = h
            fit()
        }
        player.setOnErrorListener { _, _, _ ->
            if (!released) onFailed()
            true
        }
        player.setOnCompletionListener { controller.show(0) }
        texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        worker.execute {
            try {
                player.setDataSource(context, Uri.parse(url))
                player.prepareAsync()
            } catch (e: Exception) {
                post { if (!released) onFailed() }
            }
        }
    }

    /** Scales the picture to fit inside the card without stretching it, like VideoView did. */
    private fun fit() {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w == 0f || h == 0f || videoWidth == 0 || videoHeight == 0) return
        val scale = minOf(w / videoWidth, h / videoHeight)
        texture.setTransform(Matrix().apply { setScale(videoWidth * scale / w, videoHeight * scale / h, w / 2, h / 2) })
    }

    /** Hands the picture's surface to the player once Android has made it. */
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        val made = Surface(texture)
        surface = made
        worker.execute { runCatching { player.setSurface(made) } }
    }

    /** Nothing to do: the picture is scaled again when the layout changes. */
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    /**
     * Keeps the texture alive after the view leaves the screen (false), because the player may
     * still be drawing into it until [release] finishes on the worker thread, which frees it.
     */
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = false

    /** Nothing to do for each new frame. */
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /**
     * Stops the video and frees the player, off the main thread so a slow network can't freeze
     * the app. Safe to call more than once.
     */
    fun release() {
        if (released) return
        released = true
        controller.hide()
        audio?.abandonAudioFocusRequest(focus)
        val made = surface
        val drawnOn = texture.surfaceTexture
        worker.execute {
            runCatching { player.release() }
            made?.release()
            drawnOn?.release()
        }
        worker.shutdown()
    }

    /** Plays, after asking for the sound, as any media app should. */
    override fun start() {
        if (!prepared || released) return
        audio?.requestAudioFocus(focus)
        player.start()
    }

    /** Pauses. */
    override fun pause() {
        if (prepared && !released) player.pause()
    }

    /** The video's length in ms, or -1 before it's known. */
    override fun getDuration(): Int = if (prepared && !released) player.duration else -1

    /** How far in the video is, in ms. */
    override fun getCurrentPosition(): Int = if (prepared && !released) player.currentPosition else 0

    /** Moves to [pos] ms. The player seeks on its own threads, so this doesn't wait. */
    override fun seekTo(pos: Int) {
        if (prepared && !released) player.seekTo(pos)
    }

    /** True if the video is playing now. */
    override fun isPlaying(): Boolean = prepared && !released && player.isPlaying

    /** How much of the video has downloaded, in percent. */
    override fun getBufferPercentage(): Int = buffered

    /** True: any network video can be paused. */
    override fun canPause() = true

    /** True: the controls may seek back. */
    override fun canSeekBackward() = true

    /** True: the controls may seek forward. */
    override fun canSeekForward() = true

    /** The player's audio session. */
    override fun getAudioSessionId(): Int = if (released) 0 else player.audioSessionId
}
