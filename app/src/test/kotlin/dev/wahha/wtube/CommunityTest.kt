package dev.wahha.wtube

import com.grack.nanojson.JsonParser
import dev.wahha.wtube.data.Dislikes
import dev.wahha.wtube.data.Segment
import dev.wahha.wtube.data.SponsorBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SponsorBlock and Return YouTube Dislike: parsing offline, then the live APIs. */
class CommunityTest {
    @Test
    fun segmentsOfTheVideoOnlySortedSkipAndMute() {
        val json = JsonParser.array().from(
            """
            [{"videoID":"other","segments":[{"segment":[1,2],"category":"sponsor","actionType":"skip"}]},
             {"videoID":"abc","segments":[
               {"segment":[300.5,350],"category":"selfpromo","actionType":"mute"},
               {"segment":[10,42.25],"category":"sponsor","actionType":"skip"},
               {"segment":[60,60],"category":"intro","actionType":"skip"},
               {"segment":[90,0],"category":"poi_highlight","actionType":"poi"}]}]
            """,
        )
        assertEquals(
            listOf(Segment(10_000, 42_250, "sponsor", mute = false), Segment(300_500, 350_000, "selfpromo", mute = true)),
            SponsorBlock.parse(json, "abc"),
        )
    }

    @Test
    fun liveSponsorBlockAndDislikes() = runBlocking {
        val segments = SponsorBlock.segments("601HYb7gFz0", setOf("sponsor"))
        val dislikes = Dislikes.of("dQw4w9WgXcQ")
        println("sponsorblock: $segments, dislikes: $dislikes")
        assertTrue(segments.isNotEmpty() && segments.all { it.category == "sponsor" })
        assertTrue(dislikes > 0)
    }
}
