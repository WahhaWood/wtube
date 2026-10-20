package dev.wahha.wtube.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.Intent
import dev.wahha.wtube.data.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED }

/**
 * One saved video or its sound. [quality] is the video height picked (0 for audio); [file] is where the
 * finished file lives; [bytes] of [total] counts every part being fetched, for the progress bar.
 */
@Serializable
data class Download(
    val video: VideoItem,
    val audioOnly: Boolean,
    val quality: Int,
    val file: String,
    val state: DownloadState = DownloadState.QUEUED,
    val bytes: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val key: String get() = key(video.id, audioOnly)

    companion object {
        fun key(videoId: String, audioOnly: Boolean) = if (audioOnly) "$videoId/audio" else videoId
    }
}

/**
 * Downloads, newest first, in one JSON file like [dev.wahha.wtube.data.History]. Files go to the app's
 * own external folders (Movies/WTube and Music/WTube under Android/data), which need no permission.
 * [DownloadService] does the fetching; this object is only the list.
 */
object Downloads {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<List<Download>>(emptyList())
    private var file: File? = null
    private var app: Context? = null

    val entries: StateFlow<List<Download>> = state.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        val f = File(context.filesDir, "downloads.json").also { file = it }
        scope.launch {
            val fromFile = runCatching { if (f.exists()) json.decodeFromString<List<Download>>(f.readText()) else emptyList() }
                .onFailure { Log.e("Downloads", "Unreadable download list, starting fresh", it) }
                .getOrDefault(emptyList())
            // A download that was running when the process died goes back to the queue and resumes.
            state.update { current -> (current + fromFile.map { if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.QUEUED) else it }).distinctBy { it.key } }
            resume()
        }
    }

    /** Starts the service if anything waits in the queue. */
    fun resume() {
        if (state.value.any { it.state == DownloadState.QUEUED }) startService()
    }

    /** Queues [video] (or its sound) at [quality] and starts the service; an earlier copy is replaced. */
    fun enqueue(video: VideoItem, audioOnly: Boolean, quality: Int) {
        val context = app ?: return
        val folder = context.getExternalFilesDir(if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val name = safeName(video.title).ifEmpty { video.id } + " [${video.id}]" + if (audioOnly) ".m4a" else ".mp4"
        val download = Download(video, audioOnly, quality, File(File(folder, "WTube"), name).absolutePath)
        remove(download.key)
        change { listOf(download) + it }
        startService()
    }

    /** Stops (the service notices) and deletes [key] with its files. */
    fun remove(key: String) {
        val gone = state.value.firstOrNull { it.key == key } ?: return
        change { list -> list.filterNot { it.key == key } }
        scope.launch { partsOf(gone).forEach { it.delete() } }
    }

    fun retry(key: String) {
        change { list -> list.map { if (it.key == key) it.copy(state = DownloadState.QUEUED, error = null) else it } }
        startService()
    }

    /** The finished download of [videoId] (its picture with sound, or the sound alone), if its file is there. */
    fun finished(videoId: String, audioOnly: Boolean): Download? =
        find(Download.key(videoId, audioOnly))?.takeIf { it.state == DownloadState.DONE && File(it.file).exists() }

    internal fun next(): Download? = state.value.lastOrNull { it.state == DownloadState.QUEUED }

    internal fun find(key: String): Download? = state.value.firstOrNull { it.key == key }

    internal fun update(key: String, transform: (Download) -> Download) = change { list -> list.map { if (it.key == key) transform(it) else it } }

    /** The finished file and the parts it is made from while downloading. */
    internal fun partsOf(download: Download): List<File> = File(download.file).let { listOf(it, File("${it.path}.video"), File("${it.path}.audio")) }

    private fun startService() {
        val context = app ?: return
        // Android 12+ refuses a foreground service started from the background (a process woken for
        // playback); the queue then waits until the Downloads screen or a new download resumes it.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
            .onFailure { Log.w("Downloads", "Service not started now: $it") }
    }

    private fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().take(80)

    private fun change(transform: (List<Download>) -> List<Download>) {
        state.update(transform)
        val snapshot = state.value
        val f = file ?: return
        scope.launch {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json.encodeToString(snapshot))
                tmp.renameTo(f)
            }.onFailure { Log.e("Downloads", "Could not save the download list", it) }
        }
    }
}
