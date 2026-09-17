package io.github.dovecoteescapee.byedpi.core

import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/** Measures a real DNS lookup performed through a SOCKS5 UDP association. */
object Socks5UdpProbe {
    private const val DEFAULT_DNS_HOST = "1.1.1.1"
    private const val DEFAULT_DNS_PORT = 53
    private const val DEFAULT_TIMEOUT_MS = 3_500
    private const val QUERY_NAME = "example.com"
    private const val MAX_UDP_PACKET_SIZE = 65_535

    /**
     * Returns the end-to-end latency in milliseconds, or `null` when the proxy does not
     * complete a valid SOCKS5 UDP DNS exchange within [timeoutMs]. This is a blocking call
     * and must be invoked from an I/O dispatcher when used by coroutine code.
     */
    fun probe(
        proxyHost: String,
        proxyPort: Int,
        dnsHost: String = DEFAULT_DNS_HOST,
        dnsPort: Int = DEFAULT_DNS_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Long? {
        if (proxyHost.isBlank() || dnsHost.isBlank() ||
            proxyPort !in 1..65_535 || dnsPort !in 1..65_535 || timeoutMs <= 0
        ) return null

        val startedAt = System.nanoTime()
        val deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())

        return try {
            Socket().use { control ->
                control.tcpNoDelay = true
                control.connect(
                    InetSocketAddress(normalizeProxyHost(proxyHost), proxyPort),
                    remainingTimeoutMs(deadline),
                )

                DatagramSocket(null).use { udp ->
                    // Matching the control connection's local address keeps the UDP socket in
                    // the same address family and gives the proxy an accurate client endpoint.
                    udp.bind(InetSocketAddress(control.localAddress, 0))

                    negotiateNoAuthentication(control, deadline)
                    val relay = requestUdpAssociation(control, udp.localPort, deadline)
                    udp.connect(relay)

                    val target = encodeSocksAddress(dnsHost)
                    val transactionId = ThreadLocalRandom.current().nextInt(0x1_0000)
                    val dnsQuery = buildDnsQuery(transactionId)
                    val request = buildUdpRequest(target, dnsPort, dnsQuery)

                    udp.send(DatagramPacket(request, request.size, relay))
                    receiveValidResponse(
                        udp = udp,
                        target = target,
                        targetPort = dnsPort,
                        transactionId = transactionId,
                        deadline = deadline,
                    )

                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun negotiateNoAuthentication(control: Socket, deadline: Long) {
        control.getOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00))
            flush()
        }

        val reply = ByteArray(2)
        readFully(control, reply, deadline)
        if (reply[0] != 0x05.toByte() || reply[1] != 0x00.toByte()) {
            throw ProtocolException("SOCKS5 proxy rejected unauthenticated access")
        }
    }

    private fun requestUdpAssociation(
        control: Socket,
        udpPort: Int,
        deadline: Long,
    ): InetSocketAddress {
        val clientAddress = encodeSocksAddress(control.localAddress)
        val request = ByteArray(3 + clientAddress.wire.size + 2)
        request[0] = 0x05
        request[1] = 0x03 // UDP ASSOCIATE
        request[2] = 0x00
        clientAddress.wire.copyInto(request, destinationOffset = 3)
        writeUnsignedShort(request, request.size - 2, udpPort)

        control.getOutputStream().apply {
            write(request)
            flush()
        }

        val header = ByteArray(4)
        readFully(control, header, deadline)
        if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte() ||
            header[2] != 0x00.toByte()
        ) {
            throw ProtocolException("SOCKS5 UDP association failed")
        }

        val boundAddress = readSocksAddress(control, header[3].toInt() and 0xff, deadline)
        val portBytes = ByteArray(2)
        readFully(control, portBytes, deadline)
        val boundPort = readUnsignedShort(portBytes, 0)
        if (boundPort == 0) throw ProtocolException("SOCKS5 returned an invalid UDP port")

        val relayAddress = if (boundAddress.isAnyLocalAddress) {
            control.inetAddress
        } else {
            boundAddress
        }
        return InetSocketAddress(relayAddress, boundPort)
    }

    private fun readSocksAddress(control: Socket, type: Int, deadline: Long): InetAddress =
        when (type) {
            0x01 -> {
                val bytes = ByteArray(4)
                readFully(control, bytes, deadline)
                InetAddress.getByAddress(bytes)
            }

            0x04 -> {
                val bytes = ByteArray(16)
                readFully(control, bytes, deadline)
                InetAddress.getByAddress(bytes)
            }

            0x03 -> {
                val lengthBytes = ByteArray(1)
                readFully(control, lengthBytes, deadline)
                val length = lengthBytes[0].toInt() and 0xff
                if (length == 0) throw ProtocolException("Empty SOCKS5 bind hostname")
                val bytes = ByteArray(length)
                readFully(control, bytes, deadline)
                InetAddress.getByName(String(bytes, StandardCharsets.US_ASCII))
            }

            else -> throw ProtocolException("Unsupported SOCKS5 address type")
        }

