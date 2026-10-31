package dev.wahha.wtube

import dev.wahha.wtube.player.VideoCodec
import dev.wahha.wtube.player.selectAudio
import dev.wahha.wtube.player.selectStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

class StreamSelectorTest {
    private fun video(itag: Int, codec: String, height: Int, fps: Int = 30, delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP): VideoStream {
        val item = ItagItem.getItag(itag).apply {
            setCodec(codec)
            setHeight(height)
            setFps(fps)
        }
        return VideoStream.Builder()
            .setId(itag.toString())
            .setContent("https://rr1.googlevideo.com/videoplayback?itag=$itag", true)
            .setMediaFormat(item.mediaFormat)
            .setDeliveryMethod(delivery)
            .setIsVideoOnly(true)
            .setResolution("${height}p")
            .setItagItem(item)
            .build()
    }

    private fun audio(itag: Int, bitrate: Int, type: AudioTrackType? = null, trackId: String? = null, drc: Boolean = false): AudioStream {
        val item = ItagItem.getItag(itag).apply { setIsDrc(drc) }
        return AudioStream.Builder()
            .setId("$itag-${trackId.orEmpty()}-$drc")
            .setContent("https://rr1.googlevideo.com/videoplayback?itag=$itag&t=$trackId", true)
            .setMediaFormat(item.mediaFormat)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setAverageBitrate(bitrate)
            .setAudioTrackType(type)
            .setAudioTrackId(trackId)
            .setItagItem(item)
            .build()
    }

    private val streams = listOf(
        video(137, "avc1.640028", 1080),
        video(136, "avc1.4d401f", 720),
        video(248, "vp9", 1080),
        video(247, "vp9", 720),
        video(303, "vp9", 1080, fps = 60),
        video(399, "av01.0.08M.08", 1080),
    )

    @Test
    fun picksOneCodecByPreferenceSortedBestFirst() {
        val vp9 = selectStreams(streams, emptyList(), listOf(VideoCodec.VP9, VideoCodec.AVC)).video
        assertEquals(listOf(303, 248, 247), vp9.map { it.itag })

        val avc = selectStreams(streams, emptyList(), listOf(VideoCodec.AVC)).video
        assertEquals(listOf(137, 136), avc.map { it.itag })
    }

    @Test
    fun fallsBackWhenPreferredCodecIsMissing() {
        val onlyAvc = streams.filter { it.codec.startsWith("avc1") }
        val picked = selectStreams(onlyAvc, emptyList(), listOf(VideoCodec.AV1, VideoCodec.VP9, VideoCodec.AVC)).video
        assertEquals(listOf(137, 136), picked.map { it.itag })
    }

    // Issue #3: a phone whose AV1 decoder stops at 1080p60 got no 1440p or 4K, though its VP9 decoder plays them.
    @Test
    fun takesTheCodecTheDevicePlaysHighest() {
        val uhd = listOf(video(401, "av01.0.12M.08", 2160, 60), video(399, "av01.0.09M.08", 1080, 60), video(394, "av01.0.00M.08", 144)) +
            listOf(video(315, "vp9", 2160, 60), video(303, "vp9", 1080, 60), video(278, "vp9", 144))
        val av1UpTo1080: (VideoCodec, Int, Int, Int) -> Boolean = { codec, _, height, _ -> codec != VideoCodec.AV1 || height <= 1080 }
        val picked = selectStreams(uhd, emptyList(), listOf(VideoCodec.AV1, VideoCodec.VP9, VideoCodec.AVC), av1UpTo1080).video
        assertEquals(listOf(315, 303, 278), picked.map { it.itag })

        // Both reach 4K: the preferred codec stays.
        val both = selectStreams(uhd, emptyList(), listOf(VideoCodec.AV1, VideoCodec.VP9, VideoCodec.AVC)).video
        assertEquals(listOf(401, 399, 394), both.map { it.itag })
    }

    // Big Buck Bunny 60 fps: AV1 only from 720p60, so a 480p cap on mobile data would get 720p60.
    @Test
    fun skipsACodecWithoutLowQualities() {
        val bbb = listOf(video(398, "av01.0.08M.08", 720, 60), video(399, "av01.0.09M.08", 1080, 60)) +
            listOf(video(303, "vp9", 1080, 60), video(302, "vp9", 720, 60), video(244, "vp9", 480), video(243, "vp9", 360))
        val picked = selectStreams(bbb, emptyList(), listOf(VideoCodec.AV1, VideoCodec.VP9, VideoCodec.AVC)).video
        assertEquals(listOf(303, 302, 244, 243), picked.map { it.itag })
    }

    @Test
    fun codecWithOnlyHighQualitiesStillPlaysWhenAlone() {
        val onlyHigh = listOf(video(398, "av01.0.08M.08", 720, 60), video(399, "av01.0.09M.08", 1080, 60))
        assertEquals(listOf(399, 398), selectStreams(onlyHigh, emptyList(), listOf(VideoCodec.AV1, VideoCodec.AVC)).video.map { it.itag })
    }

    @Test
    fun noUsableCodecMeansNoVideo() {
        val onlyAv1 = streams.filter { it.codec.startsWith("av01") }
        assertTrue(selectStreams(onlyAv1, emptyList(), listOf(VideoCodec.AVC)).video.isEmpty())
    }

    @Test
    fun skipsHlsDelivery() {
        val hls = listOf(video(248, "vp9", 1080, delivery = DeliveryMethod.HLS))
        assertTrue(selectStreams(hls, emptyList(), VideoCodec.entries).video.isEmpty())
    }

    @Test
    fun audioTrackPrefersOpusWithoutDrc() {
        val audio = listOf(
            audio(251, 160, AudioTrackType.ORIGINAL, "en.4"),
            audio(251, 150, AudioTrackType.ORIGINAL, "en.4", drc = true),
            audio(250, 70, AudioTrackType.ORIGINAL, "en.4"),
            audio(140, 128, AudioTrackType.ORIGINAL, "en.4"),
        )
        val picked = selectAudio(audio).single()
        assertEquals(listOf(251, 250), picked.map { it.itag })
        assertTrue(picked.all { it.itagItem?.isDrc != true && it.format == MediaFormat.WEBMA_OPUS })
    }

    @Test
    fun everyAudioTrackOriginalFirst() {
        val audio = listOf(
            audio(251, 160, AudioTrackType.DESCRIPTIVE, "en.2"),
            audio(140, 128, AudioTrackType.DUBBED, "ru.3"),
            audio(251, 160, AudioTrackType.DUBBED, "de.3"),
            audio(251, 160, AudioTrackType.ORIGINAL, "en.4"),
        )
        val tracks = selectAudio(audio)
        assertEquals(listOf("en.4", "ru.3", "de.3", "en.2"), tracks.map { it.single().audioTrackId })
        assertEquals(140, tracks[1].single().itag)
    }

    @Test
    fun audioWithoutTrackInfoKeepsEverything() {
        val picked = selectAudio(listOf(audio(140, 128), audio(139, 48))).single()
        assertEquals(listOf(140, 139), picked.map { it.itag })
    }
}
