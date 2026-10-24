package dev.wahha.wtube.dpi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One byedpi desync strategy from the presets; [id] is stable across list updates, so per-network memory survives them. */
@Serializable
data class Strategy(val id: String, val args: String, val note: String = "")

/** `dpi/strategies.json` in the repository, also bundled as an asset. */
@Serializable
data class StrategyList(val version: Int, val minApp: Int = 0, val strategies: List<Strategy>)

object Strategies {
    /** The fake SNI put in place of `{sni}`: a host every censor lets through. */
    const val FAKE_SNI = "www.google.com"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses a preset list; entries whose arguments [validate] refuses are dropped, not the list.
     * Null when the text is not a list at all or needs a newer app than [appVersion].
     */
    fun parse(text: String, appVersion: Int): StrategyList? {
        val list = runCatching { json.decodeFromString<StrategyList>(text) }.getOrNull() ?: return null
        if (list.minApp > appVersion) return null
        val valid = list.strategies.filter { it.id.isNotBlank() && validate(it.args) != null }.distinctBy { it.id }
        return list.copy(strategies = valid).takeIf { valid.isNotEmpty() }
    }

    private const val V4_SUFFIX = "-v4"

    /**
     * [strategy] over IPv4 only. byedpi resolves the names it gets itself and connects to the first address
     * alone, on a phone with IPv6 usually that one, and a censor may treat the two ways differently (0.20.4).
     */
    fun v4(strategy: Strategy) = Strategy(strategy.id + V4_SUFFIX, "-X " + strategy.args, strategy.note)

    /** The strategy [id] names among [presets]: one of them or the [v4] twin of one. */
    fun find(presets: List<Strategy>, id: String): Strategy? = presets.firstOrNull { it.id == id }
        ?: id.removeSuffix(V4_SUFFIX).takeIf { it != id }?.let { base -> presets.firstOrNull { it.id == base } }?.let(::v4)

    // Short option -> takes an argument. Only what shapes the desync; listening address, files, daemon mode,
    // redirects and debug output are ours to set or must never be set from a downloaded list.
    private val Options = mapOf(
        'N' to false, 'X' to false, 'U' to false, 'F' to false, 'S' to false, 'Z' to false, 'Y' to false,
        'b' to true, 'c' to true, 'A' to true, 'L' to true, 'u' to true, 'T' to true, 'K' to true,
        'H' to true, 'j' to true, 'V' to true, 'R' to true, 's' to true, 'd' to true, 'o' to true,
        'q' to true, 'f' to true, 'n' to true, 't' to true, 'l' to true, 'O' to true, 'Q' to true,
        'e' to true, 'M' to true, 'r' to true, 'm' to true, 'a' to true, 'g' to true, 'W' to true, '#' to true,
    )
    private val LongOptions = mapOf(
        "no-domain" to 'N', "no-ipv6" to 'X', "no-udp" to 'U', "tfo" to 'F', "md5sig" to 'S', "wait-send" to 'Z',
        "drop-sack" to 'Y', "buf-size" to 'b', "max-conn" to 'c', "auto" to 'A', "auto-mode" to 'L', "cache-ttl" to 'u',
        "timeout" to 'T', "proto" to 'K', "hosts" to 'H', "ipset" to 'j', "pf" to 'V', "round" to 'R', "split" to 's',
        "disorder" to 'd', "oob" to 'o', "disoob" to 'q', "fake" to 'f', "fake-sni" to 'n', "ttl" to 't',
        "fake-data" to 'l', "fake-offset" to 'O', "fake-tls-mod" to 'Q', "oob-data" to 'e', "mod-http" to 'M',
        "tlsrec" to 'r', "tlsminor" to 'm', "udp-fake" to 'a', "def-ttl" to 'g', "await-int" to 'W', "comment" to '#',
    )
    // These read a file unless the value starts with ':' (inline data).
    private val InlineOnly = setOf('H', 'j', 'l')

    /**
     * The argv tail for byedpi (without the program name, address and port) from a preset's [args], with
     * `{sni}` filled in; null if anything in it is not a known strategy option.
     */
    fun validate(args: String): List<String>? {
        val tokens = split(args)?.map { if (it == "{sni}") FAKE_SNI else it } ?: return null
        if (tokens.isEmpty()) return null
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i++]
            if (token.startsWith("--")) {
                val name = token.drop(2).substringBefore('=')
                val option = LongOptions[name] ?: return null
                if (Options.getValue(option)) {
                    val value = if ('=' in token) token.substringAfter('=') else tokens.getOrNull(i++) ?: return null
                    if (option in InlineOnly && !value.startsWith(':')) return null
                } else if ('=' in token) {
                    return null
                }
                continue
            }
            if (token.length < 2 || token[0] != '-') return null
            // A cluster like -SN, or an option with its value attached, like -s1:5+sm.
            var j = 1
            while (j < token.length) {
                val option = token[j++]
                val takesValue = Options[option] ?: return null
                if (!takesValue) continue
                val value = if (j < token.length) token.substring(j) else tokens.getOrNull(i++) ?: return null
                if (option in InlineOnly && !value.startsWith(':')) return null
                break
            }
        }
        return tokens
    }

    /** Splits like a shell: whitespace separates, single or double quotes group; null on an unclosed quote. */
    fun split(text: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var started = false
        for (c in text) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> { quote = c; started = true }
                c.isWhitespace() -> if (started) {
                    tokens += current.toString()
                    current.clear()
                    started = false
                }
                else -> { current.append(c); started = true }
            }
        }
        if (quote != null) return null
        if (started) tokens += current.toString()
        return tokens
    }
}
