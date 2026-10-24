package dev.wahha.wtube.dpi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Names for the [Relay], not for byedpi: byedpi takes the first address the phone's resolver gives, usually an
 * IPv6 one, and an operator may answer YouTube's names wrongly. DNS-over-HTTPS goes to resolvers by address
 * (no name for a censor to read), the phone's own resolver is the last resort and its answers go after.
 */
class Resolver(
    private val client: () -> OkHttpClient,
    private val endpoints: List<String> = ENDPOINTS,
    private val system: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val note: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val addresses: List<InetAddress>, val expires: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val noted = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var dohDownUntil = 0L

    /** Addresses to try for [host], IPv4 first; empty if nothing resolves. Blocks. */
    fun resolve(host: String): List<InetAddress> {
        literal(host)?.let { return listOf(it) }
        val key = host.lowercase()
        cache[key]?.takeIf { it.expires > clock() }?.let { return it.addresses }
        var ttl = DEFAULT_TTL_S
        val doh = if (clock() < dohDownUntil) emptyList() else fromDoh(key)?.also { ttl = it.second }?.first.orEmpty()
        if (doh.isEmpty() && clock() >= dohDownUntil) dohDownUntil = clock() + DOH_DOWN_MS
        val sys = runCatching { system(key) }.getOrDefault(emptyList())
        if (noted.add(key) && (doh.isEmpty() || sys.isNotEmpty() && sys.none { it in doh })) {
            note("dns $key: system ${sys.joinToString(",") { it.hostAddress.orEmpty() }.ifEmpty { "none" }}; " +
                "doh ${doh.joinToString(",") { it.hostAddress.orEmpty() }.ifEmpty { "none" }}")
        }
        val all = ordered(doh + sys).take(MAX_ADDRESSES)
        if (all.isNotEmpty()) cache[key] = Entry(all, clock() + (if (doh.isEmpty()) FALLBACK_TTL_S else ttl) * 1000)
        return all
    }

    fun forget(host: String) {
        cache.remove(host.lowercase())
    }

    /** Addresses and the shortest TTL (seconds) from the first resolver that answers; null if none does. */
    private fun fromDoh(host: String): Pair<List<InetAddress>, Long>? {
        for (endpoint in endpoints) {
            val a = CompletableFuture.supplyAsync({ query(endpoint, host, "A") }, pool)
            val aaaa = CompletableFuture.supplyAsync({ query(endpoint, host, "AAAA") }, pool)
            val v4 = runCatching { a.get(REQUEST_TIMEOUT_S * 2, TimeUnit.SECONDS) }.getOrNull()
            val v6 = runCatching { aaaa.get(REQUEST_TIMEOUT_S * 2, TimeUnit.SECONDS) }.getOrNull()
            if (v4 == null && v6 == null) continue
            val addresses = (v4?.first.orEmpty() + v6?.first.orEmpty())
            if (addresses.isEmpty()) continue
            val ttl = listOfNotNull(v4, v6).filter { it.first.isNotEmpty() }.minOf { it.second }
            return addresses to ttl.coerceIn(MIN_TTL_S, MAX_TTL_S)
        }
        return null
    }

    private fun query(endpoint: String, host: String, type: String): Pair<List<InetAddress>, Long>? {
        val url = endpoint.replace("{name}", host).replace("{type}", type)
        val request = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        return try {
            client().newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else parse(response.body.string())
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        /** `{name}` and `{type}` are filled in. By address: the TLS name of a resolver is what a censor blocks first. */
        val ENDPOINTS = listOf(
            "https://1.1.1.1/dns-query?name={name}&type={type}",
            "https://8.8.8.8/resolve?name={name}&type={type}",
        )
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "dns").apply { isDaemon = true } }
        private val json = Json { ignoreUnknownKeys = true }
        private const val REQUEST_TIMEOUT_S = 3L
        private const val DEFAULT_TTL_S = 300L
        private const val MIN_TTL_S = 60L
        private const val MAX_TTL_S = 600L
        /** Without a DoH answer the phone's own is used, and asked again soon. */
        private const val FALLBACK_TTL_S = 30L
        private const val DOH_DOWN_MS = 60_000L
        private const val MAX_ADDRESSES = 6

        /** The answer records of a DNS-JSON reply (Cloudflare, Google): A and AAAA addresses and the shortest TTL. */
        fun parse(text: String): Pair<List<InetAddress>, Long>? {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            if (root["Status"]?.jsonPrimitive?.int != 0) return null
            val answers = (root["Answer"] as? JsonArray).orEmpty()
            val found = answers.mapNotNull { element ->
                val record = element as? JsonObject ?: return@mapNotNull null
                val type = record["type"]?.jsonPrimitive?.int
                if (type != 1 && type != 28) return@mapNotNull null
                val address = literal(record["data"]?.jsonPrimitive?.content.orEmpty()) ?: return@mapNotNull null
                address to (record["TTL"]?.jsonPrimitive?.int ?: 0).toLong()
            }
            if (found.isEmpty()) return emptyList<InetAddress>() to DEFAULT_TTL_S
            return found.map { it.first } to found.minOf { it.second }
        }

        /** [text] as an address if it is one (no lookup ever), else null. */
        fun literal(text: String): InetAddress? {
            val bare = text.removePrefix("[").removeSuffix("]")
            val v4 = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(bare)
            val v6 = ':' in bare && bare.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
            return if (v4 || v6) runCatching { InetAddress.getByName(bare) }.getOrNull() else null
        }

        /** IPv4 before IPv6, each in the given order, without repeats. */
        fun ordered(addresses: List<InetAddress>): List<InetAddress> {
            val unique = addresses.distinct()
            return unique.filter { it is Inet4Address } + unique.filter { it !is Inet4Address }
        }
    }
}
