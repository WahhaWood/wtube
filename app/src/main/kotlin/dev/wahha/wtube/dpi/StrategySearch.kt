package dev.wahha.wtube.dpi

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Proxy

/** How a search ended. */
sealed interface SearchOutcome {
    /** YouTube works fine without the bypass in this network. */
    data object Direct : SearchOutcome

    /**
     * [strategy] works; [fast] = fast enough for 1080p, otherwise it was merely the best of the rest.
     * [backups]: other strategies that worked, best first, for the [Relay] to fall back on.
     */
    data class Found(
        val strategy: Strategy,
        val result: ProbeResult,
        val fast: Boolean,
        val backups: List<Strategy> = emptyList(),
    ) : SearchOutcome {
        /** What is remembered for the network: the strategy and its backups, by id. */
        val choice get() = (listOf(strategy) + backups).joinToString(",") { it.id }
    }

    data object None : SearchOutcome
}

/**
 * Tries [candidates] in order, as many at a time as there are [engines], each through its own proxy, after
 * checking the network directly. The search ends at the first batch that leaves two fast strategies, or one
 * fast and [BACKUPS] + 1 working (the earliest fast one wins); otherwise the best working one is taken.
 * The working ones that are left become its backups. [probe] gets null for the direct check; [proxyOf] the
 * engine's index and port.
 */
class StrategySearch(
    private val engines: List<ProxyEngine>,
    private val probe: suspend (Proxy?) -> ProbeResult,
    private val proxyOf: (engine: Int, port: Int) -> Proxy = { _, port -> localSocks(port) },
    /** The engine did not start for a strategy, which then counts as failed without having been tried at all. */
    private val onEngineFailure: (Strategy, String) -> Unit = { _, _ -> },
) {
    /** [onResult] sees every strategy's result as it comes; [onProgress] the number done out of the total. */
    suspend fun run(
        candidates: List<Strategy>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (Strategy, ProbeResult) -> Unit = { _, _ -> },
    ): SearchOutcome {
        onProgress(0, candidates.size)
        val direct = probe(null)
        if (direct.fast) return SearchOutcome.Direct
        val results = mutableListOf<Pair<Strategy, ProbeResult>>()
        for (batch in candidates.chunked(engines.size)) {
            val batchResults = coroutineScope {
                batch.mapIndexed { i, strategy -> async { strategy to test(engines[i], i, strategy) } }.awaitAll()
            }
            batchResults.forEach { (strategy, result) -> onResult(strategy, result) }
            results += batchResults
            onProgress(results.size, candidates.size)
            val fast = results.filter { it.second.fast }
            if (fast.size >= 2 || fast.isNotEmpty() && results.count { it.second.works } > BACKUPS) return found(results, fast.first(), true)
        }
        results.firstOrNull { it.second.fast }?.let { return found(results, it, true) }
        // Nothing fast: a working strategy that is quicker than going direct, else direct if that works at all.
        // One that also reached the other servers first, however slow: the rest may never connect for some videos.
        // Then one that did not slow down past the first megabyte.
        val best = results.filter { it.second.works }.maxWithOrNull(quality(results))
        return when {
            best != null && (!direct.works || best.second.kbps > direct.kbps) -> found(results, best, false)
            direct.works -> SearchOutcome.Direct
            else -> SearchOutcome.None
        }
    }

    private fun quality(results: List<Pair<Strategy, ProbeResult>>) =
        compareBy<Pair<Strategy, ProbeResult>>({ it.second.fast }, { it.second.allServers }, { it.second.deep != false }, { it.second.kbps }, { -results.indexOf(it) })

    private fun found(results: List<Pair<Strategy, ProbeResult>>, best: Pair<Strategy, ProbeResult>, fast: Boolean): SearchOutcome.Found {
        val backups = results.filter { it !== best && it.second.works }.sortedWith(quality(results).reversed()).take(BACKUPS).map { it.first }
        return SearchOutcome.Found(best.first, best.second, fast, backups)
    }

    private suspend fun test(engine: ProxyEngine, index: Int, strategy: Strategy): ProbeResult {
        val args = Strategies.validate(strategy.args) ?: return ProbeResult.Failed
        return try {
            val port = engine.start(args)
            probe(proxyOf(index, port))
        } catch (e: java.io.IOException) {
            onEngineFailure(strategy, e.message.orEmpty())
            ProbeResult.Failed
        } finally {
            withContext(NonCancellable) { engine.stop() }
        }
    }

    companion object {
        /** Working strategies kept next to the best one. */
        const val BACKUPS = 2
    }
}
