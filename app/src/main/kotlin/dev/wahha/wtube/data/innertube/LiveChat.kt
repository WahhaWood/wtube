package dev.wahha.wtube.data.innertube

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.SessionExpiredException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import java.util.UUID

/** Where a video's chat starts: the reload token of the watch page, and whether it is a replay of a past stream. */
data class ChatSource(val token: String, val replay: Boolean)

/** A piece of a chat message: plain text, or an emoji (a custom one has only its picture). */
sealed interface ChatRun {
    data class Text(val text: String) : ChatRun
    data class Emoji(val text: String, val image: String?, val custom: Boolean) : ChatRun
}

enum class ChatRole { NONE, MEMBER, MODERATOR, OWNER }

/**
 * One line of a live chat. [amount] is set for Super Chats and Super Stickers, [headline] for membership
 * and gift announcements; [offsetMs] is the message's place in the video in a replay, -1 live.
 */
data class ChatMessage(
    val id: String,
    val author: String,
    val authorId: String?,
    val avatar: String?,
    val runs: List<ChatRun>,
    val role: ChatRole = ChatRole.NONE,
    val verified: Boolean = false,
    val amount: String? = null,
    val headline: String? = null,
    val sticker: String? = null,
    val offsetMs: Long = -1,
)

/** What a chat response asks for, in order. */
sealed interface ChatAction {
    data class Add(val message: ChatMessage) : ChatAction
    data class Replace(val id: String, val message: ChatMessage) : ChatAction
    data class Remove(val id: String) : ChatAction
    data class RemoveAuthor(val channelId: String) : ChatAction
    data class Pin(val message: ChatMessage) : ChatAction
    data object Unpin : ChatAction
}

/** One of YouTube's chat views: Top chat (filtered) or Live chat (everything), each with its reload token. */
data class ChatMode(val title: String, val token: String, val selected: Boolean)

/** What the account may do in the chat: send with [sendParams], or only read [restriction] (members only and so on). */
data class ChatInput(val sendParams: String?, val restriction: String?)

/**
 * One chat response. [continuation] is null once the chat is over; [waitMs] is how long YouTube asks to
 * wait before the next poll (for a replay: until its last message); [seekToken] restarts a replay elsewhere.
 */
class ChatPage(
    val actions: List<ChatAction>,
    val continuation: String?,
    val waitMs: Long,
    val seekToken: String?,
    val modes: List<ChatMode>,
    val input: ChatInput?,
)

/** A live stream's chat and the chat replay of a past one, through the web client's `live_chat` endpoints. */
object LiveChat {
    /** The next piece of chat after [token]; a replay needs the player's position, [offsetMs]. */
    suspend fun page(token: String, replay: Boolean, offsetMs: Long = 0): ChatPage {
        val session = Account.active
        val response = if (replay) {
            InnerTube.post(session, "live_chat/get_live_chat_replay") {
                value("continuation", token)
                `object`("currentPlayerState").value("playerOffsetMs", offsetMs.coerceAtLeast(0).toString()).end()
            }
        } else {
            InnerTube.post(session, "live_chat/get_live_chat") { value("continuation", token) }
        }
        return LiveChatParser.page(response)
    }

    /** Posts [text] as the account; returns the message as YouTube shows it, if it said. */
    suspend fun send(params: String, text: String): ChatMessage? {
        val response = InnerTube.post(Account.active ?: throw SessionExpiredException(), "live_chat/send_message") {
            value("params", params)
            `object`("richMessage").array("textSegments").`object`().value("text", text).end().end().end()
            value("clientMessageId", UUID.randomUUID().toString())
        }
        return LiveChatParser.sent(response)
    }
}

/** Reads `live_chat` responses: actions, the next token, the chat views and the account's input box. */
object LiveChatParser {
    private val Continuations = listOf("invalidationContinuationData", "timedContinuationData", "reloadContinuationData", "liveChatReplayContinuationData")

    fun page(response: JsonObject): ChatPage {
        val chat = response.getObject("continuationContents").getObject("liveChatContinuation")
        var continuation: String? = null
        var waitMs = 0L
        var seekToken: String? = null
        for (entry in chat.getArray("continuations").filterIsInstance<JsonObject>()) {
            seekToken = seekToken ?: entry.getObject("playerSeekContinuationData").getString("continuation")
            if (continuation != null) continue
            val data = Continuations.firstNotNullOfOrNull { key -> entry.getObject(key).takeIf { it.has("continuation") } } ?: continue
            continuation = data.getString("continuation")
            waitMs = data.getLong("timeoutMs", data.getLong("timeUntilLastMessageMsec", 0))
        }
        val modes = FeedParser.findFirst(chat["header"], "sortFilterSubMenuRenderer")?.getArray("subMenuItems")
            ?.filterIsInstance<JsonObject>()?.mapNotNull { item ->
                val token = item.getObject("continuation").getObject("reloadContinuationData").getString("continuation") ?: return@mapNotNull null
                ChatMode(item.getString("title").orEmpty(), token, item.getBoolean("selected", false))
            }.orEmpty()
        return ChatPage(actions(chat.getArray("actions"), -1), continuation, waitMs, seekToken, modes, input(chat.getObject("actionPanel")))
    }

    /** The account's own message in a `send_message` response. */
    fun sent(response: JsonObject): ChatMessage? =
        actions(response.getArray("actions"), -1).firstNotNullOfOrNull { (it as? ChatAction.Add)?.message }

