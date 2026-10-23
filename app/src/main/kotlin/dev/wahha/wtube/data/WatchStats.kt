package dev.wahha.wtube.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Time spent playing one video on one local calendar day ([day] is the epoch day). */
@Serializable
data class StatsEntry(
    val day: Long,
    val videoId: String,
    val title: String,
    val channel: String? = null,
    val channelUrl: String? = null,
    val seconds: Long,
)

/**
 * How long videos were actually played, per day and video, on this phone. History keeps one row per video, so
 * it cannot tell this; the player service adds the seconds as they play. Nothing is recorded while saving
 * history is off.
 */
object WatchStats {
    private const val KEEP_DAYS = 400L
    private val state = MutableStateFlow<List<StatsEntry>>(emptyList())
    private val disk = JsonFile("WatchStats", ListSerializer(StatsEntry.serializer())) { state.value }

    val entries: StateFlow<List<StatsEntry>> = state.asStateFlow()

    fun init(context: Context) {
        disk.load(File(context.filesDir, "stats.json")) { fromFile ->
            // Seconds counted before the file finished loading are added to what was on disk.
            state.update { current -> merge(fromFile.orEmpty(), current) }
        }
    }

    fun add(entry: StatsEntry) {
        if (entry.seconds <= 0) return
        state.update { merge(it, listOf(entry)) }
        disk.save()
    }

    fun clear() {
        state.value = emptyList()
        disk.save()
    }

    /** The whole stats at once (a restored backup). */
    fun replace(entries: List<StatsEntry>) {
        state.value = merge(emptyList(), entries)
        disk.save()
    }

    fun dayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /** Adds [extra] to [base]: the same video on the same day sums up; days older than [KEEP_DAYS] are dropped. */
    internal fun merge(base: List<StatsEntry>, extra: List<StatsEntry>): List<StatsEntry> {
        val sums = LinkedHashMap<Pair<Long, String>, StatsEntry>()
        for (entry in base + extra) {
            val key = entry.day to entry.videoId
            val before = sums[key]
            sums[key] = if (before == null) entry else entry.copy(seconds = before.seconds + entry.seconds)
        }
        val newest = sums.values.maxOfOrNull { it.day } ?: return emptyList()
        return sums.values.filter { it.day > newest - KEEP_DAYS }
    }
}

enum class StatsRange(val days: Int?, val chartBuckets: Int, val bucketDays: Int) {
    WEEK(7, 7, 1),
    MONTH(30, 30, 1),

    /** Everything counted, charted as the last twelve weeks. */
    ALL(null, 12, 7),
}

data class ChannelStat(val name: String, val url: String?, val seconds: Long)

data class VideoStat(val id: String, val title: String, val channel: String?, val seconds: Long)

data class StatsSummary(
    val totalSeconds: Long,
    val videos: Int,
    val activeDays: Int,
    /** Consecutive days with playback up to today (yesterday counts if today has none yet). */
    val streak: Int,
    /** Seconds per chart bucket, oldest first. */
    val chart: List<Long>,
    val channels: List<ChannelStat>,
    val topVideos: List<VideoStat>,
)

fun summarize(entries: List<StatsEntry>, today: Long, range: StatsRange, top: Int = 5): StatsSummary {
    val inRange = if (range.days == null) entries else entries.filter { it.day in (today - range.days + 1)..today }
    val bucketsFrom = today - range.chartBuckets * range.bucketDays + 1
    val chart = LongArray(range.chartBuckets)
    for (entry in entries) {
        if (entry.day < bucketsFrom || entry.day > today) continue
        chart[((entry.day - bucketsFrom) / range.bucketDays).toInt()] += entry.seconds
    }
    val channels = inRange
        .filter { !it.channel.isNullOrBlank() }
        .groupBy { it.channelUrl ?: it.channel }
        .map { (_, list) -> ChannelStat(list.first().channel.orEmpty(), list.first().channelUrl, list.sumOf { it.seconds }) }
        .sortedByDescending { it.seconds }
    val videos = inRange
        .groupBy { it.videoId }
        .map { (id, list) -> VideoStat(id, list.first().title, list.first().channel, list.sumOf { it.seconds }) }
        .sortedByDescending { it.seconds }
    val activeDays = entries.map { it.day }.toSet()
    var streak = 0
    var day = if (today in activeDays) today else today - 1
    while (day in activeDays) {
        streak++
        day--
    }
    return StatsSummary(
        totalSeconds = inRange.sumOf { it.seconds },
        videos = videos.size,
        activeDays = inRange.map { it.day }.toSet().size,
        streak = streak,
        chart = chart.toList(),
        channels = channels.take(top),
        topVideos = videos.take(top),
    )
}
