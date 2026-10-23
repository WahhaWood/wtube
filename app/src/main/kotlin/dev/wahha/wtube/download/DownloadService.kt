package dev.wahha.wtube.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.wahha.wtube.MainActivity
import dev.wahha.wtube.R
import dev.wahha.wtube.data.Http
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.player.VideoCodec
import dev.wahha.wtube.player.sameLanguage
import dev.wahha.wtube.player.YoutubeRequests
import dev.wahha.wtube.ui.components.errorDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.MediaFormat as StreamFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import dev.wahha.wtube.player.sameLanguage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/** A choice in the download sheet: the video at [height] (H.264 + AAC in MP4), or its sound alone; [bytes] approximate. */
data class DownloadOption(val audioOnly: Boolean, val height: Int, val bytes: Long)

/**
 * What can be saved of a video: H.264 qualities (every phone plays them and MediaMuxer puts them in MP4
 * without re-encoding) and the original AAC sound. Only streams of known length: they download by range.
 */
fun downloadOptions(info: StreamInfo, choice: AudioChoice? = null): List<DownloadOption> {
    val audio = bestAudio(info.audioStreams, choice) ?: return emptyList()
    val video = info.videoOnlyStreams.filter(::downloadable).filter { VideoCodec.AVC.matches(it.codec) }
        .groupBy { it.height }.values.map { it.maxBy { s -> s.fps } }
        .sortedByDescending { it.height }
        .map { DownloadOption(false, it.height, length(it) + length(audio)) }
    return video + DownloadOption(true, 0, length(audio))
}

/**
 * The AAC track to save: the one the user had picked in the player ([choice]: language and kind), else
 * the original. A pick the video does not have (or has no AAC for) falls back to the original.
 */
internal fun bestAudio(streams: List<AudioStream>, choice: AudioChoice? = null): AudioStream? {
    val aac = streams.filter(::downloadable).filter { it.format == StreamFormat.M4A && it.itagItem?.isDrc != true }
    fun language(stream: AudioStream) = stream.audioLocale?.language ?: stream.audioTrackId?.substringBefore('.')
    val picked = choice?.let { c ->
        aac.filter { s -> language(s)?.sameLanguage(c.language) == true && (c.kind == null || (s.audioTrackType ?: AudioTrackType.ORIGINAL).name == c.kind) }
    }.orEmpty()
    return picked.ifEmpty { aac.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL } }
        .maxByOrNull { it.averageBitrate }
}

/** The H.264 stream at [height], else the best one below it (a playlist is saved "up to" a quality), else the lowest. */
private fun videoFor(info: StreamInfo, height: Int): VideoStream? {
    val streams = info.videoOnlyStreams.filter(::downloadable).filter { VideoCodec.AVC.matches(it.codec) }
    return streams.filter { it.height <= height }.maxWithOrNull(compareBy<VideoStream>({ it.height }, { it.fps }))
        ?: streams.minWithOrNull(compareBy<VideoStream>({ it.height }, { -it.fps }))
}

private fun downloadable(stream: org.schabi.newpipe.extractor.stream.Stream): Boolean =
    stream.isUrl && stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && length(stream) > 0

private fun length(stream: org.schabi.newpipe.extractor.stream.Stream): Long = stream.itagItem?.contentLength ?: -1

