package dev.wahha.wtube.ui.video

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.wahha.wtube.data.innertube.ChatAction
import dev.wahha.wtube.data.innertube.ChatInput
import dev.wahha.wtube.data.innertube.ChatMessage
import dev.wahha.wtube.data.innertube.ChatMode
import dev.wahha.wtube.data.innertube.ChatSource
import dev.wahha.wtube.data.innertube.LiveChat
import dev.wahha.wtube.ui.components.launchAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.coroutines.cancellation.CancellationException

/**
 * The chat of the video on the sheet. [follow] polls YouTube while the page is on screen and fills the
 * state the chat card and panels read. Live, each poll's messages trickle in over the wait before the
 * next one, as on YouTube; in a replay, messages show up as the player reaches their place in the video.
 */
@Stable
class ChatState(private val source: ChatSource) {
    val replay: Boolean get() = source.replay

    /** Oldest first, at most [MAX_MESSAGES]. */
    val messages = mutableStateListOf<ChatMessage>()
    var pinned by mutableStateOf<ChatMessage?>(null)
        private set

    /** Top chat and Live chat, once the first page said; empty before. */
    var modes by mutableStateOf<List<ChatMode>>(emptyList())
        private set

    /** What the account may do here; null signed out. */
    var input by mutableStateOf<ChatInput?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set

    /** The stream is over and its chat closed. */
    var ended by mutableStateOf(false)
        private set

    // The view picked from [modes]; null is the one the watch page opens with (Top chat).
    private var token by mutableStateOf<String?>(null)

    fun select(mode: ChatMode) {
        if (mode.selected && token == null || mode.token == token) return
        token = mode.token
        modes = modes.map { it.copy(selected = it.token == mode.token) }
    }

    /** Runs until cancelled; [position] is the player's position, which a replay follows. */
    suspend fun follow(position: () -> Long) {
        snapshotFlow { token ?: source.token }.collectLatest { start ->
            loading = true
            try {
                if (replay) followReplay(start, position) else followLive(start)
            } finally {
                loading = false
            }
        }
    }

    fun send(text: String) {
        val params = input?.sendParams ?: return
        launchAction {
            val sent = LiveChat.send(params, text)
            if (sent != null && messages.none { it.id == sent.id }) add(sent)
        }
    }

    private suspend fun followLive(start: String) {
        var next: String? = start
        var first = true
        var failures = 0
        while (next != null) {
            val page = try {
                LiveChat.page(next, replay = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LiveChat", "Poll failed: $e")
                error = e
                delay(RETRY_MS * (1 shl failures.coerceAtMost(3)))
                failures++
                continue
            }
            error = null
            failures = 0
            val wait = page.waitMs.coerceIn(MIN_POLL_MS, MAX_POLL_MS)
            if (first) {
                // A new view replaces the old one only once it has something to show.
                messages.clear()
                pinned = null
                input = page.input
                if (page.modes.isNotEmpty() && token == null) modes = page.modes
                page.actions.forEach(::apply)
                first = false
                loading = false
                delay(wait)
            } else {
                val adds = page.actions.count { it is ChatAction.Add }
                val step = if (adds > 0) wait / adds else 0
                for (action in page.actions) {
                    apply(action)
                    if (action is ChatAction.Add) delay(step)
                }
                if (adds == 0) delay(wait)
            }
            next = page.continuation
        }
        ended = true
    }

    private suspend fun followReplay(start: String, position: () -> Long) {
        // Every message fetched around the position, ordered by place in the video; shown up to the position.
        val buffer = ArrayList<ChatMessage>()
        var from = 0L
        var until = -1L
        var next: String? = null
        var seek: String? = null
        var first = true
        var failures = 0

        suspend fun load(token: String, at: Long, reset: Boolean) {
            val page = LiveChat.page(token, replay = true, offsetMs = at)
            if (reset) {
                buffer.clear()
                from = at
            }
            if (first) {
                pinned = null
                if (page.modes.isNotEmpty() && this.token == null) modes = page.modes
                first = false
            }
            val known = buffer.mapTo(HashSet()) { it.id }
            for (action in page.actions) when (action) {
                is ChatAction.Add -> if (known.add(action.message.id)) buffer += action.message
                is ChatAction.Pin -> pinned = action.message
                ChatAction.Unpin -> pinned = null
                else -> {}
            }
            buffer.sortBy { it.offsetMs }
            next = page.continuation
            seek = page.seekToken ?: seek
            val last = buffer.lastOrNull()?.offsetMs ?: at
            // A page with nothing new still moves on, or the same page would be asked for on every tick.
            until = if (last > until) last else at + EMPTY_REPLAY_SPAN_MS
        }

        while (true) {
            val at = position()
            try {
                when {
                    first || at < from - SEEK_SLACK_MS || at > until + SEEK_JUMP_MS -> load(if (first) start else seek ?: start, at, reset = true)
                    at > until - PREFETCH_MS && next != null -> load(next!!, at, reset = false)
                }
                error = null
                failures = 0
                loading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LiveChat", "Replay fetch failed: $e")
                error = e
                delay(RETRY_MS * (1 shl failures.coerceAtMost(3)))
                failures++
                continue
            }
            if (buffer.size > MAX_BUFFER) {
                buffer.subList(0, buffer.size - MAX_BUFFER).clear()
                from = buffer.first().offsetMs
            }
            val shown = buffer.count { it.offsetMs <= at }.let { end -> buffer.subList((end - MAX_MESSAGES).coerceAtLeast(0), end) }
            if (shown.size != messages.size || shown.lastOrNull()?.id != messages.lastOrNull()?.id) {
                messages.clear()
                messages.addAll(shown)
            }
            if (next == null && at > until) ended = true
            delay(REPLAY_TICK_MS)
        }
    }

    private fun apply(action: ChatAction) {
        when (action) {
            is ChatAction.Add -> if (messages.none { it.id == action.message.id }) add(action.message)
            is ChatAction.Replace -> {
                val index = messages.indexOfFirst { it.id == action.id }
                if (index >= 0) messages[index] = action.message else add(action.message)
            }
            is ChatAction.Remove -> messages.removeAll { it.id == action.id }
            is ChatAction.RemoveAuthor -> messages.removeAll { it.authorId == action.channelId }
            is ChatAction.Pin -> pinned = action.message
            ChatAction.Unpin -> pinned = null
        }
    }

    private fun add(message: ChatMessage) {
        messages += message
        if (messages.size > MAX_MESSAGES) messages.removeRange(0, messages.size - MAX_MESSAGES)
    }

    private companion object {
        const val MAX_MESSAGES = 200
        const val MAX_BUFFER = 3000
        const val MIN_POLL_MS = 1_000L
        // YouTube asks for 10 s and pushes the rest; without the push a shorter wait keeps the chat lively.
        const val MAX_POLL_MS = 5_000L
        const val RETRY_MS = 3_000L
        const val REPLAY_TICK_MS = 300L
        const val PREFETCH_MS = 3_000L
        const val SEEK_SLACK_MS = 2_000L
        const val SEEK_JUMP_MS = 30_000L
        const val EMPTY_REPLAY_SPAN_MS = 10_000L
    }
}
