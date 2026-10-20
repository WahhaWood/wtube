package dev.wahha.wtube

import dev.wahha.wtube.data.ChannelItem
import dev.wahha.wtube.data.HiddenChannel
import dev.wahha.wtube.data.HiddenItems
import dev.wahha.wtube.data.VideoItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenTest {
    private fun video(id: String, channel: String?) =
        VideoItem(id, "t", null, "c", channel, null, duration = 60, views = 1, uploaded = null, isLive = false)

    @Test
    fun hidesVideosAndChannelsWhateverTheUrlForm() {
        val hidden = HiddenItems(
            videos = listOf(video("v1", null)),
            channels = listOf(HiddenChannel("https://www.youtube.com/channel/UCabc/", "C", null)),
        )
        assertFalse(hidden.allows(video("v1", "https://www.youtube.com/channel/UCother")))
        assertFalse(hidden.allows(video("v2", "https://m.youtube.com/channel/UCabc")))
        assertTrue(hidden.allows(video("v3", "https://www.youtube.com/channel/UCother")))
        assertTrue(hidden.allows(video("v4", null)))
        // The channel itself still shows up when searched for.
        assertTrue(hidden.allows(ChannelItem("https://www.youtube.com/channel/UCabc", "C", null, 0, false)))
        assertTrue(hidden.isHidden("https://youtube.com/channel/UCabc"))
    }
}