/**
 * Fetches queued [Downloads] one at a time in the foreground, with a progress notification. Parts are
 * fetched by range like the player does (a whole-file request gets throttled), resume where they
 * stopped, and refetch the stream URLs once if they expired. A video's picture and sound are then put
 * together into one MP4.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(notification(null, 0, 0))
        if (!running) {
            running = true
            scope.launch { work() }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 gives a data-sync service a time budget. At its end: stop cleanly, leave the unfinished
     * download queued (its parts on disk are resumed) and offer to continue from a notification.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.coroutineContext.cancelChildren()
        running = false
        Downloads.pauseRunning()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val resume = PendingIntent.getForegroundService(
            this, 1, Intent(this, DownloadService::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        notifications.notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.downloads_title))
                .setContentText(getString(R.string.downloads_paused))
                .setAutoCancel(true)
                .setContentIntent(resume)
                .addAction(0, getString(R.string.downloads_continue), resume)
                .build(),
        )
        stopSelf()
    }

    private suspend fun work() {
        while (true) {
            val download = Downloads.next() ?: break
            try {
                Downloads.update(download) { it.copy(state = DownloadState.RUNNING, error = null) }
                fetch(download)
                Downloads.update(download) { it.copy(state = DownloadState.DONE, bytes = it.total) }
            } catch (e: CancellationException) {
                // Removed or queued again meanwhile: nothing to report, the queue goes on.
                if (Downloads.isCurrent(download)) throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed: ${errorDetail(e)}")
                Downloads.update(download) { it.copy(state = DownloadState.FAILED, error = errorDetail(e)) }
            }
        }
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun fetch(download: Download) {
        val (videoPart, audioPart) = Downloads.partsOf(download).let { it[1] to it[2] }
        File(download.file).parentFile?.mkdirs()
        var info = YouTube.streamInfo(download.video.id)
        var refreshed = false
        while (true) {
            val audio = bestAudio(info.audioStreams, download.audio) ?: throw IOException("No AAC sound for this video")
            val video = if (download.audioOnly) null else videoFor(info, download.quality) ?: throw IOException("No H.264 stream")
            val total = length(audio) + (video?.let(::length) ?: 0)
            Downloads.update(download) { it.copy(total = total, quality = video?.height ?: it.quality) }
            try {
                video?.let { part(download, it.content, length(it), videoPart, offset = 0, total) }
                part(download, audio.content, length(audio), audioPart, offset = video?.let(::length) ?: 0, total)
                break
            } catch (e: ExpiredException) {
                // Stream URLs live a few hours; a download resumed later needs fresh ones, once.
                if (refreshed) throw IOException("YouTube refused the stream (HTTP 403)")
                refreshed = true
                YouTube.forget(download.video.id)
                info = YouTube.streamInfo(download.video.id)
            }
        }
        val out = File(download.file)
        if (download.audioOnly) {
            if (!audioPart.renameTo(out)) throw IOException("Could not move the finished file")
        } else {
            notify(download, total = 0, bytes = 0, muxing = true)
            mux(videoPart, audioPart, out)
            videoPart.delete()
            audioPart.delete()
        }
    }

    /** Appends bytes of [url] to [target] until it holds [length] of them; [offset] places them in the overall progress. */
    private suspend fun part(download: Download, url: String, length: Long, target: File, offset: Long, total: Long) {
        downloadRange(Http.client, url, length, target, CHUNK_BYTES, YoutubeRequests::rangeRequest, ensureAlive = {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!Downloads.isCurrent(download)) throw CancellationException("Download removed")
        }) { position ->
            Downloads.progress(download, offset + position)
            notify(download, total, offset + position, muxing = false)
        }
    }

    private fun notify(download: Download, total: Long, bytes: Long, muxing: Boolean) {
        notifications.notify(NOTIFICATION_ID, notification(download, if (muxing) 0 else total, bytes))
    }

    private fun notification(download: Download?, total: Long, bytes: Long): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.downloads_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(download?.video?.title ?: getString(R.string.downloads_title))
            .setContentText(getString(if (download != null && total == 0L) R.string.downloads_muxing else R.string.downloads_running))
            .setProgress(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), total == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "Downloads"
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 2
        // Up to 4 MB per request: like the player's segment requests, which googlevideo serves at full speed.
        const val CHUNK_BYTES = 4L * 1024 * 1024
    }
}

