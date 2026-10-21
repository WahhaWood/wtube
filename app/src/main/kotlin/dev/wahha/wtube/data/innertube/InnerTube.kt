package dev.wahha.wtube.data.innertube

import com.grack.nanojson.JsonBuilder
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.grack.nanojson.JsonWriter
import dev.wahha.wtube.data.Http
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Cookies
import dev.wahha.wtube.data.account.Identity
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.account.SessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException

/**
 * youtube.com's own API as the signed-in web client: what NewPipeExtractor cannot do without an account.
 * The request context and client version come from the extractor, so they move with it.
 */
object InnerTube {
    private const val API = "https://www.youtube.com/youtubei/v1/"
    private const val WEB_CLIENT_ID = "1"
    private const val ACCOUNT_MENU = "account/account_menu"
    private val JsonType = "application/json".toMediaType()

    /** A browse page; [params] picks a channel tab, [query] searches one. Without a [session] it is anonymous. */
    suspend fun browse(
        session: Session?,
        browseId: String? = null,
        continuation: String? = null,
        params: String? = null,
        query: String? = null,
    ): JsonObject =
        post(session, "browse") {
            if (continuation != null) {
                value("continuation", continuation)
            } else {
                value("browseId", browseId)
                params?.let { value("params", it) }
                query?.let { value("query", it) }
            }
        }

    /**
     * The watch page's data for [videoId] as the account sees it, or the page [continuation] points to
     * (comments). Without a [session] it is the anonymous web page: comments with sorting and replies.
     */
    suspend fun next(session: Session?, videoId: String? = null, continuation: String? = null): JsonObject =
        post(session, "next") {
            if (continuation != null) value("continuation", continuation) else value("videoId", videoId)
        }

    /** The watch page HTML: the only place the web player's stats URLs come from without a PO token. */
    suspend fun watchPage(session: Session, videoId: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://www.youtube.com/watch?v=$videoId&hl=en").get().signedBy(session).build()
        execute(session, request)
    }

    /** A stats ping of the web player; it answers 204 with no body. */
    suspend fun ping(session: Session, url: String) = withContext(Dispatchers.IO) {
        execute(session, Request.Builder().url(url).get().signedBy(session).build())
        Unit
    }

    /** Who [session] acts as, or null when YouTube does not see it as signed in. Network errors are thrown. */
    suspend fun whoAmI(session: Session): Identity? = try {
        FeedParser.activeAccount(post(session, ACCOUNT_MENU))
    } catch (_: SessionExpiredException) {
        null
    }

