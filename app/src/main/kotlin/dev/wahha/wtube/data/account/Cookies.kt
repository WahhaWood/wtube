package dev.wahha.wtube.data.account

import java.security.MessageDigest

/** The youtube.com cookie header a session signs in with, and the InnerTube auth derived from it. */
object Cookies {
    const val ORIGIN = "https://www.youtube.com"

    /** `name=value; name=value` as sent in a Cookie header; a pasted `Cookie:` prefix is ignored. */
    fun parse(header: String): Map<String, String> = header.trim()
        .removePrefix("Cookie:").removePrefix("cookie:")
        .split(';')
        .mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
        }
        .filter { it.first.isNotEmpty() }
        .toMap()

    fun format(cookies: Map<String, String>): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * youtube.com cookies from a Netscape cookies.txt (what browser exporters write): tab-separated
     * `domain, subdomains, path, secure, expiry, name, value`; `#HttpOnly_` marks an http-only cookie.
     */
    fun fromNetscape(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim().removePrefix("#HttpOnly_") }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 7 || !fields[0].trimStart('.').endsWith("youtube.com")) null else fields[5] to fields[6]
        }
        .toMap()

    /** Either form, whichever [text] is; empty when it holds no cookies. */
    fun fromUserInput(text: String): Map<String, String> =
        if ('\t' in text) fromNetscape(text) else parse(text)

    /** A session needs SAPISID (or its secure twin) to sign requests; without it Google did not log in. */
    fun canSign(cookies: Map<String, String>): Boolean = sapisid(cookies) != null

    /**
     * The Authorization header the web client sends, as yt-dlp builds it: `SAPISIDHASH <time>_<sha1>` and,
     * when the partitioned cookies exist, the 1P and 3P variants after it. The hash is
     * `SHA1("<time> <cookie> <origin>")` with the time in Unix seconds.
     */
    fun authorization(cookies: Map<String, String>, timeSeconds: Long, origin: String = ORIGIN): String? {
        val schemes = listOf(
            "SAPISIDHASH" to sapisid(cookies),
            "SAPISID1PHASH" to cookies["__Secure-1PAPISID"],
            "SAPISID3PHASH" to cookies["__Secure-3PAPISID"],
        )
        return schemes.filter { it.second != null }
            .takeIf { it.isNotEmpty() && sapisid(cookies) != null }
            ?.joinToString(" ") { (scheme, value) -> "$scheme ${timeSeconds}_${sha1("$timeSeconds $value $origin")}" }
    }

    private fun sapisid(cookies: Map<String, String>) = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
