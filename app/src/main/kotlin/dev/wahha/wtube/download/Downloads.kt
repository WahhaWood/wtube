package dev.wahha.wtube.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.Intent
import dev.wahha.wtube.data.JsonFile
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
import kotlinx.serialization.builtins.ListSerializer
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
    private const val PROGRESS_SAVE_MS = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<List<Download>>(emptyList())
    private val disk = JsonFile("Downloads", ListSerializer(Download.serializer())) { state.value }
    private var app: Context? = null
    @Volatile private var progressSavedAt = 0L

    val entries: StateFlow<List<Download>> = state.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        disk.load(File(context.filesDir, "downloads.json")) { fromFile ->
            // A download that was running when the process died goes back to the queue and resumes.
            state.update { current -> (current + fromFile.orEmpty().map { if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.QUEUED) else it }).distinctBy { it.key } }
            resume()
        }
    }

    /** Starts the service if anything waits in the queue. */
    fun resume() {
        if (state.value.any { it.state == DownloadState.QUEUED }) startService()
    }

    /**
     * Queues [video] (or its sound) at [quality] and starts the service; an earlier copy is replaced. Its
     * files are deleted before the new one is listed, so the service never resumes from the old parts.
     */
    fun enqueue(video: VideoItem, audioOnly: Boolean, quality: Int) {
        val download = newDownload(video, audioOnly, quality, System.currentTimeMillis()) ?: return
        change { list -> list.filterNot { it.key == download.key } }
        scope.launch {
            partsOf(download).forEach { it.delete() }
            change { list -> listOf(download) + list.filterNot { it.key == download.key } }
            startService()
        }
    }

    /**
     * Queues a whole list (a playlist) at up to [quality], fetched in the list's order and shown in it.
     * Videos already saved or on their way are left alone; failed ones are queued again. Returns how many were queued.
     */
    fun enqueueAll(videos: List<VideoItem>, audioOnly: Boolean, quality: Int): Int {
        val now = System.currentTimeMillis()
        val kept = state.value.filter { it.state != DownloadState.FAILED }.mapTo(HashSet()) { it.key }
        // Later in the list = added later, so the queue takes them in order.
        val fresh = videos.distinctBy { it.id }.filter { Download.key(it.id, audioOnly) !in kept }
            .mapIndexedNotNull { index, video -> newDownload(video, audioOnly, quality, now + index) }
        if (fresh.isEmpty()) return 0
        val keys = fresh.mapTo(HashSet()) { it.key }
        change { list -> fresh + list.filterNot { it.key in keys } }
        startService()
        return fresh.size
    }

    private fun newDownload(video: VideoItem, audioOnly: Boolean, quality: Int, addedAt: Long): Download? {
        val context = app ?: return null
        val folder = context.getExternalFilesDir(if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val name = safeName(video.title).ifEmpty { video.id } + " [${video.id}]" + if (audioOnly) ".m4a" else ".mp4"
        return Download(video, audioOnly, quality, File(File(folder, "WTube"), name).absolutePath, addedAt = addedAt)
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

    /** The queued download added first. */
    /** Puts the running download back in the queue, parts kept: the service was stopped by the system. */
    internal fun pauseRunning() = change { list -> list.map { if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.QUEUED) else it } }

    internal fun next(): Download? = state.value.filter { it.state == DownloadState.QUEUED }.minByOrNull { it.addedAt }

    internal fun find(key: String): Download? = state.value.firstOrNull { it.key == key }

    /** Whether [download] is still listed, and not replaced by a newer copy of the same video. */
    internal fun isCurrent(download: Download): Boolean = find(download.key)?.addedAt == download.addedAt

    /** Changes [download] if it is still the listed one: a replaced download's late news is dropped. */
    internal fun update(download: Download, transform: (Download) -> Download) =
        change { list -> list.map { if (it.key == download.key && it.addedAt == download.addedAt) transform(it) else it } }

    /**
     * Moves the progress bar at once but reaches the disk at most every [PROGRESS_SAVE_MS]: after a restart
     * the parts on disk say where to resume, the saved count only fills the bar until the first chunk.
     */
    internal fun progress(download: Download, bytes: Long) {
        state.update { list -> list.map { if (it.key == download.key && it.addedAt == download.addedAt) it.copy(bytes = bytes) else it } }
        val now = System.currentTimeMillis()
        if (now - progressSavedAt >= PROGRESS_SAVE_MS) {
            progressSavedAt = now
            disk.save()
        }
    }

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
        disk.save()
    }
}
