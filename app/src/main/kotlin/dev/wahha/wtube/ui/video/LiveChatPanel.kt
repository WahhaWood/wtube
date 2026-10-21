package dev.wahha.wtube.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import dev.wahha.wtube.R
import dev.wahha.wtube.data.formatClock
import dev.wahha.wtube.data.innertube.ChatMessage
import dev.wahha.wtube.data.innertube.ChatRole
import dev.wahha.wtube.data.innertube.ChatRun
import dev.wahha.wtube.ui.components.Avatar
import dev.wahha.wtube.ui.icon.Close
import dev.wahha.wtube.ui.icon.ExpandMore
import dev.wahha.wtube.ui.icon.Send
import dev.wahha.wtube.ui.icon.WIcons

/** The chat card under the actions: the latest message as a teaser; tapping opens the chat under the video. */
@Composable
internal fun ChatPreview(chat: ChatState, onOpen: () -> Unit) {
    val last = chat.messages.lastOrNull()
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(if (chat.replay) R.string.chat_replay_title else R.string.chat_title), style = MaterialTheme.typography.titleSmall)
        when {
            last != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(last.avatar, 24.dp)
                val (text, inline) = chatText(last, withAuthor = true)
                Text(text, inlineContent = inline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            chat.ended -> Text(stringResource(R.string.chat_ended), style = MaterialTheme.typography.bodySmall)
            else -> Text(stringResource(R.string.chat_open), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The chat itself: views, the pinned message, the messages, and the account's input. Under the video or beside it. */
@Composable
internal fun ChatContent(chat: ChatState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    // Follows new messages while the list is at its end; scrolling up stops that until it is back there.
    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, forward) ->
            if (scrolling) stick = !forward
        }
    }
    val lastId = chat.messages.lastOrNull()?.id
    LaunchedEffect(lastId, stick) {
        if (stick && chat.messages.isNotEmpty()) listState.scrollToItem(chat.messages.lastIndex)
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (chat.replay) R.string.chat_replay_title else R.string.chat_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // YouTube names them in English whatever the language; its order is Top, then everything.
                chat.modes.take(2).forEachIndexed { index, mode ->
                    FilterChip(
                        selected = mode.selected,
                        onClick = { chat.select(mode) },
                        label = { Text(stringResource(if (index == 0) R.string.chat_mode_top else R.string.chat_mode_all)) },
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(WIcons.Close, contentDescription = stringResource(R.string.close), tint = colors.onSurface)
            }
        }
        chat.pinned?.let { pinned ->
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.chat_pinned), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                ChatLine(pinned, replay = false)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(chat.messages, key = { it.id }) { message -> ChatItem(message, chat.replay) }
            }
            when {
                chat.messages.isEmpty() && !chat.loading -> Text(
                    stringResource(if (chat.ended) R.string.chat_ended else R.string.chat_empty),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center),
                )
                !stick -> SmallFloatingActionButton(
                    onClick = { stick = true },
                    containerColor = colors.surfaceContainerHighest,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(WIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.chat_new_messages), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                    }
                }
            }
        }
        if (chat.error != null) {
            Text(
                stringResource(R.string.chat_error),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val input = chat.input
        when {
            chat.replay || chat.ended || input == null -> {}
            input.sendParams != null -> ChatInputRow(chat::send)
            input.restriction != null -> Text(
                input.restriction,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ChatInputRow(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_MESSAGE_LENGTH) },
            placeholder = { Text(stringResource(R.string.chat_placeholder), style = MaterialTheme.typography.bodyMedium) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                onSend(text.trim())
                text = ""
            },
            enabled = text.isNotBlank(),
        ) {
            Icon(WIcons.Send, contentDescription = stringResource(R.string.comment_send))
        }
    }
}

/** A message in the list: a plain line, or a card for Super Chats and membership news. */
@Composable
private fun ChatItem(message: ChatMessage, replay: Boolean) {
    val colors = MaterialTheme.colorScheme
    val card = when {
        message.amount != null -> colors.tertiaryContainer
        message.headline != null -> colors.secondaryContainer
        else -> null
    }
    if (card == null) {
        ChatLine(message, replay, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        return
    }
    Column(
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(card)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(message.avatar, 32.dp)
            Column(Modifier.weight(1f)) {
                Text(message.author, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    message.amount ?: message.headline.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
            }
            message.sticker?.let { AsyncImage(it, contentDescription = null, modifier = Modifier.size(48.dp)) }
        }
        if (message.runs.isNotEmpty()) {
            val (text, inline) = chatText(message, withAuthor = false)
            Text(text, inlineContent = inline, style = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface))
        }
    }
}

@Composable
private fun ChatLine(message: ChatMessage, replay: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(message.avatar, 24.dp)
        val (text, inline) = chatText(message, withAuthor = true, time = if (replay && message.offsetMs >= 0) formatClock(message.offsetMs) else null)
        Text(text, inlineContent = inline, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
    }
}

/** The message as text with its custom emoji inline; the author's name goes first, coloured by role as on YouTube. */
@Composable
private fun chatText(message: ChatMessage, withAuthor: Boolean, time: String? = null): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val colors = MaterialTheme.colorScheme
    val inline = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        if (time != null) withStyle(SpanStyle(color = colors.outline)) { append("$time  ") }
        if (withAuthor) {
            val style = when (message.role) {
                ChatRole.OWNER -> SpanStyle(color = colors.onPrimaryContainer, background = colors.primaryContainer, fontWeight = FontWeight.Medium)
                ChatRole.MODERATOR -> SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)
                ChatRole.MEMBER -> SpanStyle(color = colors.tertiary, fontWeight = FontWeight.Medium)
                ChatRole.NONE -> SpanStyle(color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
            withStyle(style) { append(message.author) }
            if (message.verified) withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append(" ✓") }
            append("  ")
        }
        message.runs.forEachIndexed { index, run ->
            when (run) {
                is ChatRun.Text -> append(run.text)
                is ChatRun.Emoji -> if (run.custom && run.image != null) {
                    val id = "emoji$index"
                    appendInlineContent(id, run.text)
                    inline[id] = InlineTextContent(Placeholder(1.4.em, 1.4.em, PlaceholderVerticalAlign.TextCenter)) {
                        AsyncImage(run.image, contentDescription = run.text, modifier = Modifier.fillMaxSize())
                    }
                } else {
                    append(run.text)
                }
            }
        }
    }
    return text to inline
}

// YouTube's own limit for a chat message.
private const val MAX_MESSAGE_LENGTH = 200
