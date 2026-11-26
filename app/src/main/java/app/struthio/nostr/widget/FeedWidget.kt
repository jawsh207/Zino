/*
 * The Following widget: the newest posts from the people you follow, as stored on the phone, in a
 * scrolling list. Tapping a post opens it, the pencil opens a new post, and the title opens Zino.
 *
 * Android draws the list through a RemoteViewsService in Zino's own process, which reads the
 * database each time the widget is told its data changed. Profile pictures come only from Zino's
 * picture cache, never the network, so someone whose picture was never shown in the app gets a
 * plain circle. New posts arrive while the app is open, and from background checks while a
 * Following widget is placed (see Core.fetchWidgetFeed).
 */
package app.struthio.nostr.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.struthio.nostr.MainActivity
import app.struthio.nostr.R
import app.struthio.nostr.Shortcuts
import app.struthio.nostr.core.Entity
import app.struthio.nostr.core.Event
import app.struthio.nostr.core.Hex
import app.struthio.nostr.core.Kind
import app.struthio.nostr.core.Nip19
import app.struthio.nostr.core.Part
import app.struthio.nostr.core.noteImages
import app.struthio.nostr.core.noteVideos
import app.struthio.nostr.core.nowSeconds
import app.struthio.nostr.core.parseContent
import app.struthio.nostr.core.repostedEvent
import app.struthio.nostr.core.sha256
import app.struthio.nostr.core.stripEmoji
import app.struthio.nostr.core.titleOf
import app.struthio.nostr.data.Core
import app.struthio.nostr.data.Notifier
import app.struthio.nostr.str
import app.struthio.nostr.ui.relativeTime
import java.io.File

/** The home screen widget listing the newest posts from people you follow. */
class FeedWidget : AppWidgetProvider() {
    /** Android asks for the widget to be drawn, as when it's placed or after a restart. */
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        draw(context, manager, ids)
        manager.notifyAppWidgetViewDataChanged(ids, R.id.feed_list)
    }

    companion object {
        private const val REQUEST_OPEN = 7301
        private const val REQUEST_COMPOSE = 7302
        private const val REQUEST_POST = 7303

        /** Draws the frame of the widgets [ids]: title, pencil, and the list Android fills in. */
        fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val account = Widgets.account(context)
            // Each post fills in its own nostr: link, so the template must stay changeable.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val post = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Notifier.EXTRA_ACCOUNT, account)
            val template = PendingIntent.getActivity(context, REQUEST_POST, post, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_feed)
                // A data address of its own per widget, or Android hands every widget the same list.
                val adapter = Intent(context, FeedService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .setData(Uri.parse("zino-widget://feed/$id"))
                @Suppress("DEPRECATION")
                views.setRemoteAdapter(R.id.feed_list, adapter)
                views.setEmptyView(R.id.feed_list, R.id.feed_empty)
                views.setPendingIntentTemplate(R.id.feed_list, template)
                views.setOnClickPendingIntent(R.id.feed_title, Widgets.open(context, null, account, REQUEST_OPEN))
                views.setOnClickPendingIntent(R.id.feed_compose, Widgets.open(context, Shortcuts.COMPOSE, account, REQUEST_COMPOSE))
                manager.updateAppWidget(id, views)
            }
        }
    }
}

/** Hands Android the list of posts for a Following widget. */
class FeedService : RemoteViewsService() {
    /** A list for one widget. */
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = FeedList(applicationContext)
}

/** One post as the widget shows it, worked out ahead so drawing a row does no database work. */
private class FeedRow(
    val open: String,
    val name: String,
    val booster: String?,
    val text: String,
    val createdAt: Long,
    val avatar: Bitmap?,
)