    private fun actions(list: JsonArray, offsetMs: Long): List<ChatAction> = list.filterIsInstance<JsonObject>().flatMap { action ->
        action.entries.flatMap { (key, value) ->
            val body = value as? JsonObject ?: return@flatMap emptyList()
            when (key) {
                "replayChatItemAction" -> actions(body.getArray("actions"), body.getString("videoOffsetTimeMsec")?.toLongOrNull() ?: offsetMs)
                "addChatItemAction" -> listOfNotNull(message(body.getObject("item"), offsetMs)?.let(ChatAction::Add))
                "replaceChatItemAction" -> listOfNotNull(
                    message(body.getObject("replacementItem"), offsetMs)?.let { ChatAction.Replace(body.getString("targetItemId").orEmpty(), it) },
                )
                "removeChatItemAction", "markChatItemAsDeletedAction" -> listOfNotNull(body.getString("targetItemId")?.let(ChatAction::Remove))
                "removeChatItemByAuthorAction", "markChatItemsByAuthorAsDeletedAction" ->
                    listOfNotNull(body.getString("externalChannelId")?.let(ChatAction::RemoveAuthor))
                "addBannerToLiveChatCommand" -> listOfNotNull(
                    message(body.getObject("bannerRenderer").getObject("liveChatBannerRenderer").getObject("contents"), offsetMs)?.let(ChatAction::Pin),
                )
                "removeBannerForLiveChatCommand" -> listOf(ChatAction.Unpin)
                else -> emptyList()
            }
        }
    }

    /** A chat item renderer as a message; null for system lines, placeholders and what is not a message. */
    private fun message(item: JsonObject, offsetMs: Long): ChatMessage? {
        val (key, renderer) = item.entries.firstOrNull { it.value is JsonObject } ?: return null
        renderer as JsonObject
        return when (key) {
            "liveChatTextMessageRenderer" -> base(renderer, offsetMs)
            "liveChatPaidMessageRenderer" -> base(renderer, offsetMs)?.copy(amount = getTextFromObject(renderer.getObject("purchaseAmountText")))
            "liveChatPaidStickerRenderer" -> base(renderer, offsetMs)?.copy(
                amount = getTextFromObject(renderer.getObject("purchaseAmountText")),
                sticker = image(renderer.getObject("sticker")),
            )
            "liveChatMembershipItemRenderer" -> base(renderer, offsetMs)?.copy(
                headline = getTextFromObject(renderer.getObject("headerPrimaryText")) ?: getTextFromObject(renderer.getObject("headerSubtext")),
            )
            "liveChatSponsorshipsGiftPurchaseAnnouncementRenderer" -> {
                val header = renderer.getObject("header").getObject("liveChatSponsorshipsHeaderRenderer")
                base(header, offsetMs, id = renderer.getString("id"))?.copy(headline = getTextFromObject(header.getObject("primaryText")))
            }
            "liveChatSponsorshipsGiftRedemptionAnnouncementRenderer" -> base(renderer, offsetMs)?.let { it.copy(headline = it.runs.text(), runs = emptyList()) }
            else -> null
        }
    }

    private fun base(renderer: JsonObject, offsetMs: Long, id: String? = renderer.getString("id")): ChatMessage? {
        val author = renderer.getObject("authorName").getString("simpleText") ?: return null
        var role = ChatRole.NONE
        var verified = false
        for (badge in renderer.getArray("authorBadges").filterIsInstance<JsonObject>().map { it.getObject("liveChatAuthorBadgeRenderer") }) {
            when (badge.getObject("icon").getString("iconType")) {
                "OWNER" -> role = ChatRole.OWNER
                "MODERATOR" -> if (role != ChatRole.OWNER) role = ChatRole.MODERATOR
                "VERIFIED" -> verified = true
                // Members wear the channel's own picture as their badge.
                null -> if (role == ChatRole.NONE && badge.has("customThumbnail")) role = ChatRole.MEMBER
            }
        }
        return ChatMessage(
            id = id ?: return null,
            author = author,
            authorId = renderer.getString("authorExternalChannelId"),
            avatar = image(renderer.getObject("authorPhoto")),
            runs = runs(renderer.getObject("message")),
            role = role,
            verified = verified,
            offsetMs = offsetMs,
        )
    }

    private fun runs(message: JsonObject): List<ChatRun> = message.getArray("runs").filterIsInstance<JsonObject>().mapNotNull { run ->
        val emoji = run.getObject("emoji")
        when {
            run.has("text") -> ChatRun.Text(run.getString("text", ""))
            emoji.isEmpty() -> null
            else -> {
                val custom = emoji.getBoolean("isCustomEmoji", false)
                val shortcut = emoji.getArray("shortcuts").firstOrNull() as? String
                ChatRun.Emoji(if (custom) shortcut ?: "" else emoji.getString("emojiId") ?: shortcut ?: "", image(emoji.getObject("image")), custom)
            }
        }
    }

    private fun List<ChatRun>.text() = joinToString("") { if (it is ChatRun.Text) it.text else (it as ChatRun.Emoji).text }

    private fun image(holder: JsonObject): String? =
        holder.getArray("thumbnails").filterIsInstance<JsonObject>().lastOrNull()?.getString("url")?.let { if (it.startsWith("//")) "https:$it" else it }

    private fun input(panel: JsonObject): ChatInput? {
        panel.getObject("liveChatMessageInputRenderer").takeIf { it.isNotEmpty() }?.let { box ->
            val params = FeedParser.findFirst(box, "sendLiveChatMessageEndpoint")?.getString("params")
            return ChatInput(params, if (params == null) getTextFromObject(FeedParser.findFirst(box, "liveChatRestrictedParticipationRenderer")?.getObject("message")) else null)
        }
        panel.getObject("liveChatRestrictedParticipationRenderer").takeIf { it.isNotEmpty() }?.let { restricted ->
            return ChatInput(null, getTextFromObject(restricted.getObject("message")))
        }
        // Signed out the panel is a "Sign in to chat" button.
        return null
    }
}
