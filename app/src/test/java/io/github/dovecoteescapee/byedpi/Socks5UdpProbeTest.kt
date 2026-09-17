package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.Socks5UdpProbe
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeNoException
import org.junit.Test

class Socks5UdpProbeTest {
    @Test(timeout = 10_000L)
    fun ipv4DnsRoundTripSucceeds() {
        FakeSocks5DnsRelay(InetAddress.getByName("127.0.0.1")).use { relay ->
            assertNotNull(Socks5UdpProbe.probe(relay.hostAddress, relay.port))
            relay.assertHealthy()
        }
    }

    @Test(timeout = 10_000L)
    fun wildcardIpv4BindAddressFallsBackToTcpPeer() {
        // Using a distinct loopback address prevents a send to 0.0.0.0 from
        // accidentally reaching a relay bound to the platform's usual 127.0.0.1.
        val loopback = InetAddress.getByName("127.0.0.2")
        FakeSocks5DnsRelay(loopback, advertiseWildcard = true).use { relay ->
            assertNotNull(Socks5UdpProbe.probe(relay.hostAddress, relay.port))
            relay.assertHealthy()
        }
    }

    @Test(timeout = 10_000L)
    fun ipv6DnsRoundTripSucceedsWhenLoopbackIsAvailable() {
        val relay = try {
            FakeSocks5DnsRelay(InetAddress.getByName("::1"))
        } catch (error: IOException) {
            assumeNoException("IPv6 loopback is unavailable", error)
            return
        }

        relay.use {
            assertNotNull(Socks5UdpProbe.probe(it.hostAddress, it.port))
            it.assertHealthy()
        }
    }

    private class FakeSocks5DnsRelay(
        private val bindAddress: InetAddress,
        private val advertiseWildcard: Boolean = false,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable?>()
        private val tcpServer = ServerSocket(0, 1, bindAddress).apply {
            soTimeout = IO_TIMEOUT_MS
        }
        private val udpRelay = DatagramSocket(0, bindAddress).apply {
            soTimeout = IO_TIMEOUT_MS
        }
        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "fake-socks5-dns-${tcpServer.localPort}",
        ) {
            try {
                serveOneProbe()
            } catch (error: Throwable) {
                if (!closed.get()) failure.compareAndSet(null, error)
            }
        }

        val hostAddress: String
            get() = bindAddress.hostAddress

        val port: Int
            get() = tcpServer.localPort

        fun assertHealthy() {
            worker.join(IO_TIMEOUT_MS.toLong())
            failure.get()?.let { throw AssertionError("Fake SOCKS5 relay failed", it) }
        }

        override fun close() {
            closed.set(true)
            tcpServer.close()
            udpRelay.close()
            worker.join(1_000L)
        }

        private fun serveOneProbe() {
            tcpServer.accept().use { control ->
                control.soTimeout = IO_TIMEOUT_MS
                val input = control.getInputStream()
                val output = control.getOutputStream()

                val greeting = readExact(input, 2)
                require(unsigned(greeting[0]) == SOCKS_VERSION)
                val methods = readExact(input, unsigned(greeting[1]))
                require(methods.any { unsigned(it) == AUTH_NONE })
                output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_NONE.toByte()))
                output.flush()

                val request = readExact(input, 4)
                require(unsigned(request[0]) == SOCKS_VERSION)
                require(unsigned(request[1]) == UDP_ASSOCIATE)
                require(unsigned(request[2]) == 0)
                consumeAddressAndPort(input, unsigned(request[3]))

                output.write(successfulAssociateReply())
                output.flush()