/** Copies the samples of the picture and sound files into one MP4, as they are: no re-encoding. */
internal fun mux(video: File, audio: File, out: File) {
    val tmp = File(out.path + ".tmp")
    val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val sources = listOf(video, audio).map { file -> MediaExtractor().apply { setDataSource(file.path) } }
    try {
        val tracks = sources.map { extractor ->
            extractor.selectTrack(0)
            muxer.addTrack(extractor.getTrackFormat(0))
        }
        val maxSample = sources.maxOf { ex ->
            ex.getTrackFormat(0).let { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
        }
        val buffer = ByteBuffer.allocate(maxOf(maxSample, 4 * 1024 * 1024))
        val info = MediaCodec.BufferInfo()
        muxer.start()
        // Picture and sound interleaved by time, the earlier sample first: players read the file front to back.
        while (true) {
            val index = sources.indices.filter { sources[it].sampleTime >= 0 }.minByOrNull { sources[it].sampleTime } ?: break
            val extractor = sources[index]
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            info.set(0, size, extractor.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(tracks[index], buffer, info)
            extractor.advance()
        }
        muxer.stop()
    } catch (e: Exception) {
        tmp.delete()
        throw e
    } finally {
        sources.forEach { it.release() }
        muxer.release()
    }
    if (!tmp.renameTo(out)) throw IOException("Could not move the finished file")
}

/** The stream URL is no longer accepted (googlevideo answered 403). */
internal class ExpiredException : IOException("Stream URL expired")

/**
 * Appends bytes of [url] to [target] until it holds [length] of them, in requests of up to [chunk] bytes,
 * continuing from what [target] already holds. [onProgress] gets the size after each request; [ensureAlive]
 * throws to stop. A longer [target] is a stale leftover and is started over. An answer that does not
 * start at the asked position (a server ignoring the range) is skipped into place or refused, never appended.
 */
internal suspend fun downloadRange(
    client: OkHttpClient,
    url: String,
    length: Long,
    target: File,
    chunk: Long,
    request: (String, Long, Long) -> Request,
    ensureAlive: suspend () -> Unit = {},
    onProgress: suspend (Long) -> Unit = {},
) {
    var position = target.length().coerceAtMost(length)
    if (target.length() > length) target.delete().also { position = 0 }
    FileOutputStream(target, true).use { output ->
        while (position < length) {
            ensureAlive()
            val end = minOf(position + chunk, length)
            var got = 0L
            client.newCall(request(url, position, end)).execute().use { response ->
                if (response.code == 403) throw ExpiredException()
                if (!response.isSuccessful) throw IOException("googlevideo: HTTP ${response.code}")
                val source = response.body.source()
                source.skip(bytesBefore(response, position, end - position, length))
                val body = source.inputStream()
                val buffer = ByteArray(64 * 1024)
                while (got < end - position) {
                    val read = body.read(buffer, 0, minOf(buffer.size.toLong(), end - position - got).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    got += read
                }
            }
            // An empty answer would repeat forever; the next attempt (a retry) continues from the file.
            if (got == 0L) throw IOException("googlevideo sent no data")
            output.flush()
            position += got
            onProgress(position)
        }
    }
}

/**
 * How many leading bytes of [response] lie before [position]. googlevideo takes the range as a query
 * parameter and answers 200 with just the [wanted] bytes; a 206 must say it starts at [position]; a 200
 * of the whole [length] means the range was ignored. Anything else would put wrong bytes into the part.
 */
private fun bytesBefore(response: Response, position: Long, wanted: Long, length: Long): Long {
    if (response.code == 206) {
        val range = response.header("Content-Range")
        val start = range?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        if (start != position) throw IOException("googlevideo: Content-Range $range for a range from $position")
        return 0
    }
    val size = response.body.contentLength()
    return when {
        size == wanted || size == 0L -> 0
        size == length -> position
        size == -1L && position == 0L -> 0
        else -> throw IOException("googlevideo: $size bytes for a range of $wanted")
    }
}
