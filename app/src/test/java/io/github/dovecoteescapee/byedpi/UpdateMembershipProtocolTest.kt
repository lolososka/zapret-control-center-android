package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.UpdateMembershipProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMembershipProtocolTest {
    private val sessionId = "A".repeat(43)
    private val nonce = "n".repeat(42) + "A"
    private val now = 1_800_000_000L

    @Test
    fun serviceAcceptsOnlyPublicHttpsOrigins() {
        listOf(
            "https://updates.example.com",
            "https://updates.example.com/",
            "https://zapret-updates.account.workers.dev",
            "https://updates.example.com:443",
            "https://updates.example.com:443/",
        ).forEach { origin ->
            assertTrue("Expected safe origin: $origin", UpdateMembershipProtocol.isServiceUrl(origin))
        }
    }

    @Test
    fun serviceRejectsCredentialsNonHttpsAndUnexpectedUrlComponents() {
        listOf(
            "http://updates.example.com",
            "ftp://updates.example.com",
            "//updates.example.com",
            "https://user@updates.example.com",
            "https://user:secret@updates.example.com",
            "https://updates.example.com:8443",
            "https://updates.example.com/session",
            "https://updates.example.com//",
            "https://updates.example.com/%2f",
            "https://updates.example.com?mode=check",
            "https://updates.example.com?",
            "https://updates.example.com#session",
            "https://updates.example.com#",
        ).forEach { invalid ->
            assertFalse("Unsafe service URL: $invalid", UpdateMembershipProtocol.isServiceUrl(invalid))
        }
    }

    @Test
    fun serviceRejectsLocalhostAndIpLiteralsIncludingMappedIpv6() {
        listOf(
            "https://localhost",
            "https://127.0.0.1",
            "https://127.1",
            "https://192.168.0.1",
            "https://8.8.8.8",
            "https://[::1]",
            "https://[2001:db8::1]",
            "https://[::ffff:127.0.0.1]",
            "https://[::ffff:192.168.0.1]",
        ).forEach { invalid ->
            assertFalse("IP or local service URL: $invalid", UpdateMembershipProtocol.isServiceUrl(invalid))
        }
    }

    @Test
    fun serviceRejectsMalformedAndOversizedValuesWithoutThrowing() {
        listOf(
            "",
            "not a URL",
            "https://",
            "https:///updates.example.com",
            " https://updates.example.com",
            "https://updates.example.com ",
            "https://updates.example.com\n",
            "https://updates.example.com/%",
            "https://[::ffff:127.0.0.1",
            "https://" + "a".repeat(192) + ".example.com",
        ).forEach { invalid ->
            assertFalse("Malformed service URL: $invalid", UpdateMembershipProtocol.isServiceUrl(invalid))
        }
    }

    @Test
    fun botLinkRequiresExactTelegramHostAndSessionStartParameter() {
        assertTrue(UpdateMembershipProtocol.isBotUrl("https://t.me/Slag0dUpdatesBot?start=$sessionId", sessionId))
        assertTrue(UpdateMembershipProtocol.isBotUrl("https://t.me/abBot?start=$sessionId", sessionId))
        assertTrue(UpdateMembershipProtocol.isBotUrl("https://t.me/${"a".repeat(29)}Bot?start=$sessionId", sessionId))
        val urlSafeSessionId = "a_-".repeat(14) + "Y"
        assertTrue(UpdateMembershipProtocol.isBotUrl("https://t.me/Slag0dUpdatesBot?start=$urlSafeSessionId", urlSafeSessionId))
    }

    @Test
    fun botLinkRejectsPhishingHostsCredentialsAndOtherComponents() {
        listOf(
            "http://t.me/Slag0dUpdatesBot?start=$sessionId",
            "https://t.me.evil.example/Slag0dUpdatesBot?start=$sessionId",
            "https://evil.example/t.me/Slag0dUpdatesBot?start=$sessionId",
            "https://telegram.me/Slag0dUpdatesBot?start=$sessionId",
            "https://user@t.me/Slag0dUpdatesBot?start=$sessionId",
            "https://t.me@evil.example/Slag0dUpdatesBot?start=$sessionId",
            "https://t.me:443/Slag0dUpdatesBot?start=$sessionId",
            "https://t.me/Slag0dUpdatesBot?start=$sessionId#result",
            "https://t.me/Slag0dUpdatesBot?start=$sessionId#",
        ).forEach { invalid ->
            assertFalse("Unsafe bot link: $invalid", UpdateMembershipProtocol.isBotUrl(invalid, sessionId))
        }
    }

    @Test
    fun botLinkRejectsWrongUsernamePathAndEncodedOrExtraParameters() {
        listOf(
            "https://t.me/Slag0dworld?start=$sessionId",
            "https://t.me/aBot?start=$sessionId",
            "https://t.me/1UpdatesBot?start=$sessionId",
            "https://t.me/_UpdatesBot?start=$sessionId",
            "https://t.me/${"a".repeat(30)}Bot?start=$sessionId",
            "https://t.me/Slag0dUpdatesBot/?start=$sessionId",
            "https://t.me/Slag0dUpdatesBot/extra?start=$sessionId",
            "https://t.me/Slag0dUpdates%42ot?start=$sessionId",
            "https://t.me//Slag0dUpdatesBot?start=$sessionId",
            "https://t.me/Slag0dUpdatesBot",
            "https://t.me/Slag0dUpdatesBot?start=${"B".repeat(42) + "A"}",
            "https://t.me/Slag0dUpdatesBot?start=$sessionId&other=1",
            "https://t.me/Slag0dUpdatesBot?other=1&start=$sessionId",
            "https://t.me/Slag0dUpdatesBot?start=$sessionId&start=$sessionId",
            "https://t.me/Slag0dUpdatesBot?start%3D$sessionId",
            "https://t.me/Slag0dUpdatesBot?%73tart=$sessionId",
            "https://t.me/Slag0dUpdatesBot?start=%41${sessionId.drop(1)}",
        ).forEach { invalid ->
            assertFalse("Unexpected bot path or query: $invalid", UpdateMembershipProtocol.isBotUrl(invalid, sessionId))
        }
    }

    @Test
    fun secretsMustBeCanonicalThirtyTwoByteUrlSafeBase64() {
        assertTrue(UpdateMembershipProtocol.isSecret(sessionId))
        "AEIMQUYcgkosw048".forEach { canonicalFinalCharacter ->
            assertTrue(UpdateMembershipProtocol.isSecret("a_-".repeat(14) + canonicalFinalCharacter))
        }
        listOf("", "a".repeat(42), "a".repeat(44), "a".repeat(42) + "=",
            "a".repeat(42) + "+", "a".repeat(42) + "/", "a".repeat(42) + " ",
            "a".repeat(42) + "\n", "a".repeat(42) + "я", "a_-".repeat(14) + "Z",
        ).forEach { invalid ->
            assertFalse("Invalid secret: $invalid", UpdateMembershipProtocol.isSecret(invalid))
            assertFalse(UpdateMembershipProtocol.isBotUrl("https://t.me/Slag0dUpdatesBot?start=$invalid", invalid))
        }
    }

    @Test
    fun versionsRequireThreeCanonicalNumericComponents() {
        listOf("0.3.0", "1.10.100", "0.0.0", "99999.99999.99999").forEach { version ->
            assertTrue(UpdateMembershipProtocol.isVersion(version))
        }
        listOf("", "0.3", "0.3.0.1", "01.3.0", "0.03.0", "0.3.00",
            "+0.3.0", "-1.3.0", "0.3.0-debug", " 0.3.0", "0.3.0\n",
            "100000.3.0", "0.100000.0", "0.3.100000",
        ).forEach { invalid ->
            assertFalse("Invalid version: $invalid", UpdateMembershipProtocol.isVersion(invalid))
        }
    }

    @Test
    fun sessionMatchBindsNonceAndVersionAndAllowsOnlyFutureShortExpiry() {
        assertTrue(matches(expiresAt = now + 1))
        assertTrue(matches(expiresAt = now + 600))
        assertFalse(matches(expiresAt = now))
        assertFalse(matches(expiresAt = now - 1))
        assertFalse(matches(expiresAt = now + 601, sessionExpiresAt = now + 900))
        assertFalse(matches(expiresAt = now + 301, sessionExpiresAt = now + 300))
        assertTrue(matches(expiresAt = now + 300, sessionExpiresAt = now + 300))
        assertFalse(matches(sessionExpiresAt = now - 1))
    }

    @Test
    fun sessionMatchRejectsDifferentOrMalformedNonceAndVersion() {
        assertFalse(matches(actualNonce = "m".repeat(42) + "A"))
        assertFalse(matches(actualNonce = ""))
        assertFalse(matches(expectedNonce = "invalid", actualNonce = "invalid"))
        val noncanonicalNonce = "a_-".repeat(14) + "Z"
        assertFalse(matches(expectedNonce = noncanonicalNonce, actualNonce = noncanonicalNonce))
        assertFalse(matches(actualVersion = "0.3.1"))
        assertFalse(matches(actualVersion = "0.3.0-debug"))
        assertFalse(matches(expectedVersion = "0.3", actualVersion = "0.3"))
        assertFalse(matches(expectedVersion = "01.3.0", actualVersion = "01.3.0"))
        assertFalse(matches(expectedVersion = "100000.3.0", actualVersion = "100000.3.0"))
    }

    private fun matches(
        expectedVersion: String = "0.3.0",
        expectedNonce: String = nonce,
        actualVersion: String = expectedVersion,
        actualNonce: String = expectedNonce,
        expiresAt: Long = now + 60,
        sessionExpiresAt: Long = now + 600,
    ): Boolean = UpdateMembershipProtocol.matchesSession(
        expectedVersion, expectedNonce, actualVersion, actualNonce,
        expiresAt, sessionExpiresAt, now,
    )
}
