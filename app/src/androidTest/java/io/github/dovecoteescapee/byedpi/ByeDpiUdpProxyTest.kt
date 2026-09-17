package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyUIPreferences
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ByeDpiUdpProxyTest {
    @Test(timeout = 20_000L)
    fun oneUdpAssociationCanReachMoreThanOneDestination() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val proxyPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val proxy = ByeDpiProxy()
        val session = proxy.prepareProxy(
            ByeDpiProxyUIPreferences(
                ip = loopback.hostAddress,
                port = proxyPort,
                desyncUdp = false,
                udpFakeCount = 0,
            ),
        )
        val proxyResult = AtomicReference<Int?>()
        val proxyThread = thread(name = "byedpi-udp-test") {
            proxyResult.set(session.run())
        }

        try {
            waitForProxy(loopback, proxyPort)
            Socket().use { control ->
                control.soTimeout = 2_000
                control.connect(InetSocketAddress(loopback, proxyPort), 2_000)
                val input = control.getInputStream()
                val output = control.getOutputStream()

                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                val greeting = ByteArray(2)
                readFully(input, greeting)
                assertArrayEquals(byteArrayOf(0x05, 0x00), greeting)

                output.write(
                    byteArrayOf(
                        0x05, 0x03, 0x00, 0x01,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00,
                    ),
                )
                output.flush()
                val reply = ByteArray(4)
                readFully(input, reply)
                assertEquals(0x05, reply[0].toInt() and 0xff)
                assertEquals(0x00, reply[1].toInt() and 0xff)
                val relay = readSocksAddress(input, reply[3], loopback)

                DatagramSocket(0, loopback).use { client ->
                    client.soTimeout = 2_000
                    assertUdpRoundTrip(client, relay, loopback, "first".toByteArray())
                    assertUdpRoundTrip(client, relay, loopback, "second".toByteArray())
                }
            }
        } finally {
            session.stop()
            proxyThread.join(3_000L)
            if (proxyThread.isAlive) {
                throw AssertionError("Native proxy did not stop")
            }
            assertEquals(0, proxyResult.get())
        }
    }

    private fun assertUdpRoundTrip(
        client: DatagramSocket,
        relay: InetSocketAddress,
        targetAddress: InetAddress,
        payload: ByteArray,
    ) {
        DatagramSocket(0, targetAddress).use { echo ->
            echo.soTimeout = 2_000
            val echoFailure = AtomicReference<Throwable?>()
            val echoThread = thread(name = "udp-echo-${echo.localPort}") {
                try {
                    val request = DatagramPacket(ByteArray(256), 256)
                    echo.receive(request)
                    echo.send(
                        DatagramPacket(
                            request.data,
                            request.offset,
                            request.length,
                            request.socketAddress,
                        ),
                    )
                } catch (error: Throwable) {
                    echoFailure.set(error)
                }
            }

            val request = socksUdpPacket(targetAddress, echo.localPort, payload)
            client.send(DatagramPacket(request, request.size, relay))
            val response = DatagramPacket(ByteArray(512), 512)
            client.receive(response)
            echoThread.join(2_500L)
            echoFailure.get()?.let { throw AssertionError("UDP echo failed", it) }
            val decoded = decodeSocksUdpPayload(response.data, response.length)
            assertArrayEquals(payload, decoded)
        }
    }

    private fun socksUdpPacket(address: InetAddress, port: Int, payload: ByteArray): ByteArray {
        val rawAddress = address.address
        require(rawAddress.size == 4)
        return ByteArray(10 + payload.size).also { packet ->
            packet[3] = 0x01
            rawAddress.copyInto(packet, 4)
            packet[8] = (port ushr 8).toByte()
            packet[9] = port.toByte()
            payload.copyInto(packet, 10)
        }
    }

    private fun decodeSocksUdpPayload(packet: ByteArray, length: Int): ByteArray {
        require(length >= 4)
        require(packet[0] == 0.toByte() && packet[1] == 0.toByte() && packet[2] == 0.toByte())
        val addressLength = when (packet[3].toInt() and 0xff) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> 1 + (packet[4].toInt() and 0xff)
            else -> error("Unexpected SOCKS address type")
        }
        val payloadOffset = 4 + addressLength + 2
        require(length >= payloadOffset)
        return packet.copyOfRange(payloadOffset, length)
    }

    private fun readSocksAddress(
        input: InputStream,
        type: Byte,
        fallbackAddress: InetAddress,
    ): InetSocketAddress {
        val address = when (type.toInt() and 0xff) {
            0x01 -> InetAddress.getByAddress(ByteArray(4).also { readFully(input, it) })
            0x04 -> InetAddress.getByAddress(ByteArray(16).also { readFully(input, it) })
            0x03 -> {
                val size = input.read()
                require(size > 0)
                val host = ByteArray(size).also { readFully(input, it) }.toString(Charsets.US_ASCII)
                InetAddress.getByName(host)
            }
            else -> error("Unexpected SOCKS address type")
        }
        val portBytes = ByteArray(2)
        readFully(input, portBytes)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or
            (portBytes[1].toInt() and 0xff)
        val usableAddress = if (address.isAnyLocalAddress) fallbackAddress else address
        return InetSocketAddress(usableAddress, port)
    }

    private fun waitForProxy(address: InetAddress, port: Int) {
        val deadline = System.nanoTime() + 3_000_000_000L
        var lastError: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress(address, port), 150) }
                return
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(25L)
            }
        }
        throw AssertionError("Proxy did not start", lastError)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw SocketTimeoutException("Unexpected end of stream")
            offset += count
        }
    }
}