    /** The accounts and brand channels [session] can switch to. */
    suspend fun identities(session: Session): List<Identity> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://www.youtube.com/getAccountSwitcherEndpoint").get().signedBy(session).build()
        // The body starts with the anti-JSON-hijacking prefix `)]}'`.
        val text = execute(session, request).substringAfter('\n')
        FeedParser.identities(JsonParser.`object`().from(text))
    }

    suspend fun post(session: Session?, endpoint: String, fill: JsonBuilder<JsonObject>.() -> Unit = {}): JsonObject {
        val json = rawPost(session, endpoint, fill)
        if (session != null && isLoggedOut(json)) {
            // One "logged out" answer can be a hiccup; the session is dropped only when the account menu agrees.
            val again = if (endpoint == ACCOUNT_MENU) json else rawPost(session, ACCOUNT_MENU)
            if (isLoggedOut(again)) throw expired(session)
        }
        return json
    }

    private suspend fun rawPost(session: Session?, endpoint: String, fill: JsonBuilder<JsonObject>.() -> Unit = {}): JsonObject =
        withContext(Dispatchers.IO) {
            // The language alone: the extractor's localization is "en-<phone region>", and YouTube answers
            // an hl like "en-RU" with 400 INVALID_ARGUMENT on every endpoint.
            val language = Localization(NewPipe.getPreferredLocalization().languageCode)
            val body = YoutubeParsingHelper.prepareDesktopJsonBuilder(language, NewPipe.getPreferredContentCountry())
                .apply(fill)
                .done()
            val request = Request.Builder()
                .url("$API$endpoint?prettyPrint=false")
                .post(JsonWriter.string(body).toRequestBody(JsonType))
                .signedBy(session)
                .build()
            parseJson(request.url.encodedPath, execute(session, request))
        }

    private fun isLoggedOut(json: JsonObject): Boolean =
        json.getObject("responseContext").getObject("mainAppWebResponseContext").getBoolean("loggedOut", false)

    private fun execute(session: Session?, request: Request): String =
        Http.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) return@use response.body.string()
            val body = response.body.string()
            when (failureOf(response.code, body)) {
                Failure.SessionRejected -> if (session != null) throw expired(session)
                Failure.Antibot -> throw ReCaptchaException("reCaptcha Challenge requested", request.url.toString())
                Failure.Http -> {}
            }
            throw InnerTubeHttpException(response.code, request.url.encodedPath, errorReason(body))
        }

    // YouTube's JSON error says what it disliked, e.g. "INVALID_ARGUMENT: Request contains an invalid argument."
    private fun errorReason(body: String): String? = runCatching {
        val error = JsonParser.`object`().from(body).getObject("error")
        listOfNotNull(error.getString("status"), error.getString("message")).joinToString(": ").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Marks the stored session expired; a candidate still being checked at sign-in is just rejected. */
    private fun expired(session: Session): SessionExpiredException {
        if (session == Account.session.value) Account.expire()
        return SessionExpiredException()
    }

    /** The web client's headers, plus the account's cookies and their signature when there is a [session]. */
    private fun Request.Builder.signedBy(session: Session?): Request.Builder = apply {
        header("User-Agent", Http.USER_AGENT)
        header("Origin", Cookies.ORIGIN)
        header("X-Youtube-Client-Name", WEB_CLIENT_ID)
        header("X-Youtube-Client-Version", YoutubeParsingHelper.getClientVersion())
        if (session == null) return@apply
        header("Cookie", session.cookie)
        Cookies.authorization(Cookies.parse(session.cookie), System.currentTimeMillis() / 1000)?.let { header("Authorization", it) }
        header("X-Origin", Cookies.ORIGIN)
        header("Referer", "${Cookies.ORIGIN}/")
        header("X-Goog-AuthUser", session.authUser.toString())
        // What the web client and yt-dlp send with a signed-in session.
        header("X-Youtube-Bootstrap-Logged-In", "true")
        session.pageId?.let { header("X-Goog-PageId", it) }
    }
}

/** What an unsuccessful InnerTube answer means: the account is refused, YouTube suspects a bot, or just an error. */
internal enum class Failure { SessionRejected, Antibot, Http }

private val antibotMarks = listOf("unusual traffic", "captcha", "/sorry/", "not a robot")

/**
 * 401 means the cookies are refused. 403 does too, unless the body is Google's bot-check page (HTML, not
 * the JSON error of the API): that one says nothing about the session. 429 is always rate limiting.
 */
internal fun failureOf(code: Int, body: String): Failure = when {
    code == 429 -> Failure.Antibot
    code == 401 -> Failure.SessionRejected
    code == 403 -> if (antibotMarks.any { body.contains(it, ignoreCase = true) }) Failure.Antibot else Failure.SessionRejected
    else -> Failure.Http
}

/** YouTube answered 200 but not with the JSON we asked for (a changed protocol or a block page). */
class InnerTubeFormatException(path: String, what: String) : Exception("InnerTube $path: $what")

/** The JSON object in [body]; anything else is told apart as a bot check, an HTML page or plain garbage. */
internal fun parseJson(path: String, body: String): JsonObject = try {
    JsonParser.`object`().from(body)
} catch (e: JsonParserException) {
    val head = body.trimStart()
    throw when {
        antibotMarks.any { body.contains(it, ignoreCase = true) } -> ReCaptchaException("reCaptcha Challenge requested", path)
        head.isEmpty() -> InnerTubeFormatException(path, "empty body")
        head.startsWith("<") -> InnerTubeFormatException(path, "HTML instead of JSON")
        else -> InnerTubeFormatException(path, "not JSON")
    }
}

/** YouTube answered, but with an HTTP error: the connection itself is fine. */
class InnerTubeHttpException(val code: Int, path: String, reason: String? = null) :
    IOException("InnerTube $path: HTTP $code" + (reason?.let { " ($it)" } ?: ""))
