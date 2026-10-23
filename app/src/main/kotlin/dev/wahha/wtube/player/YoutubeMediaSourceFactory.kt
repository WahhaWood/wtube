@file:OptIn(UnstableApi::class)

package dev.wahha.wtube.player

import androidx.media3.datasource.FileDataSource
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.wahha.wtube.data.Http
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.isLive
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo

private const val TAG = "WTubeSource"

/** Format ids of caption tracks, by kind. */
const val SUBTITLE_MANUAL = "manual"
const val SUBTITLE_AUTO = "auto"
const val SUBTITLE_TRANSLATED = "translated"

/**
 * Turns a media item whose id is a YouTube video id into a playable source. The [StreamInfo] must already
 * be in [YouTube]'s cache (the session callback fetches it), because media source creation is synchronous.
 *
 * Regular videos become one DASH source built from the extractor's per-stream manifests, merged so that
 * every quality of one codec sits in one adaptation set: ExoPlayer then adapts to bandwidth on its own and
 * a manual quality pick is just a track override. Live streams use YouTube's HLS manifest.
 */
class YoutubeMediaSourceFactory : MediaSource.Factory {
    private val youtubeDataSource = ResolvingDataSource.Factory(OkHttpDataSource.Factory(Http.calls), YoutubeRequests)
    private val plainDataSource = OkHttpDataSource.Factory(Http.calls).setUserAgent(Http.USER_AGENT)
    private val subtitleDataSource = DataSource.Factory { SubtitleDataSource() }
    private val codecs by lazy { deviceCodecPreference() }
    private var drmProvider: DrmSessionManagerProvider? = null
    private var errorPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider) =
        apply { drmProvider = drmSessionManagerProvider }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy) =
        apply { errorPolicy = loadErrorHandlingPolicy }

    override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        // A downloaded file: an ordinary MP4 or M4A on disk.
        if (mediaItem.localConfiguration?.uri?.scheme == "file") {
            return ProgressiveMediaSource.Factory(FileDataSource.Factory()).configured().createMediaSource(mediaItem)
        }
        val info = YouTube.cachedStreamInfo(mediaItem.mediaId)
        if (info == null) {
            // Never throw here: it would crash the session thread. A source with no extractor fails on
            // prepare instead, and the player reports an ordinary error.
            Log.e(TAG, "No stream info for ${mediaItem.mediaId}")
            return progressive(mediaItem)
        }

        if (info.streamType.isLive() && info.hlsUrl.isNotEmpty()) {
            return HlsMediaSource.Factory(plainDataSource).configured()
                .createMediaSource(mediaItem.buildUpon().setUri(info.hlsUrl).build())
        }

        val selection = selectStreams(info.videoOnlyStreams, info.audioStreams, codecs)
        val manifest = mergedManifest(info, selection)
        val main = if (manifest != null) {
            DashMediaSource.Factory(youtubeDataSource).configured().createMediaSource(manifest, mediaItem)
        } else {
            // Rare: no DASH-able streams. Fall back to the best muxed progressive stream.
            val muxed = info.videoStreams.filter { it.isUrl }.maxByOrNull { it.height }
            Log.w(TAG, "No DASH streams for ${info.id}, muxed fallback: ${muxed?.height}p")
            progressive(mediaItem.buildUpon().setUri(muxed?.content.orEmpty()).build())
        }
        return withSubtitles(main, info)
    }

    /**
     * Caption tracks as extra text sources next to the video, the way NewPipe does it: a single-sample source
     * loads its file only once the track is picked. Manual tracks carry [C.ROLE_FLAG_SUBTITLE], which the
     * player prefers over auto-generated ones of the same language; the format id tells the UI the kind.
     */
    private fun withSubtitles(main: MediaSource, info: StreamInfo): MediaSource {
        // The app is Russian only, so machine translation always targets Russian.
        val tracks = subtitleTracks(info.subtitles, translateTo = "ru")
        if (tracks.isEmpty()) return main
        val factory = SingleSampleMediaSource.Factory(subtitleDataSource).also { f -> errorPolicy?.let { f.setLoadErrorHandlingPolicy(it) } }
        val subtitles = tracks.map { track ->
            val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(track.language)
                .setRoleFlags(if (track.auto) 0 else C.ROLE_FLAG_SUBTITLE)
                .setId(
                    when {
                        track.translated -> SUBTITLE_TRANSLATED
                        track.auto -> SUBTITLE_AUTO
                        else -> SUBTITLE_MANUAL
                    },
                )
                .build()
            factory.createMediaSource(config, C.TIME_UNSET)
        }
        return MergingMediaSource(main, *subtitles.toTypedArray())
    }

    private fun progressive(mediaItem: MediaItem): MediaSource =
        ProgressiveMediaSource.Factory(youtubeDataSource).configured().createMediaSource(mediaItem)

    private fun <F : MediaSource.Factory> F.configured(): F = apply {
        drmProvider?.let { setDrmSessionManagerProvider(it) }
        errorPolicy?.let { setLoadErrorHandlingPolicy(it) }
    }

    private fun mergedManifest(info: StreamInfo, selection: StreamSelection): DashManifest? {
        val parser = DashManifestParser()
        fun parse(stream: Stream): DashManifest? = runCatching {
            val itag = requireNotNull(stream.itagItem)
            val xml = if (stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) {
                YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, itag, info.duration)
            } else {
                YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, itag, info.duration)
            }
            parser.parse(Uri.parse(stream.content), xml.byteInputStream())
        }.onFailure { Log.w(TAG, "Manifest for itag ${stream.itagItem?.id} failed", it) }.getOrNull()

        // One adaptation set per audio track: the extractor's manifests carry its language and role
        // (main, dub, description), and picking a dub is a track override like picking a quality.
        val video = selection.video.mapNotNull(::parse)
        val audio = selection.audio.map { track -> track.mapNotNull(::parse) }
        val base = (video + audio.flatten()).firstOrNull() ?: return null
        val sets = (listOf(C.TRACK_TYPE_VIDEO to video) + audio.map { C.TRACK_TYPE_AUDIO to it })
            .filter { (_, manifests) -> manifests.isNotEmpty() }
            .mapIndexed { index, (type, manifests) ->
                val representations = manifests.flatMap { m -> m.getPeriod(0).adaptationSets.flatMap { it.representations } }
                AdaptationSet(index.toLong(), type, representations, emptyList(), emptyList(), emptyList())
            }
        val period = base.getPeriod(0)
        return DashManifest(
            base.availabilityStartTimeMs,
            base.durationMs,
            base.minBufferTimeMs,
            base.dynamic,
            base.minUpdatePeriodMs,
            base.timeShiftBufferDepthMs,
            base.suggestedPresentationDelayMs,
            base.publishTimeMs,
            base.programInformation,
            base.utcTiming,
            base.serviceDescription,
            listOf(Period(period.id, period.startMs, sets)),
            base.locations,
        )
    }
}

/**
 * Codecs in the order to try: hardware AV1 and VP9 first (better quality per bit, VP9 goes up to 4K),
 * then H.264, which every device decodes in hardware, then software decoders as a last resort.
 */
private fun deviceCodecPreference(): List<VideoCodec> {
    val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
    fun has(codec: VideoCodec, hardware: Boolean) = decoders.any { info ->
        info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) } && (!hardware || info.isHardware())
    }
    val order = buildList {
        if (has(VideoCodec.AV1, hardware = true)) add(VideoCodec.AV1)
        if (has(VideoCodec.VP9, hardware = true)) add(VideoCodec.VP9)
        add(VideoCodec.AVC)
        if (has(VideoCodec.VP9, hardware = false)) add(VideoCodec.VP9)
        if (has(VideoCodec.AV1, hardware = false)) add(VideoCodec.AV1)
    }.distinct()
    Log.i(TAG, "Codec preference: $order")
    return order
}

private fun MediaCodecInfo.isHardware(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isHardwareAccelerated
    } else {
        !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")
    }
