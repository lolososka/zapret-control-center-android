package io.github.dovecoteescapee.byedpi.core

import android.os.SystemClock
import android.util.Base64
import io.github.dovecoteescapee.byedpi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection

object UpdateMembershipRepository {
    private const val MAX_RESPONSE_BYTES = 8 * 1024
    private const val SESSION_SECONDS = 600L

    class Session internal constructor(
        val id: String,
        internal val token: String,
        val version: String,
        internal val nonce: String,
        val botUrl: String,
        internal val expiresAt: Long,
        private val elapsedDeadline: Long,
    ) {
        fun isValid(version: String): Boolean = this.version == version &&
            System.currentTimeMillis() / 1000L < expiresAt &&
            SystemClock.elapsedRealtime() < elapsedDeadline
    }

    class Grant internal constructor(
        private val session: Session,
        private val expiresAt: Long,
        private val elapsedDeadline: Long,
    ) {
        fun isValid(version: String): Boolean = session.isValid(version) &&
            System.currentTimeMillis() / 1000L < expiresAt &&
            SystemClock.elapsedRealtime() < elapsedDeadline
    }

    enum class Status { Pending, NotMember, Verified }
    data class Check(val status: Status, val grant: Grant?)
    class SessionExpired : IOException("Membership session expired")
    class RateLimited(val retryAfterSeconds: Long) : IOException("Membership rate limit")

    fun isConfigured(): Boolean =
        UpdateMembershipProtocol.isServiceUrl(BuildConfig.UPDATE_MEMBERSHIP_URL)

    suspend fun create(version: String): Session = withContext(Dispatchers.IO) {
        if (!UpdateMembershipProtocol.isVersion(version)) throw IOException("Invalid version")
        val nonce = randomSecret()
        val response = request(
            "POST", "/v1/sessions",
            body = JSONObject().put("version", version).put("nonce", nonce),
        )
        val id = response.getString("id")
        val token = response.getString("token")
        val botUrl = response.getString("botUrl")
        val expiresAt = response.getLong("expiresAt")
        val now = System.currentTimeMillis() / 1000L
        if (!UpdateMembershipProtocol.isSecret(id) ||
            !UpdateMembershipProtocol.isSecret(token) ||
            !UpdateMembershipProtocol.isBotUrl(botUrl, id) ||
            expiresAt <= now || expiresAt > now + SESSION_SECONDS
        ) throw IOException("Invalid membership session")
        Session(
            id, token, version, nonce, botUrl, expiresAt,
            SystemClock.elapsedRealtime() + (expiresAt - now) * 1000L,
        )
    }

    /** Each authorization asks the server to recheck the bound Telegram account. */
    suspend fun authorize(session: Session): Check = withContext(Dispatchers.IO) {
        if (!session.isValid(session.version)) throw SessionExpired()
        val response = request("POST", "/v1/sessions/${session.id}/authorize", session.token)
        val now = System.currentTimeMillis() / 1000L
        val expiresAt = response.getLong("expiresAt")
        if (response.getString("status") == "expired") throw SessionExpired()
        if (!UpdateMembershipProtocol.matchesSession(
                session.version, session.nonce,
                response.getString("version"), response.getString("nonce"),
                expiresAt, session.expiresAt, now,
            )
        ) throw IOException("Membership response does not match session")
        when (response.getString("status")) {
            "pending" -> Check(Status.Pending, null)
            "not_member" -> Check(Status.NotMember, null)
            "verified" -> Check(
                Status.Verified,
                Grant(session, expiresAt, SystemClock.elapsedRealtime() + (expiresAt - now) * 1000L),
            )
            "expired" -> throw SessionExpired()
            else -> throw IOException("Unknown membership state")
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
    ): JSONObject {
        if (!isConfigured()) throw IOException("Membership service is not configured")
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            val connection = URL(BuildConfig.UPDATE_MEMBERSHIP_URL.trimEnd('/') + path)
                .openConnection() as HttpsURLConnection
            var retryAfter: Long? = null
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = method
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-store")
                connection.setRequestProperty("User-Agent", "Zapret-Mobile/${BuildConfig.VERSION_NAME}")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                if (method == "POST") {
                    val bytes = (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                when (connection.responseCode) {
                    200, 201 -> {
                        val output = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (output.size() + read > MAX_RESPONSE_BYTES) {
                                    throw IOException("Membership response is too large")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        return JSONObject(output.toString("UTF-8"))
                    }
                    401, 410 -> throw SessionExpired()
                    429 -> {
                        val cooldown = connection.getHeaderField("Retry-After")
                            ?.toLongOrNull()?.coerceIn(1L, 60L) ?: 3L
                        // Retry a short concurrent check, not an exhausted minute quota.
                        if (cooldown > 5L || attempt == 2) throw RateLimited(cooldown)
                        retryAfter = cooldown
                    }
                    else -> throw IOException("Membership service unavailable")
                }
            } finally {
                connection.disconnect()
            }
            delay(requireNotNull(retryAfter) * 1000L)
        }
        throw IOException("Membership service unavailable")
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
