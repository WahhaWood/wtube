package dev.wahha.wtube

import com.grack.nanojson.JsonParser
import dev.wahha.wtube.data.DeArrow
import dev.wahha.wtube.data.Segment
import dev.wahha.wtube.data.SponsorBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SponsorBlock and Return YouTube Dislike: parsing offline, then the live APIs. */
class CommunityTest {
    @Test
    fun deArrowTitleNeedsAgreementAndLosesItsMarker() {
        fun parse(titles: String) = DeArrow.parse(JsonParser.`object`().from("""{"abc":{"titles":$titles}}"""), "abc")
        assertEquals("Plain title", parse("""[{"title":">Plain title","original":false,"votes":0,"locked":true}]"""))
        assertEquals("Voted", parse("""[{"title":"Voted","original":false,"votes":2,"locked":false}]"""))
        assertNull(parse("""[{"title":"Unvoted","original":false,"votes":0,"locked":false}]"""))
        assertNull(parse("""[{"title":"Real","original":true,"votes":5,"locked":false}]"""))
        assertNull(parse("[]"))
        assertNull(DeArrow.parse(JsonParser.`object`().from("{}"), "abc"))
    }

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
}
