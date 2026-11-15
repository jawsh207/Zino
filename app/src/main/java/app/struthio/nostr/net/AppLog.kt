/*
 * The app's log: a short, private record of connections, sign-ins and errors, kept on the phone
 * and shared only when the user copies it.
 */
package app.struthio.nostr.net

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short record of what the app did, to copy when something goes wrong. It stays on this phone
 * until copied, holds no note or message text, and any key that slips in is removed first.
 */
object AppLog {
    private const val KEEP = 800
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private var file: File? = null

    private val SECRETS = listOf(
        Regex("nostr\\+walletconnect:\\S*"),
        Regex("\\bnsec1[a-z0-9]+"),
        Regex("\\bncryptsec1[a-z0-9]+"),
        Regex("\\b[0-9a-f]{64}\\b"),
    )

    /** Connection errors name this phone's own address, which is nobody else's business. */
    private val OWN_ADDRESS = Regex(" from /[0-9a-fA-F:.%]+ \\(port \\d+\\)")

    /** The text with keys, secrets and the device's own address hidden. */
    fun scrub(text: String): String =
        SECRETS.fold(text) { out, secret -> out.replace(secret, "[hidden]") }.replace(OWN_ADDRESS, "")

    /** Starts logging to a file, keeping the last lines from before. */
    @Synchronized
    fun start(target: File) {
        file = target
        runCatching { target.takeIf { it.exists() }?.readLines()?.takeLast(KEEP)?.map(::scrub)?.let(lines::addAll) }
        runCatching { target.writeText(lines.joinToString("\n", postfix = "\n")) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            add("crash on ${thread.name}: ${error.stackTraceToString().lines().take(14).joinToString(" | ")}")
            previous?.uncaughtException(thread, error)
        }
    }

    /** Adds a timestamped line. */
    @Synchronized
    fun add(text: String) {
        val line = time.format(Date()) + " " + scrub(text).replace('\n', ' ')
        lines.addLast(line)
        if (lines.size > KEEP) lines.removeFirst()
        runCatching { file?.appendText(line + "\n") }
    }

    /** The whole log, for sharing. */
    @Synchronized
    fun text(): String = lines.joinToString("\n")

    /** Empties the log. */
    @Synchronized
    fun clear() {
        lines.clear()
        runCatching { file?.writeText("") }
    }
}