                val packet = DatagramPacket(ByteArray(MAX_DATAGRAM_SIZE), MAX_DATAGRAM_SIZE)
                udpRelay.receive(packet)
                val response = buildUdpDnsResponse(packet.data, packet.offset, packet.length)
                udpRelay.send(
                    DatagramPacket(response, response.size, packet.socketAddress),
                )
            }
        }

        private fun successfulAssociateReply(): ByteArray {
            val address = if (advertiseWildcard) {
                ByteArray(bindAddress.address.size)
            } else {
                bindAddress.address
            }
            val type = when (address.size) {
                4 -> ADDRESS_IPV4
                16 -> ADDRESS_IPV6
                else -> error("Unexpected bind address length: ${address.size}")
            }
            return ByteArrayOutputStream().apply {
                write(byteArrayOf(SOCKS_VERSION.toByte(), 0, 0, type.toByte()))
                write(address)
                writePort(udpRelay.localPort)
            }.toByteArray()
        }

        private fun buildUdpDnsResponse(
            datagram: ByteArray,
            offset: Int,
            length: Int,
        ): ByteArray {
            require(length >= 4)
            require(datagram[offset] == 0.toByte() && datagram[offset + 1] == 0.toByte())
            require(datagram[offset + 2] == 0.toByte())

            val addressEnd = socksAddressEnd(datagram, offset + 3, offset + length)
            val payloadOffset = addressEnd + 2
            require(payloadOffset <= offset + length)
            val dnsQuery = datagram.copyOfRange(payloadOffset, offset + length)
            val dnsResponse = buildDnsResponse(dnsQuery)

            return ByteArrayOutputStream().apply {
                write(byteArrayOf(0, 0, 0))
                write(datagram, offset + 3, payloadOffset - (offset + 3))
                write(dnsResponse)
            }.toByteArray()
        }

        private fun buildDnsResponse(query: ByteArray): ByteArray {
            require(query.size >= DNS_HEADER_SIZE)
            require(readUnsignedShort(query, 4) == 1)

            var cursor = DNS_HEADER_SIZE
            while (true) {
                require(cursor < query.size)
                val labelLength = unsigned(query[cursor++])
                require(labelLength <= 63)
                if (labelLength == 0) break
                require(cursor + labelLength <= query.size)
                cursor += labelLength
            }
            require(cursor + 4 <= query.size)
            val questionEnd = cursor + 4
            val queryType = readUnsignedShort(query, cursor)
            val answer = when (queryType) {
                DNS_TYPE_A -> byteArrayOf(203.toByte(), 0, 113, 1)
                DNS_TYPE_AAAA -> byteArrayOf(
                    0x20, 0x01, 0x0d, 0xb8.toByte(),
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
                )
                else -> error("Unexpected DNS query type: $queryType")
            }

            return ByteArrayOutputStream().apply {
                write(query, 0, 2) // Transaction ID.
                write(byteArrayOf(0x81.toByte(), 0x80.toByte())) // Response, RD, RA, NOERROR.
                write(byteArrayOf(0, 1, 0, 1, 0, 0, 0, 0))
                write(query, DNS_HEADER_SIZE, questionEnd - DNS_HEADER_SIZE)
                write(byteArrayOf(0xc0.toByte(), 0x0c)) // Name pointer to the question.
                writeShort(queryType)
                writeShort(DNS_CLASS_IN)
                write(byteArrayOf(0, 0, 0, 60))
                writeShort(answer.size)
                write(answer)
            }.toByteArray()
        }

        private fun consumeAddressAndPort(input: InputStream, type: Int) {
            val addressLength = when (type) {
                ADDRESS_IPV4 -> 4
                ADDRESS_IPV6 -> 16
                ADDRESS_DOMAIN -> {
                    val size = input.read()
                    if (size < 0) throw EOFException("Missing SOCKS domain length")
                    size
                }
                else -> error("Unexpected SOCKS address type: $type")
            }
            readExact(input, addressLength + 2)
        }

        private fun socksAddressEnd(packet: ByteArray, typeOffset: Int, limit: Int): Int {
            require(typeOffset < limit)
            return when (val type = unsigned(packet[typeOffset])) {
                ADDRESS_IPV4 -> typeOffset + 1 + 4
                ADDRESS_IPV6 -> typeOffset + 1 + 16
                ADDRESS_DOMAIN -> {
                    require(typeOffset + 1 < limit)
                    typeOffset + 2 + unsigned(packet[typeOffset + 1])
                }
                else -> error("Unexpected SOCKS UDP address type: $type")
            }.also { require(it <= limit) }
        }

        private fun ByteArrayOutputStream.writePort(port: Int) = writeShort(port)

        private fun ByteArrayOutputStream.writeShort(value: Int) {
            write((value ushr 8) and 0xff)
            write(value and 0xff)
        }
    }

    companion object {
        private const val IO_TIMEOUT_MS = 5_000
        private const val MAX_DATAGRAM_SIZE = 2_048
        private const val SOCKS_VERSION = 5
        private const val AUTH_NONE = 0
        private const val UDP_ASSOCIATE = 3
        private const val ADDRESS_IPV4 = 1
        private const val ADDRESS_DOMAIN = 3
        private const val ADDRESS_IPV6 = 4
        private const val DNS_HEADER_SIZE = 12
        private const val DNS_TYPE_A = 1
        private const val DNS_TYPE_AAAA = 28
        private const val DNS_CLASS_IN = 1

        private fun readExact(input: InputStream, size: Int): ByteArray =
            ByteArray(size).also { target ->
                var offset = 0
                while (offset < target.size) {
                    val count = input.read(target, offset, target.size - offset)
                    if (count < 0) throw EOFException("Unexpected end of SOCKS control stream")
                    offset += count
                }
            }

        private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
            (unsigned(bytes[offset]) shl 8) or unsigned(bytes[offset + 1])

        private fun unsigned(value: Byte): Int = value.toInt() and 0xff
    }
}
