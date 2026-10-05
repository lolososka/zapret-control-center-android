package io.github.dovecoteescapee.byedpi.core

/**
 * The update banner is intentionally gated by the server-confirmed membership grant.
 * Keeping this decision separate from the view makes it difficult to accidentally
 * expose a download action before Telegram verification completes.
 */
internal object UpdateNoticePolicy {
    fun shouldShow(releaseAvailable: Boolean, membershipVerified: Boolean): Boolean =
        releaseAvailable && membershipVerified
}
