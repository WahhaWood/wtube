package dev.wahha.wtube

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import dev.wahha.wtube.data.YouTube.toItems
import dev.wahha.wtube.data.innertube.Channels
import dev.wahha.wtube.data.innertube.FeedParser
import dev.wahha.wtube.data.innertube.LiveChatParser
import dev.wahha.wtube.data.innertube.WatchParser
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import kotlin.random.Random

/**
 * YouTube drops and reshapes fields without notice. Every parser gets its fixture with one random
 * piece damaged (a key removed, a value nulled, emptied or turned into another type, a list cut);
 * it may return less, but must not throw.
 */
class ParserRobustnessTest {
    private val timeAgo = ServiceList.YouTube.getTimeAgoParser(Localization("en", "US"))

    private fun text(name: String) = javaClass.getResourceAsStream("/innertube/$name")!!.bufferedReader().readText()

    /** Every (container, key or index) slot in the tree. */
    private fun slots(node: Any?, out: MutableList<Pair<Any, Any>> = mutableListOf()): MutableList<Pair<Any, Any>> {
        when (node) {
            is JsonObject -> node.keys.toList().forEach { k -> out += node to k; slots(node[k], out) }
            is JsonArray -> node.indices.forEach { i -> out += node to i; slots(node[i], out) }
        }
        return out
    }

    private fun damage(root: Any, random: Random): String {
        val (container, key) = slots(root).random(random)
        val how = random.nextInt(6)
        val value: Any? = when (how) {
            0 -> Unit // remove
            1 -> null
            2 -> ""
            3 -> JsonArray()
            4 -> JsonObject()
            else -> 7
        }
        if (container is JsonObject) {
            if (value === Unit) container.remove(key) else container[key as String] = value
        } else if (container is JsonArray) {
            val i = key as Int
            if (value === Unit) container.removeAt(i) else container[i] = value
        }
        return "mode $how at $key"
    }

    private fun check(fixture: String, runs: Int = 800, parse: (JsonObject) -> Unit) {
        val source = text(fixture)
        val random = Random(fixture.hashCode())
        val failures = mutableListOf<String>()
        repeat(runs) { n ->
            val copy = JsonParser.`object`().from(source)
            val what = damage(copy, random)
            try {
                parse(copy)
            } catch (e: Exception) {
                failures += "#$n $what: ${e.javaClass.simpleName} ${e.message?.take(80)} @ ${e.stackTrace.firstOrNull { it.className.contains("wahha") }}"
            }
        }
        if (failures.isNotEmpty()) fail("$fixture: ${failures.size}/$runs broke\n" + failures.distinctBy { it.substringAfter(" @ ") }.take(8).joinToString("\n"))
    }

    @Test fun home() = check("home.json") { FeedParser.page(it, timeAgo).items.toItems() }
    @Test fun homeMore() = check("home_more.json") { FeedParser.page(it, timeAgo).items.toItems() }
    @Test fun playlist() = check("playlist.json") { FeedParser.page(it, timeAgo).items.toItems(); FeedParser.playlistTitle(it) }
    @Test fun channels() = check("channels.json") { FeedParser.page(it, timeAgo).items.toItems() }
    @Test fun channelVideos() = check("channel_videos.json") { Channels.extras(it); FeedParser.page(it, timeAgo).items.toItems() }
    @Test fun channelSearch() = check("channel_search.json") { FeedParser.page(it, timeAgo).items.toItems() }
    @Test fun channelAbout() = check("channel_about.json") { Channels.about(it) }
    @Test fun accountMenu() = check("account_menu.json") { FeedParser.activeAccount(it) }
    @Test fun accountSwitcher() = check("account_switcher.json") { FeedParser.identities(it) }
    @Test fun watchNext() = check("watch_next.json") { WatchParser.watch(it); WatchParser.subscribed(it) }
    @Test fun comments() = check("comments_page.json") { WatchParser.comments(it, timeAgo) }
    @Test fun addToPlaylist() = check("add_to_playlist.json") { WatchParser.playlistOptions(it) }
    @Test fun liveChat() = check("live_chat.json") { LiveChatParser.page(it) }
    @Test fun chatReplay() = check("live_chat_replay.json") { LiveChatParser.page(it) }
}
