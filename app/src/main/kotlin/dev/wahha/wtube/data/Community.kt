package dev.wahha.wtube.data

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/** A stretch of a video the SponsorBlock community marked; [mute] silences it instead of skipping. */
data class Segment(val startMs: Long, val endMs: Long, val category: String, val mute: Boolean) {
    operator fun contains(positionMs: Long) = positionMs in startMs until endMs
}

/** The segments of the video playing now, shared by [dev.wahha.wtube.player.PlaybackService] and the seek bar. */
class VideoSegments(val videoId: String, val segments: List<Segment>)

/**
 * SponsorBlock (sponsor.ajay.app): segments of a video to skip. Asked by the first 4 hex digits of the
 * id's SHA-256, as its clients do, so the server never learns which video is watched.
 */
object SponsorBlock {
    /** Categories in the order Settings lists them. */
    val Categories = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "filler")
    val DefaultCategories = setOf("sponsor", "selfpromo", "interaction")

    private val state = MutableStateFlow<VideoSegments?>(null)
    val current: StateFlow<VideoSegments?> = state.asStateFlow()

    fun show(segments: VideoSegments?) {
        state.value = segments
    }

    suspend fun segments(videoId: String, categories: Set<String>): List<Segment> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) return@withContext emptyList()
        val prefix = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)
        val query = "categories=" + URLEncoder.encode(categories.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8") +
            "&actionTypes=" + URLEncoder.encode("[\"skip\",\"mute\"]", "UTF-8")
        val request = Request.Builder().url("https://sponsor.ajay.app/api/skipSegments/$prefix?$query").get().build()
        Http.client.newCall(request).execute().use { response ->
            // 404 is the API's "nothing for this prefix".
            if (response.code == 404) return@withContext emptyList()
            if (!response.isSuccessful) throw IOException("SponsorBlock: HTTP ${response.code}")
            parse(JsonParser.array().from(response.body.string()), videoId)
        }
    }

    fun parse(videos: JsonArray, videoId: String): List<Segment> =
        videos.filterIsInstance<JsonObject>().firstOrNull { it.getString("videoID") == videoId }
            ?.getArray("segments").orEmpty().filterIsInstance<JsonObject>().mapNotNull { s ->
                val bounds = s.getArray("segment")
                val start = (bounds.getDouble(0) * 1000).toLong()
                val end = (bounds.getDouble(1) * 1000).toLong()
                val action = s.getString("actionType")
                if (end <= start || action !in setOf("skip", "mute")) null else Segment(start, end, s.getString("category").orEmpty(), action == "mute")
            }
            .sortedBy { it.startMs }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
}

/** Return YouTube Dislike (returnyoutubedislikeapi.com): the dislike count YouTube stopped showing, estimated. */
object Dislikes {
    suspend fun of(videoId: String): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId").get().build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Return YouTube Dislike: HTTP ${response.code}")
            JsonParser.`object`().from(response.body.string()).getLong("dislikes", -1)
        }
    }
}

/**
 * DeArrow (sponsor.ajay.app/api/branding): the community's plain title for a clickbait one. Asked by the first
 * 4 hex digits of the id's SHA-256, like [SponsorBlock]; only the video page shows it, the feeds keep the real titles.
 */
object DeArrow {
    suspend fun title(videoId: String): String? = withContext(Dispatchers.IO) {
        val prefix = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)
        val request = Request.Builder().url("https://sponsor.ajay.app/api/branding/$prefix").get().build()
        Http.client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("DeArrow: HTTP ${response.code}")
            parse(JsonParser.`object`().from(response.body.string()), videoId)
        }
    }

    /** The top title of [videoId], unless the community left the original on top or has not agreed on one yet. */
    fun parse(branding: JsonObject, videoId: String): String? {
        val top = branding.getObject(videoId)?.getArray("titles")?.filterIsInstance<JsonObject>()?.firstOrNull() ?: return null
        if (top.getBoolean("original") || !(top.getBoolean("locked") || top.getInt("votes") >= 1)) return null
        // A leading ">" only tells DeArrow's own clients not to re-case the title.
        return top.getString("title", "").trimStart('>').trim().ifEmpty { null }
    }
}
