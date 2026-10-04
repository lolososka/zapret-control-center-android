package io.github.dovecoteescapee.byedpi.core

/**
 * Keeps the local Telegram listener configuration valid even when a damaged
 * or stale preference value is present. The native core accepts a u16 port,
 * while Android preferences can outlive several app versions.
 */
internal object TelegramProxyPortSelector {
    const val DEFAULT_PORT = 1443
    private const val MAX_CANDIDATES = 8

    fun sanitize(preferredPort: Int): Int =
        preferredPort.takeIf { it in 1..65_535 } ?: DEFAULT_PORT

    fun candidates(preferredPort: Int, availablePorts: Iterable<Int>): List<Int> {
        val result = LinkedHashSet<Int>(MAX_CANDIDATES)
        result += sanitize(preferredPort)
        for (port in availablePorts) {
            if (port in 1..65_535) result += port
            if (result.size >= MAX_CANDIDATES) break
        }
        return result.toList()
    }
}
