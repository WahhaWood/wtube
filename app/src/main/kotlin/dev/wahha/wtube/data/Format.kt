package dev.wahha.wtube.data

import java.util.Locale

/**
 * The app speaks Russian only, so plural rules, dates and YouTube's own texts follow Russian whatever
 * the device language is; the region stays the device's, it picks the trending lists.
 */
val AppLocale: Locale by lazy {
    Locale.Builder().setLanguage("ru").setRegion(Locale.getDefault().country.takeIf { it.length == 2 }.orEmpty()).build()
}

/** "3:07", "1:02:03"; live and unknown durations (<= 0) give an empty string. */
fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Player clock: like [formatDuration], but 0 reads "0:00". */
fun formatClock(millis: Long): String = formatDuration(millis.coerceAtLeast(0) / 1000).ifEmpty { "0:00" }

/**
 * YouTube-style short Russian counts: "999", "1,2 тыс.", "12 тыс.", "3,4 млн", "1,1 млрд".
 * Truncates rather than rounds, as YouTube does, so 1 999 is "1,9 тыс." and never "2 тыс.".
 */
fun formatCompact(count: Long): String {
    if (count < 1000) return count.coerceAtLeast(0).toString()
    val (unit, suffix) = when {
        count < 1_000_000 -> 1_000L to "тыс."
        count < 1_000_000_000 -> 1_000_000L to "млн"
        else -> 1_000_000_000L to "млрд"
    }
    val whole = count / unit
    if (whole >= 10) return "$whole $suffix"
    val tenth = count % unit * 10 / unit
    return if (tenth == 0L) "$whole $suffix" else "$whole,$tenth $suffix"
}

/** "только что", "5 минут назад", "1 неделю назад", "3 месяца назад", "2 года назад". */
fun formatAgo(thenMillis: Long, nowMillis: Long): String {
    val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    val amount = when {
        minutes < 1 -> return "только что"
        hours < 1 -> ruPlural(minutes, "минуту", "минуты", "минут")
        days < 1 -> ruPlural(hours, "час", "часа", "часов")
        days < 7 -> ruPlural(days, "день", "дня", "дней")
        days < 30 -> ruPlural(days / 7, "неделю", "недели", "недель")
        days < 365 -> ruPlural(days / 30, "месяц", "месяца", "месяцев")
        else -> ruPlural(days / 365, "год", "года", "лет")
    }
    return "$amount назад"
}

/** "1 год", "3 года", "11 лет", "21 год": Russian count agreement. */
fun ruPlural(n: Long, one: String, few: String, many: String): String {
    val form = when {
        n % 10 == 1L && n % 100 != 11L -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }
    return "$n $form"
}

/** Seconds from a YouTube `t` parameter: "95", "95s", "1m35s", "1h2m3s"; null when it is none of these. */
fun parseYoutubeTime(t: String): Long? {
    if (t.isEmpty()) return null
    t.toLongOrNull()?.let { return it }
    val match = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""").matchEntire(t) ?: return null
    val (h, m, s) = match.destructured
    return (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
}

/** "5 мин", "2 ч 05 мин"; under a minute reads "< 1 мин". */
fun formatWatchTime(seconds: Long): String {
    val minutes = seconds.coerceAtLeast(0) / 60
    return when {
        minutes < 1 -> "< 1 мин"
        minutes < 60 -> "$minutes мин"
        else -> "%d ч %02d мин".format(minutes / 60, minutes % 60)
    }
}