/** The rows of one Following widget, read from the database when the widget is told to refresh. */
private class FeedList(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    @Volatile private var rows: List<FeedRow> = emptyList()

    /** Nothing to set up: the rows are read in [onDataSetChanged]. */
    override fun onCreate() = Unit

    /**
     * Reads the newest posts from the people you follow, leaving out muted ones. Android runs this
     * on a thread of its own, so it may take its time.
     */
    override fun onDataSetChanged() {
        rows = runCatching { load(Widgets.core(context)) }.getOrDefault(emptyList())
    }

    // The rows, built from the database and the picture cache only.
    private fun load(core: Core): List<FeedRow> {
        val people = HashMap<String, Pair<String, Bitmap?>>()
        // Name and picture for each person once, however many of their posts are shown.
        fun person(pubkey: String) = people.getOrPut(pubkey) {
            val profile = core.storedProfile(pubkey)
            val name = profile?.let { stripEmoji(it.name, it.emoji).trim() }?.takeIf { it.isNotEmpty() }
                ?: Nip19.npub(pubkey).let { it.take(10) + "…" + it.takeLast(4) }
            name to profile?.picture?.let { avatar(core.imageDir, it) }
        }
        return core.store.followingFeed(MAX_ROWS * 2, nowSeconds() + 60)
            .asSequence()
            .filterNot(core::hides)
            .mapNotNull { event ->
                val boost = event.kind == Kind.REPOST || event.kind == Kind.GENERIC_REPOST
                // A boost that doesn't carry the post can't be shown without asking relays.
                val shown = if (boost) repostedEvent(event)?.takeIf { !core.hides(it) } ?: return@mapNotNull null else event
                val (name, picture) = person(shown.pubkey)
                FeedRow(
                    open = "nostr:" + Nip19.note(shown.id),
                    name = name,
                    booster = if (boost) str(R.string.widget_boosted, person(event.pubkey).first) else null,
                    text = preview(shown) { person(it).first },
                    createdAt = event.createdAt,
                    avatar = picture,
                )
            }
            .take(MAX_ROWS)
            .toList()
    }

    /** How many posts are in the list. */
    override fun getCount() = rows.size

    /** Draws the post at [position]. */
    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_feed_item)
        val row = rows.getOrNull(position) ?: return views
        views.setTextViewText(R.id.item_name, row.name)
        views.setTextViewText(R.id.item_time, relativeTime(row.createdAt))
        views.setTextViewText(R.id.item_text, row.text)
        views.setViewVisibility(R.id.item_text, if (row.text.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.item_boosted, row.booster.orEmpty())
        views.setViewVisibility(R.id.item_boosted, if (row.booster == null) View.GONE else View.VISIBLE)
        if (row.avatar != null) views.setImageViewBitmap(R.id.item_avatar, row.avatar) else views.setImageViewResource(R.id.item_avatar, R.drawable.widget_avatar)
        views.setOnClickFillInIntent(R.id.item_root, Intent().setData(Uri.parse(row.open)))
        return views
    }

    /** The default "loading" row. */
    override fun getLoadingView(): RemoteViews? = null

    /** Every row looks the same. */
    override fun getViewTypeCount() = 1

    /** Rows are told apart by their place in the list. */
    override fun getItemId(position: Int) = position.toLong()

    /** Ids change when the list does, so Android redraws every row. */
    override fun hasStableIds() = false

    /** Lets go of the rows and their pictures. */
    override fun onDestroy() {
        rows = emptyList()
    }

    private companion object {
        const val MAX_ROWS = 20
        const val AVATAR_PX = 96
    }

    /**
     * A post as one short piece of text: mentions become names, pictures and videos drop out
     * (named only when there's nothing else), and quoted posts are left out. Articles show their
     * title.
     */
    private fun preview(event: Event, name: (String) -> String): String {
        if (event.kind == Kind.ARTICLE) titleOf(event)?.let { return it }
        val media = noteImages(event).map { it.url }.toSet() + noteVideos(event)
        val text = buildString {
            for (part in parseContent(event.content)) when (part) {
                is Part.Text -> append(part.text)
                is Part.Link -> if (part.url !in media) append(part.url)
                is Part.Tag -> append('#').append(part.tag)
                is Part.Mention -> (part.entity as? Entity.Profile)?.let { append('@').append(name(it.pubkey)) }
                is Part.Lightning -> append("⚡")
            }
        }.replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\\n\\s*\\n\\s*"), "\n").trim()
        return text.ifEmpty {
            when {
                noteVideos(event).isNotEmpty() -> str(R.string.widget_video)
                noteImages(event).isNotEmpty() || event.kind == Kind.PICTURE -> str(R.string.widget_picture)
                else -> ""
            }
        }
    }

    /**
     * A round profile picture from Zino's picture cache, or null if it was never downloaded. The
     * cache names files by the SHA-256 of their address, as the screens save them.
     */
    private fun avatar(dir: File, url: String): Bitmap? = runCatching {
        val file = File(dir, Hex.encode(sha256(url.toByteArray())))
        if (file.length() <= 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= AVATAR_PX && bounds.outHeight / (sample * 2) >= AVATAR_PX) sample *= 2
        val source = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        circle(source).also { if (it !== source) source.recycle() }
    }.getOrNull()

    // Crops a picture to a circle AVATAR_PX across, as the app draws avatars.
    private fun circle(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val side = minOf(source.width, source.height).toFloat()
        val scale = AVATAR_PX / side
        val matrix = Matrix().apply {
            setTranslate(-(source.width - side) / 2f, -(source.height - side) / 2f)
            postScale(scale, scale)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        Canvas(out).drawCircle(AVATAR_PX / 2f, AVATAR_PX / 2f, AVATAR_PX / 2f, paint)
        return out
    }
}
