package dev.wahha.wtube.data

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter

/**
 * YouTube is asked in the interface language (`hl`), because it translates video titles into it: with English
 * requests Russian videos came with machine-translated English titles. NewPipeExtractor, though, reads counts
 * and dates with English patterns only and pins `hl=en-GB` itself; so for a Russian interface the request's
 * `hl` is swapped ([NewPipeDownloader], [dev.wahha.wtube.data.innertube.InnerTube]) and the answer's count and
 * date phrases are put back into English ([toEnglish]) before anything parses them. Titles, descriptions and
 * comment texts are left as they are. The player request stays English: the extractor tells a members-only,
 * age-restricted or bot-check refusal by its English reason.
 */
object YouTubeLanguage {
    /** Set by tests; otherwise the interface language. */
    @Volatile internal var override: String? = null

    /** The `hl` to ask YouTube in: "ru" or "en". */
    val current: String get() = override ?: AppLocale.language

    val isEnglish: Boolean get() = current == "en"

    /** [json] with every count and date phrase in English; the text itself when it is not JSON. */
    fun toEnglish(json: String): String {
        val root = runCatching { JsonParser.any().from(json) }.getOrNull() ?: return json
        if (root !is JsonObject && root !is JsonArray) return json
        return if (walk(root, null)) JsonWriter.string(root) else json
    }

    /** [root]'s count and date phrases in English, changed in place. */
    fun toEnglish(root: JsonObject): JsonObject = root.also { walk(it, null) }

    // Subtrees with text people wrote: a title of "10 лет назад" stays one.
    private val Written = setOf("title", "headline", "description", "descriptionSnippet", "attributedDescription", "contentText", "snippetText", "message")

    /** Rewrites strings in place; true when anything changed. */
    private fun walk(node: Any?, key: String?): Boolean {
        var changed = false
        when (node) {
            is JsonObject -> for (entry in node.entries) {
                val k = entry.key
                // A comment's text: commentEntityPayload.properties.content.
                if (k in Written || k == "content" && key == "properties") continue
                val value = entry.value
                if (value is String) {
                    val english = phrase(value)
                    if (english !== value) {
                        entry.setValue(english)
                        changed = true
                    }
                } else if (walk(value, k)) changed = true
            }
            is JsonArray -> for (i in node.indices) {
                val value = node[i]
                if (value is String) {
                    val english = phrase(value)
                    if (english !== value) {
                        node[i] = english
                        changed = true
                    }
                } else if (walk(value, key)) changed = true
            }
        }
        return changed
    }

    /** One string in English when it is a whole count or date phrase; otherwise [text] itself. */
    internal fun phrase(text: String): String {
        if (text.length > 60 || text.none(Char::isDigit) && text != NO_VIEWS) return text
        if (text == NO_VIEWS) return "No views"
        Ago.matchEntire(text)?.let { m ->
            val (prefix, amount, unit, edited) = m.destructured
            val english = Units.entries.firstOrNull { unit.lowercase().startsWith(it.key) }?.value ?: return text
            val words = if (amount == "1") english else english + "s"
            val start = when (prefix.trim()) {
                "Трансляция закончилась" -> "Streamed "
                "Премьера состоялась" -> "Premiered "
                else -> ""
            }
            return "$start$amount $words ago" + if (edited.isNotEmpty()) " (edited)" else ""
        }
        Count.matchEntire(text)?.let { m ->
            val (number, multiplier, noun) = m.destructured
            if (multiplier.isEmpty() && noun.isEmpty()) return text
            val digits = number.filterNot(Char::isWhitespace).filterNot { it in Spaces }
            val value = if (multiplier.isEmpty()) digits.substringBefore(',') else digits.replace(',', '.')
            val suffix = Multipliers.entries.firstOrNull { multiplier.lowercase().startsWith(it.key) }?.value.orEmpty()
            val word = Nouns.entries.firstOrNull { noun.lowercase().startsWith(it.key) }?.value
            return listOfNotNull("$value$suffix", word).joinToString(" ")
        }
        // (гипотеза) A scheduled premiere or stream; not seen in a real answer yet.
        Scheduled.matchEntire(text)?.let { m ->
            val (kind, day, month, year, time) = m.destructured
            val start = if (kind.startsWith("Премьера")) "Premieres" else "Scheduled for"
            return "$start ${day.padStart(2, '0')}/$month/$year, $time"
        }
        return text
    }

    private const val NO_VIEWS = "Нет просмотров"
    private const val Spaces = "   "
    private const val Gap = "[\\s   ]*"

    private val Ago = Regex("^(Трансляция закончилась |Премьера состоялась )?(\\d+)$Gap([а-яё.]+)${Gap}назад( \\(изменено\\))?$")
    private val Count = Regex("^(\\d[\\d\\s   ]*(?:,\\d+)?)$Gap(тыс\\.?|тысяч[аи]?|млн|миллион(?:а|ов)?|млрд|миллиард(?:а|ов)?)?$Gap(просмотр[а-я]*|подписчик[а-я]*|зрител[а-я]*|комментари[а-я]*|видео)?$")
    private val Scheduled = Regex("^(Премьера|Запланировано на|Начало):?$Gap(\\d{1,2})\\.(\\d{2})\\.(\\d{4}),?$Gap(\\d{1,2}:\\d{2})$")

    // By the start of the word: "мес." and "месяцев" alike. Longer keys first where one starts another.
    private val Units = linkedMapOf(
        "сек" to "second", "мин" to "minute", "ч" to "hour", "д" to "day", "нед" to "week",
        "мес" to "month", "г" to "year", "л" to "year",
    )
    private val Multipliers = linkedMapOf("тыс" to "K", "млн" to "M", "миллион" to "M", "млрд" to "B", "миллиард" to "B")
    private val Nouns = linkedMapOf("просмотр" to "views", "подписчик" to "subscribers", "зрител" to "watching", "комментари" to "comments", "видео" to "videos")
}
