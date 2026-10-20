package dev.wahha.wtube

import com.grack.nanojson.JsonParser
import dev.wahha.wtube.data.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun versionsCompareNumerically() {
        assertTrue(Updates.isNewer("0.10.0", "0.9.2"))
        assertTrue(Updates.isNewer("1.0", "0.10.0"))
        assertFalse(Updates.isNewer("0.10.0", "0.10.0"))
        assertFalse(Updates.isNewer("0.10", "0.10.0"))
        assertFalse(Updates.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun releaseTakesApkAssetAndStripsTagPrefix() {
        val json = """{"tag_name":"v0.11.0","html_url":"https://github.com/x/y/releases/tag/v0.11.0","draft":false,"prerelease":false,
            "body":" Notes \n","assets":[{"name":"mapping.txt","browser_download_url":"https://x/m"},{"name":"WTube-0.11.0.apk","browser_download_url":"https://x/a.apk"}]}"""
        val release = Updates.parse(JsonParser.`object`().from(json))!!
        assertEquals("0.11.0", release.version)
        assertEquals("https://x/a.apk", release.url)
        assertEquals("Notes", release.notes)
    }

    @Test
    fun releaseWithoutApkFallsBackToPageAndDraftsAreIgnored() {
        val page = """{"tag_name":"v0.11.0","html_url":"https://github.com/x/y/releases/tag/v0.11.0","assets":[]}"""
        assertEquals("https://github.com/x/y/releases/tag/v0.11.0", Updates.parse(JsonParser.`object`().from(page))!!.url)
        assertNull(Updates.parse(JsonParser.`object`().from("""{"tag_name":"v9","draft":true}""")))
    }
}
