package dev.wahha.wtube.data

import android.util.Log
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.innertube.ResumeMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Where the account stopped in a video on any device, as YouTube's lists said at [seenAt]. */
data class AccountPosition(val positionMs: Long, val finished: Boolean, val seenAt: Long)

/**
 * How far each video was watched, here and on the account's other devices. The phone's own place comes
 * from [History]; the account's from the lists it loads (home, subscriptions, history, playlists), which
 * mark started videos. Whichever was seen last wins, so a video left on the computer goes on from there.
 * The account's places live only as long as the process: every list loads them afresh.
 */
object WatchProgress {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accountState = MutableStateFlow<Map<String, AccountPosition>>(emptyMap())

    init {
        // The places belong to one account: another account, a re-login or an expired session must not inherit them.
        Account.session.map { s -> s?.let { listOf(it.cookie, it.authUser, it.pageId, it.expired) } }
            .distinctUntilChanged()
            .onEach { accountState.value = emptyMap() }
            .launchIn(scope)
    }

    // Lengths of the videos the account marked, for the fraction of the ones not in the local history.
    private val durations = ConcurrentHashMap<String, Long>()

    /** Watched share 0..1 of each started video, for the bars on thumbnails. */
    val fractions: StateFlow<Map<String, Float>> =
        combine(History.entries, accountState, Account.session) { entries, account, session ->
            val result = HashMap<String, Float>()
            for (entry in entries) {
                val duration = entry.video.duration * 1000L
                val fraction = when {
                    entry.positionMs > 0 && duration > 0 -> entry.positionMs.toFloat() / duration
                    entry.finished -> 1f
                    else -> 0f
                }
                if (fraction > 0f) result[entry.video.id] = fraction.coerceIn(0f, 1f)
            }
            if (session != null) for ((id, position) in account) {
                val local = entries.firstOrNull { it.video.id == id }
                if (local != null && local.savedAt() >= position.seenAt) continue
                val duration = local?.video?.duration?.times(1000L) ?: durations[id] ?: 0
                result[id] = if (position.finished) 1f else if (duration > 0) (position.positionMs.toFloat() / duration).coerceIn(0f, 1f) else continue
            }
            result
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Takes in the marks of a list the account loaded; [items] give the videos' lengths. */
    fun record(marks: Map<String, ResumeMark>, items: List<Item>) {
        if (marks.isEmpty()) return
        val now = System.currentTimeMillis()
        val lengths = items.filterIsInstance<VideoItem>().associate { it.id to it.duration * 1000L }
        val found = marks.mapNotNull { (id, mark) ->
            val duration = lengths[id] ?: 0
            val positionMs = when {
                mark.startSeconds > 0 -> mark.startSeconds * 1000L
                duration > 0 -> duration * mark.percent / 100
                else -> return@mapNotNull null
            }
            if (duration > 0) durations[id] = duration
            // YouTube starts a video it counts as watched over, and its bar is then full.
            val finished = mark.percent >= FINISHED_PERCENT || duration > 0 && positionMs > duration - FINISHED_TAIL_MS
            id to AccountPosition(if (finished) 0 else positionMs, finished, now)
        }.toMap()
        Log.i("WatchProgress", "Account marks: ${found.size} of ${items.size} items")
        accountState.update { it + found }
    }

    /** Where to start [id]: the account's place when it is newer than the phone's, else the phone's; 0 from the start. */
    fun resumeMs(id: String): Long {
        val local = History.entryOf(id)
        val account = accountState.value[id]?.takeIf { Account.session.value != null }
        if (account != null && (local == null || account.seenAt > local.savedAt())) return account.positionMs
        return local?.positionMs ?: 0
    }

    // Opening a video moves watchedAt but not the place in it; entries from before 0.12 have no time and lose.
    private fun HistoryEntry.savedAt() = positionAt

    private const val FINISHED_PERCENT = 95
    private const val FINISHED_TAIL_MS = 15_000L
}
