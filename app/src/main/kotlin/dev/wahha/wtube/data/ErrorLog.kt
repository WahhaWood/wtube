package dev.wahha.wtube.data

import android.content.Context
import android.os.Build
import android.os.Process
import dev.wahha.wtube.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone's own error log for "Share log": crashes are kept in a file (the process that crashed takes its
 * logcat with it), everything else comes from this process's logcat when the log is shared. Nothing leaves
 * the phone on its own; stream links and cookies are cut out before anything is written or shared.
 */
object ErrorLog {
    private const val MAX_CRASH_BYTES = 64 * 1024
    // Warnings and errors from everyone in the process, plus our own notable events at info level.
    private val LogcatFilter = listOf("PlaybackService:I", "Account:I", "*:W")
    private var crashes: File? = null

    fun init(context: Context) {
        val file = File(context.filesDir, "crashes.log").also { crashes = it }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { appendCrash(file, "${timestamp()} crash in ${thread.name}\n${e.stackTraceToString()}") }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun appendCrash(file: File, text: String) {
        val old = if (file.exists()) file.readText() else ""
        file.writeText((old + redact(text) + "\n").takeLast(MAX_CRASH_BYTES))
    }

    /** Writes the report into the cache for sharing: versions, saved crashes, then this run's log. */
    suspend fun report(context: Context): File = withContext(Dispatchers.IO) {
        val logcat = runCatching {
            val command = listOf("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}") + LogcatFilter
            ProcessBuilder(command).redirectErrorStream(true).start().inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { "logcat unavailable: $it" }
        val text = buildString {
            appendLine("WTube ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}), extractor ${BuildConfig.EXTRACTOR_VERSION}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Written ${timestamp()}")
            appendLine()
            appendLine("== Crashes ==")
            appendLine(crashes?.takeIf { it.exists() }?.readText()?.ifBlank { null } ?: "none")
            appendLine("== This run ==")
            append(logcat)
        }
        File(context.cacheDir, "logs").apply { mkdirs() }.resolve("wtube-log.txt").apply { writeText(redact(text)) }
    }

    private val StreamLink = Regex("""https?://[^\s"'<>]*googlevideo\.com[^\s"'<>]*""")
    private val CookieValue = Regex("""\b(SAPISID|APISID|HSID|SSID|SID|SIDCC|LOGIN_INFO|__Secure-[\w-]+)=[^;\s]+""")
    private val AuthHeader = Regex("""SAPISIDHASH\s+\S+""")

    /** Cuts stream links (they name the IP and session) and cookie values out of [text]. */
    fun redact(text: String): String = text
        .replace(StreamLink, "[googlevideo link]")
        .replace(CookieValue) { "${it.groupValues[1]}=[cut]" }
        .replace(AuthHeader, "SAPISIDHASH [cut]")

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
}
