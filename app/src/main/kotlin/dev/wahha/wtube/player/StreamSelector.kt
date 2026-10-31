package dev.wahha.wtube.player

import dev.wahha.wtube.data.Settings
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.VideoStream

enum class VideoCodec(val mimeType: String, private vararg val prefixes: String) {
    AV1("video/av01", "av01"),
    VP9("video/x-vnd.on2.vp9", "vp9", "vp09"),
    AVC("video/avc", "avc1");

    fun matches(codec: String?): Boolean = codec != null && prefixes.any { codec.startsWith(it, ignoreCase = true) }
}

/** What goes into one DASH manifest: every quality of one codec, and every audio track with its bitrates. */
data class StreamSelection(val video: List<VideoStream>, val audio: List<List<AudioStream>>)

/**
 * Picks streams for the merged manifest. A single codec per adaptation set keeps adaptive switching
 * seamless; [codecPreference] lists codecs the device decodes well, best first, and [decodes] says whether
 * the device plays a codec at a width, height and frame rate.
 */
fun selectStreams(
    videoOnly: List<VideoStream>,
    audio: List<AudioStream>,
    codecPreference: List<VideoCodec>,
    decodes: (VideoCodec, Int, Int, Int) -> Boolean = { _, _, _, _ -> true },
): StreamSelection {
    val usableVideo = videoOnly.filter { it.isPlayableDash() }
    // A codec that reaches down to the lowest quality cap, or the cap has nothing to pick and the player
    // takes that codec's lowest: YouTube encodes some 60 fps videos in AV1 only from 720p60 up.
    val candidates = codecPreference.filter { c -> usableVideo.any { c.matches(it.codec) && it.height <= LOWEST_CAP } }
        .ifEmpty { codecPreference.filter { c -> usableVideo.any { c.matches(it.codec) } } }
    // Of those, the one the device plays at the best quality, the preferred one on a tie: a phone whose
    // AV1 decoder stops at 1080p60 may still play VP9 in 4K, and the player hides what it cannot decode.
    fun best(c: VideoCodec): Int = usableVideo
        .filter { c.matches(it.codec) && decodes(c, it.width.takeIf { w -> w > 0 } ?: (it.height * 16 / 9), it.height, it.fps) }
        .maxOfOrNull { it.height * 1000 + it.fps } ?: -1
    val codec = candidates.maxByOrNull(::best)
    val video = if (codec == null) emptyList() else usableVideo
        .filter { codec.matches(it.codec) }
        .distinctBy { it.itag }
        .sortedByDescending { it.height * 1000 + it.fps }
    return StreamSelection(video, selectAudio(audio))
}

/**
 * Every audio track (dubs, auto-dubs and descriptions of videos that have them), the original first.
 * Within a track: Opus over AAC, without DRC duplicates, best bitrate first.
 */
fun selectAudio(audio: List<AudioStream>): List<List<AudioStream>> =
    audio.filter { it.isPlayableDash() }
        .groupBy { it.audioTrackId }
        .values
        .map { track ->
            val noDrc = track.filter { it.itagItem?.isDrc != true }.ifEmpty { track }
            val format = listOf(MediaFormat.WEBMA_OPUS, MediaFormat.M4A).firstOrNull { f -> noDrc.any { it.format == f } }
            noDrc.filter { format == null || it.format == format }
                .distinctBy { it.itag }
                .sortedByDescending { it.averageBitrate }
        }
        .sortedBy { track ->
            when (track.first().audioTrackType) {
                AudioTrackType.ORIGINAL, null -> 0
                AudioTrackType.DUBBED -> 1
                else -> 2
            }
        }

/** The smallest of the quality caps in Settings. */
private val LOWEST_CAP = Settings.QualityChoices.filter { it > 0 }.min()

/** Progressive or OTF YouTube URLs with itag metadata: what the extractor's DASH manifest creators accept. */
private fun Stream.isPlayableDash(): Boolean =
    isUrl && itagItem != null && (deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || deliveryMethod == DeliveryMethod.DASH)