    private fun buildUdpRequest(target: SocksAddress, port: Int, payload: ByteArray): ByteArray {
        val request = ByteArray(3 + target.wire.size + 2 + payload.size)
        // RSV and FRAG are already zero. Fragmented SOCKS5 UDP datagrams are deliberately
        // avoided because support for them is optional in RFC 1928.
        target.wire.copyInto(request, destinationOffset = 3)
        val portOffset = 3 + target.wire.size
        writeUnsignedShort(request, portOffset, port)
        payload.copyInto(request, destinationOffset = portOffset + 2)
        return request
    }

    private fun receiveValidResponse(
        udp: DatagramSocket,
        target: SocksAddress,
        targetPort: Int,
        transactionId: Int,
        deadline: Long,
    ) {
        val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        while (true) {
            udp.soTimeout = remainingTimeoutMs(deadline)
            packet.length = buffer.size
            udp.receive(packet)

            val payloadOffset = parseUdpResponse(
                packet = buffer,
                packetLength = packet.length,
                expectedSource = target,
                expectedPort = targetPort,
            ) ?: continue
            if (isValidDnsAResponse(buffer, payloadOffset, packet.length, transactionId)) return
        }
    }

    private fun parseUdpResponse(
        packet: ByteArray,
        packetLength: Int,
        expectedSource: SocksAddress,
        expectedPort: Int,
    ): Int? {
        if (packetLength < 7 || packet[0] != 0x00.toByte() ||
            packet[1] != 0x00.toByte() || packet[2] != 0x00.toByte()
        ) return null

        val source = parsePacketAddress(packet, offset = 3, limit = packetLength) ?: return null
        if (source.nextOffset + 2 > packetLength) return null
        val sourcePort = readUnsignedShort(packet, source.nextOffset)
        if (sourcePort != expectedPort || !expectedSource.matches(source)) return null
        return source.nextOffset + 2
    }

    private fun buildDnsQuery(transactionId: Int): ByteArray {
        val labels = QUERY_NAME.split('.').map { it.toByteArray(StandardCharsets.US_ASCII) }
        val questionLength = labels.sumOf { it.size + 1 } + 1 + 4
        val query = ByteArray(12 + questionLength)
        writeUnsignedShort(query, 0, transactionId)
        query[2] = 0x01 // Recursion desired.
        writeUnsignedShort(query, 4, 1) // QDCOUNT

        var offset = 12
        for (label in labels) {
            query[offset++] = label.size.toByte()
            label.copyInto(query, destinationOffset = offset)
            offset += label.size
        }
        query[offset++] = 0x00
        writeUnsignedShort(query, offset, 1) // QTYPE A
        writeUnsignedShort(query, offset + 2, 1) // QCLASS IN
        return query
    }

    private fun isValidDnsAResponse(
        packet: ByteArray,
        payloadOffset: Int,
        packetLength: Int,
        transactionId: Int,
    ): Boolean {
        if (packetLength - payloadOffset < 12) return false
        if (readUnsignedShort(packet, payloadOffset) != transactionId) return false

        val flags = readUnsignedShort(packet, payloadOffset + 2)
        val isResponse = flags and 0x8000 != 0
        val opcode = flags ushr 11 and 0x0f
        val truncated = flags and 0x0200 != 0
        val responseCode = flags and 0x0f
        if (!isResponse || opcode != 0 || truncated || responseCode != 0) return false

        val questionCount = readUnsignedShort(packet, payloadOffset + 4)
        val answerCount = readUnsignedShort(packet, payloadOffset + 6)
        if (questionCount != 1 || answerCount == 0) return false

        val question = readDnsName(packet, payloadOffset + 12, payloadOffset, packetLength)
            ?: return false
        if (!question.name.equals(QUERY_NAME, ignoreCase = true) ||
            question.nextOffset + 4 > packetLength ||
            readUnsignedShort(packet, question.nextOffset) != 1 ||
            readUnsignedShort(packet, question.nextOffset + 2) != 1
        ) return false

        var offset = question.nextOffset + 4
        repeat(answerCount) {
            val answerName = readDnsName(packet, offset, payloadOffset, packetLength)
                ?: return false
            offset = answerName.nextOffset
            if (offset + 10 > packetLength) return false

            val type = readUnsignedShort(packet, offset)
            val dnsClass = readUnsignedShort(packet, offset + 2)
            val dataLength = readUnsignedShort(packet, offset + 8)
            offset += 10
            if (offset + dataLength > packetLength) return false
            if (type == 1 && dnsClass == 1 && dataLength == 4 &&
                answerName.name.equals(QUERY_NAME, ignoreCase = true)
            ) return true
            offset += dataLength
        }
        return false
    }

