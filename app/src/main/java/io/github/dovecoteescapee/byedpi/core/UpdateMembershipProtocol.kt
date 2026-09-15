package io.github.dovecoteescapee.byedpi.core

import java.net.URI

/** Public protocol validation; no Telegram credentials belong in the client. */
internal object UpdateMembershipProtocol {
    private val secret = Regex("^[A-Za-z0-9_-]{43}$")
    private val version = Regex("^(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})$")
    private val botPath = Regex("^/[A-Za-z][A-Za-z0-9_]{4,31}$")

    fun isSecret(value: String): Boolean = secret.matches(value) && value.last() in "AEIMQUYcgkosw048"

    fun isVersion(value: String): Boolean = version.matches(value)

    fun isServiceUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        value.length <= 200 && uri.scheme == "https" &&
            uri.host != null && uri.host.contains('.') &&
            !uri.host.contains(':') && !uri.host.contains('[') &&
            !uri.host.all { it.isDigit() || it == '.' } &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in listOf(-1, 443) && uri.rawPath in listOf("", "/")
    }.getOrDefault(false)

    fun isBotUrl(value: String, sessionId: String): Boolean = runCatching {
        val uri = URI(value)
        isSecret(sessionId) && uri.scheme == "https" && uri.host == "t.me" &&
            uri.port == -1 && uri.userInfo == null && uri.rawFragment == null &&
            botPath.matches(uri.rawPath) && uri.rawPath.endsWith("bot", ignoreCase = true) &&
            uri.rawQuery == "start=$sessionId"
    }.getOrDefault(false)

    fun matchesSession(
        expectedVersion: String,
        expectedNonce: String,
        actualVersion: String,
        actualNonce: String,
        expiresAt: Long,
        sessionExpiresAt: Long,
        now: Long,
    ): Boolean = isVersion(expectedVersion) && isSecret(expectedNonce) &&
        actualVersion == expectedVersion && actualNonce == expectedNonce &&
        expiresAt > now && expiresAt <= sessionExpiresAt && expiresAt <= now + 600L
}
