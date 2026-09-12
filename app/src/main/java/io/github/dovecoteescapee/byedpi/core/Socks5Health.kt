package io.github.dovecoteescapee.byedpi.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Confirms that the native listener is accepting and speaking SOCKS5. */
object Socks5Health {
    suspend fun awaitReady(host: String, port: Int, timeoutMs: Long = 3_000L): Boolean =
        withContext(Dispatchers.IO) {
            val connectHost = when (host.trim()) {
                "", "0.0.0.0" -> "127.0.0.1"
                "::", "[::]" -> "::1"
                else -> host.trim()
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (probe(connectHost, port)) return@withContext true
                delay(100L)
            }
            false
        }

    private fun probe(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.soTimeout = 350
            socket.connect(InetSocketAddress(host, port), 350)
            socket.getOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00))
                flush()
            }
            val reply = ByteArray(2)
            var offset = 0
            while (offset < reply.size) {
                val read = socket.getInputStream().read(reply, offset, reply.size - offset)
                if (read < 0) return false
                offset += read
            }
            reply[0] == 0x05.toByte() && reply[1] == 0x00.toByte()
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
