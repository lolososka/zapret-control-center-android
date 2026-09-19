package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyUIPreferences
import io.github.dovecoteescapee.byedpi.core.Socks5Health
import io.github.dovecoteescapee.byedpi.core.StrategyProbeProxyRunner
import io.github.dovecoteescapee.byedpi.services.appStatus
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyProbeProxyRunnerTest {
    @Test(timeout = 20_000L)
    fun temporaryProxyIsReadyWithoutChangingAppStatusAndReleasesPort() = runBlocking {
        val loopbackHost = "127.0.0.1"
        val loopback = InetAddress.getByName(loopbackHost)
        val proxyPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val initialAppStatus = appStatus
        val runner = StrategyProbeProxyRunner()

        try {
            assertTrue(
                runner.start(
                    ByeDpiProxyUIPreferences(
                        ip = loopbackHost,
                        port = proxyPort,
                        desyncUdp = false,
                        udpFakeCount = 0,
                    ),
                ),
            )
            assertTrue(Socks5Health.awaitReady(loopbackHost, proxyPort, 1_000L))
            assertEquals(initialAppStatus, appStatus)

            runner.stop()
            assertEquals(initialAppStatus, appStatus)

            ServerSocket(proxyPort, 1, loopback).use { rebound ->
                assertEquals(proxyPort, rebound.localPort)
            }
        } finally {
            runner.shutdown()
        }
    }
}