    private fun readDnsName(
        packet: ByteArray,
        startOffset: Int,
        messageOffset: Int,
        packetLength: Int,
    ): DnsName? {
        var offset = startOffset
        var nextOffset = -1
        var jumps = 0
        val labels = ArrayList<String>()

        while (offset < packetLength) {
            val length = packet[offset].toInt() and 0xff
            when {
                length == 0 -> {
                    if (nextOffset < 0) nextOffset = offset + 1
                    return DnsName(labels.joinToString("."), nextOffset)
                }

                length and 0xc0 == 0xc0 -> {
                    if (offset + 1 >= packetLength || ++jumps > 16) return null
                    val pointer = ((length and 0x3f) shl 8) or
                        (packet[offset + 1].toInt() and 0xff)
                    val pointedOffset = messageOffset + pointer
                    if (pointedOffset < messageOffset || pointedOffset >= packetLength) return null
                    if (nextOffset < 0) nextOffset = offset + 2
                    offset = pointedOffset
                }

                length and 0xc0 != 0 || length > 63 || offset + 1 + length > packetLength ->
                    return null

                else -> {
                    labels += String(packet, offset + 1, length, StandardCharsets.US_ASCII)
                    offset += length + 1
                }
            }
        }
        return null
    }

    private fun encodeSocksAddress(address: InetAddress): SocksAddress {
        val type = when (address) {
            is Inet4Address -> 0x01
            is Inet6Address -> 0x04
            else -> throw ProtocolException("Unsupported IP address family")
        }
        return SocksAddress(
            wire = byteArrayOf(type.toByte()) + address.address,
            literal = address,
            domain = null,
        )
    }

    private fun encodeSocksAddress(host: String): SocksAddress {
        val normalized = normalizeHost(host)
        parseIpLiteral(normalized)?.let { return encodeSocksAddress(it) }

        val domain = IDN.toASCII(normalized).lowercase()
        val bytes = domain.toByteArray(StandardCharsets.US_ASCII)
        if (bytes.isEmpty() || bytes.size > 255) {
            throw ProtocolException("Invalid SOCKS5 target hostname")
        }
        return SocksAddress(
            wire = byteArrayOf(0x03, bytes.size.toByte()) + bytes,
            literal = null,
            domain = domain,
        )
    }

    private fun parseIpLiteral(host: String): InetAddress? {
        if (':' in host) {
            return InetAddress.getByName(host).takeIf { it is Inet6Address }
        }

        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    private fun parsePacketAddress(packet: ByteArray, offset: Int, limit: Int): ParsedAddress? {
        if (offset >= limit) return null
        return when (packet[offset].toInt() and 0xff) {
            0x01 -> {
                if (offset + 5 > limit) return null
                ParsedAddress(
                    literal = InetAddress.getByAddress(packet.copyOfRange(offset + 1, offset + 5)),
                    domain = null,
                    nextOffset = offset + 5,
                )
            }

            0x04 -> {
                if (offset + 17 > limit) return null
                ParsedAddress(
                    literal = InetAddress.getByAddress(packet.copyOfRange(offset + 1, offset + 17)),
                    domain = null,
                    nextOffset = offset + 17,
                )
            }

            0x03 -> {
                if (offset + 2 > limit) return null
                val length = packet[offset + 1].toInt() and 0xff
                if (length == 0 || offset + 2 + length > limit) return null
                ParsedAddress(
                    literal = null,
                    domain = String(packet, offset + 2, length, StandardCharsets.US_ASCII),
                    nextOffset = offset + 2 + length,
                )
            }

            else -> null
        }
    }

    private fun readFully(control: Socket, buffer: ByteArray, deadline: Long) {
        val input: InputStream = control.getInputStream()
        var offset = 0
        while (offset < buffer.size) {
            control.soTimeout = remainingTimeoutMs(deadline)
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw ProtocolException("Unexpected end of SOCKS5 response")
            offset += read
        }
    }

    private fun remainingTimeoutMs(deadline: Long): Int {
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0) throw SocketTimeoutException("SOCKS5 UDP probe timed out")
        val roundedUpMillis = (remainingNanos + 999_999L) / 1_000_000L
        return roundedUpMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun normalizeHost(host: String): String {
        val trimmed = host.trim()
        return if (trimmed.length >= 2 && trimmed.first() == '[' && trimmed.last() == ']') {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun normalizeProxyHost(host: String): String = when (val value = normalizeHost(host)) {
        "0.0.0.0" -> "127.0.0.1"
        "::" -> "::1"
        else -> value
    }

    private fun writeUnsignedShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 8).toByte()
        buffer[offset + 1] = value.toByte()
    }

    private fun readUnsignedShort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xff) shl 8) or
            (buffer[offset + 1].toInt() and 0xff)

    private data class SocksAddress(
        val wire: ByteArray,
        val literal: InetAddress?,
        val domain: String?,
    ) {
        fun matches(other: ParsedAddress): Boolean = when {
            literal != null -> literal == other.literal
            other.domain != null -> domain.equals(other.domain, ignoreCase = true)
            else -> other.literal != null
        }
    }

    private data class ParsedAddress(
        val literal: InetAddress?,
        val domain: String?,
        val nextOffset: Int,
    )

    private data class DnsName(val name: String, val nextOffset: Int)
}
