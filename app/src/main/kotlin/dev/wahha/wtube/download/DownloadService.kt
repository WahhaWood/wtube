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
import dev.wahha.wtube.player.YoutubeRequests
import dev.wahha.wtube.ui.components.errorDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.MediaFormat as StreamFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
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
fun downloadOptions(info: StreamInfo): List<DownloadOption> {
    val audio = bestAudio(info) ?: return emptyList()
    val video = info.videoOnlyStreams.filter(::downloadable).filter { VideoCodec.AVC.matches(it.codec) }
        .groupBy { it.height }.values.map { it.maxBy { s -> s.fps } }
        .sortedByDescending { it.height }
        .map { DownloadOption(false, it.height, length(it) + length(audio)) }
    return video + DownloadOption(true, 0, length(audio))
}

private fun bestAudio(info: StreamInfo): AudioStream? = info.audioStreams.filter(::downloadable)
    .filter { it.format == StreamFormat.M4A && it.itagItem?.isDrc != true }
    .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
    .maxByOrNull { it.averageBitrate }

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

    private suspend fun work() {
        while (true) {
            val download = Downloads.next() ?: break
            try {
                Downloads.update(download.key) { it.copy(state = DownloadState.RUNNING, error = null) }
                fetch(download)
                Downloads.update(download.key) { it.copy(state = DownloadState.DONE, bytes = it.total) }
            } catch (e: CancellationException) {
                // Removed from the list meanwhile: nothing to report.
                if (Downloads.find(download.key) != null) throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed: ${errorDetail(e)}")
                Downloads.update(download.key) { it.copy(state = DownloadState.FAILED, error = errorDetail(e)) }
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
            val audio = bestAudio(info) ?: throw IOException("No AAC sound for this video")
            val video = if (download.audioOnly) null else videoFor(info, download.quality) ?: throw IOException("No H.264 stream")
            val total = length(audio) + (video?.let(::length) ?: 0)
            Downloads.update(download.key) { it.copy(total = total, quality = video?.height ?: it.quality) }
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
        var position = target.length().coerceAtMost(length)
        if (target.length() > length) target.delete().also { position = 0 }
        FileOutputStream(target, true).use { output ->
            while (position < length) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (Downloads.find(download.key) == null) throw CancellationException("Download removed")
                val end = minOf(position + CHUNK_BYTES, length)
                Http.client.newCall(YoutubeRequests.rangeRequest(url, position, end)).execute().use { response ->
                    if (response.code == 403) throw ExpiredException()
                    if (!response.isSuccessful) throw IOException("googlevideo: HTTP ${response.code}")
                    val body = response.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = body.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        position += read
                    }
                }
                Downloads.update(download.key) { it.copy(bytes = offset + position) }
                notify(download, total, offset + position, muxing = false)
            }
        }
    }

    /** Copies the samples of the picture and sound files into one MP4, as they are: no re-encoding. */
    private fun mux(video: File, audio: File, out: File) {
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
            sources.forEachIndexed { index, extractor ->
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    info.set(0, size, extractor.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(tracks[index], buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop()
        } finally {
            sources.forEach { it.release() }
            muxer.release()
        }
        if (!tmp.renameTo(out)) throw IOException("Could not move the finished file")
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

    private class ExpiredException : IOException("Stream URL expired")

    private companion object {
        const val TAG = "Downloads"
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 2
        // Up to 4 MB per request: like the player's segment requests, which googlevideo serves at full speed.
        const val CHUNK_BYTES = 4L * 1024 * 1024
    }
}
