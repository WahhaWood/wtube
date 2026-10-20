package dev.wahha.wtube.data

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import dev.wahha.wtube.player.sameLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** A caption track of a video; [url] is the extractor's timedtext link, [auto] marks YouTube's speech recognition. */
class CaptionTrack(val url: String, val language: String, val auto: Boolean)

/** One caption line of a transcript: what is said from [startMs] on. */
class TranscriptLine(val startMs: Long, val text: String)

/**
 * The video's words with their times, read from a caption track: YouTube's own transcript endpoint
 * (`get_transcript`) answers 400 FAILED_PRECONDITION without a PO token, the caption files do not.
 */
object Transcript {
    /** A human track in [language], else a human one in the spoken language, else the auto-generated one, else any. */
    fun preferred(tracks: List<CaptionTrack>, language: String): CaptionTrack? {
        val spoken = tracks.firstOrNull { it.auto }?.language
        return tracks.firstOrNull { !it.auto && it.language.sameLanguage(language) }
            ?: tracks.firstOrNull { !it.auto && spoken != null && it.language.sameLanguage(spoken) }
            ?: tracks.firstOrNull { it.auto }
            ?: tracks.firstOrNull()
    }

    suspend fun load(track: CaptionTrack): List<TranscriptLine> = withContext(Dispatchers.IO) {
        val url = track.url.replace(Regex("&(fmt|tlang)=[^&]*"), "") + "&fmt=json3"
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Captions: HTTP ${response.code}")
            response.body.string()
        }
        parse(body)
    }

    /**
     * YouTube's json3 captions: each event with text segments is a line on screen. Auto-generated tracks
     * also have events that only break the line (`aAppend`, a lone "\n") and window setup events without
     * segments; both carry no words and are dropped.
     */
    fun parse(json3: String): List<TranscriptLine> =
        JsonParser.`object`().from(json3).getArray("events").filterIsInstance<JsonObject>().mapNotNull { event ->
            val segments = event.getArray("segs").filterIsInstance<JsonObject>()
            val text = segments.joinToString("") { it.getString("utf8").orEmpty() }.replace(Whitespace, " ").trim()
            if (text.isEmpty()) null else TranscriptLine(event.getLong("tStartMs"), text)
        }

    private val Whitespace = Regex("\\s+")
}
