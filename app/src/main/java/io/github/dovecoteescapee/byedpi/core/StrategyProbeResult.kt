package io.github.dovecoteescapee.byedpi.core

/** Counts successful endpoint probes separately from distinct product coverage. */
data class StrategyProbeResult(
    val successes: Int,
    val successfulProducts: Set<String>,
    val latencyMs: Long,
    val udpLatencyMs: Long?,
) {
    val products: Int
        get() = successfulProducts.size

    fun isEligible(
        requiredProducts: Set<String>,
        requireUdp: Boolean = true,
    ): Boolean = successfulProducts.containsAll(requiredProducts) &&
        (!requireUdp || udpLatencyMs != null)

    fun isBetterThan(
        other: StrategyProbeResult?,
        priorityProducts: Set<String>,
        mediaReady: Boolean,
        otherMediaReady: Boolean,
    ): Boolean = when {
        other == null -> true
        priorityScore(priorityProducts) != other.priorityScore(priorityProducts) ->
            priorityScore(priorityProducts) > other.priorityScore(priorityProducts)
        mediaReady != otherMediaReady -> mediaReady
        successes != other.successes -> successes > other.successes
        else -> latencyMs < other.latencyMs
    }

    private fun priorityScore(priorityProducts: Set<String>): Int =
        successfulProducts.count { it in priorityProducts }

    companion object {
        fun fromEndpointResults(
            results: List<Pair<String, Long?>>,
            udpLatencyMs: Long?,
        ): StrategyProbeResult {
            val successful = results.filter { it.second != null }
            return StrategyProbeResult(
                successes = successful.size,
                successfulProducts = successful.map { it.first }.toSet(),
                latencyMs = successful.mapNotNull { it.second }.sum() +
                    (udpLatencyMs ?: 0L),
                udpLatencyMs = udpLatencyMs,
            )
        }
    }
}
