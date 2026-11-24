package dev.wahha.wtube

import dev.wahha.wtube.download.safeFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNameTest {
    @Test
    fun longCjkTitleFitsTheByteLimit() {
        val name = safeFileName("漢".repeat(150))
        assertTrue(name.toByteArray().size <= 200)
        assertEquals(66, name.length)
    }

    @Test
    fun surrogatePairsAreNotSplit() {
        val name = safeFileName("😀".repeat(100))
        assertTrue(name.toByteArray().size <= 200)
        assertEquals(0, name.length % 2)
        assertTrue(name.codePoints().allMatch { it == 0x1F600 })
    }

    @Test
    fun shortTitleKeepsAllButForbiddenCharacters() {
        assertEquals("ab c", safeFileName(" a/b c? "))
        assertEquals("x".repeat(200), safeFileName("x".repeat(300)))
    }
}
