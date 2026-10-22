package dev.wahha.wtube

import dev.wahha.wtube.data.Dislikes
import dev.wahha.wtube.data.SponsorBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunitySmokeTest {
    @Test
    fun liveSponsorBlockAndDislikes() = runBlocking {
        val segments = SponsorBlock.segments("601HYb7gFz0", setOf("sponsor"))
        val dislikes = Dislikes.of("dQw4w9WgXcQ")
        println("sponsorblock: $segments, dislikes: $dislikes")
        assertTrue(segments.isNotEmpty() && segments.all { it.category == "sponsor" })
        assertTrue(dislikes > 0)
    }
}
