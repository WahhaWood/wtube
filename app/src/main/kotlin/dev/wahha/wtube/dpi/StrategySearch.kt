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

    /** [strategy] works; [fast] = fast enough for 1080p, otherwise it was merely the best of the rest. */
    data class Found(val strategy: Strategy, val result: ProbeResult, val fast: Boolean) : SearchOutcome

    data object None : SearchOutcome
}

/**
 * Tries [candidates] in order, as many at a time as there are [engines], each through its own proxy, after
 * checking the network directly. The first batch with a fast strategy ends the search (the earliest fast one
 * wins); otherwise the best working one is taken. [probe] gets null for the direct check.
 */
class StrategySearch(
    private val engines: List<ProxyEngine>,
    private val probe: suspend (Proxy?) -> ProbeResult,
    private val proxyOf: (Int) -> Proxy = ::localSocks,
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
                batch.mapIndexed { i, strategy -> async { strategy to test(engines[i], strategy) } }.awaitAll()
            }
            batchResults.forEach { (strategy, result) -> onResult(strategy, result) }
            results += batchResults
            onProgress(results.size, candidates.size)
            batchResults.firstOrNull { it.second.fast }?.let { (strategy, result) -> return SearchOutcome.Found(strategy, result, fast = true) }
        }
        // Nothing fast: a working strategy that is quicker than going direct, else direct if that works at all.
        val best = results.filter { it.second.works }.maxWithOrNull(compareBy({ it.second.kbps }, { -results.indexOf(it) }))
        return when {
            best != null && (!direct.works || best.second.kbps > direct.kbps) -> SearchOutcome.Found(best.first, best.second, fast = false)
            direct.works -> SearchOutcome.Direct
            else -> SearchOutcome.None
        }
    }

    private suspend fun test(engine: ProxyEngine, strategy: Strategy): ProbeResult {
        val args = Strategies.validate(strategy.args) ?: return ProbeResult.Failed
        return try {
            val port = engine.start(args)
            probe(proxyOf(port))
        } catch (e: java.io.IOException) {
            ProbeResult.Failed
        } finally {
            withContext(NonCancellable) { engine.stop() }
        }
    }
}
