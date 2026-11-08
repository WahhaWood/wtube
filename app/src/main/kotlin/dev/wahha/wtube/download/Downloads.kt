package dev.wahha.wtube.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.Intent
import dev.wahha.wtube.data.JsonFile
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.ui.components.movedBefore
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

enum class DownloadState { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/** The audio track picked in the player when the download was started: its language and kind (an `AudioTrackType` name). */
@Serializable
data class AudioChoice(val language: String, val kind: String?)

/**
 * One saved video or its sound. [quality] is the video height picked (0 for audio); [file] is where the
 * finished file lives; [bytes] of [total] counts every part being fetched, for the progress bar. The queue
 * goes by [order] (the user's, or the turn it joined in; 0 in files from before there was one), then [addedAt].
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
    val audio: AudioChoice? = null,
    val order: Long = 0,
) {
    val key: String get() = key(video.id, audioOnly)

    /** Has not started its turn yet or was stopped mid-way: the rows the user can put in order. */
    val waiting: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.PAUSED

    companion object {
        fun key(videoId: String, audioOnly: Boolean) = if (audioOnly) "$videoId/audio" else videoId
    }
}

private val QueueOrder = compareBy<Download>({ it.order }, { it.addedAt })

/** The waiting downloads in the order they would be fetched. */
internal fun List<Download>.waitingInOrder(): List<Download> = filter { it.waiting }.sortedWith(QueueOrder)

/** What is on its way, in the order it is fetched, above everything else as it was. */
internal fun List<Download>.arranged(): List<Download> =
    filter { it.state == DownloadState.RUNNING } + waitingInOrder() + filter { it.state == DownloadState.DONE || it.state == DownloadState.FAILED }

/** The one to fetch next; a paused one sits out. */
internal fun List<Download>.nextInQueue(): Download? = filter { it.state == DownloadState.QUEUED }.minWithOrNull(QueueOrder)

/** Past every order in use, so a new download joins the end of a queue the user has arranged. */
internal fun List<Download>.queueEnd(): Long = (maxOfOrNull { it.order } ?: 0) + 1

/** [key] placed right above [before] (at the end when null) among the waiting ones; their order is written out anew. */
internal fun List<Download>.withMoved(key: String, before: String?): List<Download> {
    val orders = waitingInOrder().movedBefore(Download::key, key, before).withIndex().associate { (index, download) -> download.key to index + 1L }
    return map { download -> orders[download.key]?.let { download.copy(order = it) } ?: download }
}

internal fun List<Download>.pausing(selected: (Download) -> Boolean): List<Download> =
    map { if (selected(it) && (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING)) it.copy(state = DownloadState.PAUSED) else it }

internal fun List<Download>.unpausing(selected: (Download) -> Boolean): List<Download> =
    map { if (selected(it) && it.state == DownloadState.PAUSED) it.copy(state = DownloadState.QUEUED) else it }

/** The list as a new process takes it over: a download running when the old one died goes back to the queue, a paused one stays paused. */
internal fun List<Download>.afterRestart(): List<Download> = map { if (it.state == DownloadState.RUNNING) it.copy(state = DownloadState.QUEUED) else it }

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
            state.update { current -> (current + fromFile.orEmpty().afterRestart()).distinctBy { it.key } }
            resume()
        }
    }

    /** Starts the service if anything waits in the queue; a paused download waits for the user, not for this. */
    fun resume() {
        if (hasQueued()) startService()
    }

    /**
     * Queues [video] (or its sound) at [quality] and starts the service; an earlier copy is replaced. Its
     * files are deleted before the new one is listed, so the service never resumes from the old parts.
     */
    fun enqueue(video: VideoItem, audioOnly: Boolean, quality: Int, audio: AudioChoice? = null) {
        val download = newDownload(video, audioOnly, quality, System.currentTimeMillis(), audio) ?: return
        change { list -> list.filterNot { it.key == download.key } }
        scope.launch {
            partsOf(download).forEach { it.delete() }
            change { list -> listOf(download.copy(order = list.queueEnd())) + list.filterNot { it.key == download.key } }
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
        change { list -> list.queueEnd().let { end -> fresh.map { it.copy(order = end) } } + list.filterNot { it.key in keys } }
        startService()
        return fresh.size
    }

    private fun newDownload(video: VideoItem, audioOnly: Boolean, quality: Int, addedAt: Long, audio: AudioChoice? = null): Download? {
        val context = app ?: return null
        val folder = context.getExternalFilesDir(if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val name = safeName(video.title).ifEmpty { video.id } + " [${video.id}]" + if (audioOnly) ".m4a" else ".mp4"
        return Download(video, audioOnly, quality, File(File(folder, "WTube"), name).absolutePath, addedAt = addedAt, audio = audio)
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

    /** Stops [key] with its parts kept (the service notices); it continues only when asked to. */
    fun pause(key: String) = change { list -> list.pausing { it.key == key } }

    fun unpause(key: String) {
        change { list -> list.unpausing { it.key == key } }
        startService()
    }

    fun pauseAll() = change { list -> list.pausing { true } }

    fun unpauseAll() {
        change { list -> list.unpausing { true } }
        startService()
    }

    /** Puts waiting [key] right above [before], or last when null. */
    fun move(key: String, before: String?) = change { list -> list.withMoved(key, before) }

    /** The finished download of [videoId] (its picture with sound, or the sound alone), if its file is there. */
    fun finished(videoId: String, audioOnly: Boolean): Download? =
        find(Download.key(videoId, audioOnly))?.takeIf { it.state == DownloadState.DONE && File(it.file).exists() }

    /** Puts the running download back in the queue, parts kept: the service was stopped by the system. */
    internal fun requeueRunning() = change { list -> list.afterRestart() }

    /** Pauses what is being fetched now, from the notification. */
    internal fun pauseRunning() = change { list -> list.pausing { it.state == DownloadState.RUNNING } }

    /** Marks the next in the queue as running and returns it. One step, so a pause tapped just now is not overwritten. */
    internal fun claim(): Download? {
        var claimed: Download? = null
        change { list ->
            claimed = list.nextInQueue()?.copy(state = DownloadState.RUNNING, error = null)
            claimed?.let { taken -> list.map { if (it.key == taken.key) taken else it } } ?: list
        }
        return claimed
    }

    internal fun hasQueued(): Boolean = state.value.any { it.state == DownloadState.QUEUED }

    internal fun find(key: String): Download? = state.value.firstOrNull { it.key == key }

    /** Whether [download] should go on: still listed, not replaced by a newer copy of the same video, not paused. */
    internal fun wanted(download: Download): Boolean =
        find(download.key)?.let { it.addedAt == download.addedAt && it.state == DownloadState.RUNNING } == true

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
