package io.github.dovecoteescapee.byedpi.core

/** Counts successful endpoint probes separately from distinct product coverage. */
data class StrategyProbeResult(
    val successes: Int,
    val products: Int,
    val latencyMs: Long,
) {
    fun isEligible(minimumProducts: Int): Boolean = products >= minimumProducts

    fun isBetterThan(other: StrategyProbeResult?): Boolean = when {
        other == null -> true
        successes != other.successes -> successes > other.successes
        else -> latencyMs < other.latencyMs
    }

    companion object {
        fun fromEndpointResults(results: List<Pair<String, Long?>>): StrategyProbeResult {
            val successful = results.filter { it.second != null }
            return StrategyProbeResult(
                successes = successful.size,
                products = successful.map { it.first }.distinct().size,
                latencyMs = successful.mapNotNull { it.second }.sum(),
            )
        }
    }
}
