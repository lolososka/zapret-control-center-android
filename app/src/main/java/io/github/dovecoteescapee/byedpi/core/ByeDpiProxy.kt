package io.github.dovecoteescapee.byedpi.core

class ByeDpiProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }

    // A session is a generation handle, never a raw file descriptor. Capturing it
    // before launching the worker makes an immediate Stop safe even if the worker
    // has not started, or the OS has already reused a closed listener descriptor.
    class Session internal constructor(private val owner: ByeDpiProxy, private val handle: Int) {
        fun run(): Int = owner.jniStartProxy(handle)
        fun stop(): Int = owner.jniStopProxy(handle)
    }

    fun prepareProxy(preferences: ByeDpiProxyPreferences): Session {
        val handle = createSocketFromPreferences(preferences)
        check(handle >= 0) { "Failed to create proxy socket" }
        return Session(this, handle)
    }

    private fun createSocketFromPreferences(preferences: ByeDpiProxyPreferences) =
        when (preferences) {
            is ByeDpiProxyCmdPreferences -> jniCreateSocketWithCommandLine(preferences.args)

            is ByeDpiProxyUIPreferences -> jniCreateSocket(
                ip = preferences.ip,
                port = preferences.port,
                maxConnections = preferences.maxConnections,
                bufferSize = preferences.bufferSize,
                defaultTtl = preferences.defaultTtl,
                customTtl = preferences.customTtl,
                noDomain = preferences.noDomain,
                desyncHttp = preferences.desyncHttp,
                desyncHttps = preferences.desyncHttps,
                desyncUdp = preferences.desyncUdp,
                desyncMethod = preferences.desyncMethod.ordinal,
                splitPosition = preferences.splitPosition,
                splitAtHost = preferences.splitAtHost,
                fakeTtl = preferences.fakeTtl,
                fakeSni = preferences.fakeSni,
                oobChar = preferences.oobChar,
                hostMixedCase = preferences.hostMixedCase,
                domainMixedCase = preferences.domainMixedCase,
                hostRemoveSpaces = preferences.hostRemoveSpaces,
                tlsRecordSplit = preferences.tlsRecordSplit,
                tlsRecordSplitPosition = preferences.tlsRecordSplitPosition,
                tlsRecordSplitAtSni = preferences.tlsRecordSplitAtSni,
                hostsMode = preferences.hostsMode.ordinal,
                hosts = preferences.hosts,
                tcpFastOpen = preferences.tcpFastOpen,
                udpFakeCount = preferences.udpFakeCount,
                dropSack = preferences.dropSack,
                fakeOffset = preferences.fakeOffset,
            )
        }

    private external fun jniCreateSocketWithCommandLine(args: Array<String>): Int

    private external fun jniCreateSocket(
        ip: String,
        port: Int,
        maxConnections: Int,
        bufferSize: Int,
        defaultTtl: Int,
        customTtl: Boolean,
        noDomain: Boolean,
        desyncHttp: Boolean,
        desyncHttps: Boolean,
        desyncUdp: Boolean,
        desyncMethod: Int,
        splitPosition: Int,
        splitAtHost: Boolean,
        fakeTtl: Int,
        fakeSni: String,
        oobChar: Byte,
        hostMixedCase: Boolean,
        domainMixedCase: Boolean,
        hostRemoveSpaces: Boolean,
        tlsRecordSplit: Boolean,
        tlsRecordSplitPosition: Int,
        tlsRecordSplitAtSni: Boolean,
        hostsMode: Int,
        hosts: String?,
        tcpFastOpen: Boolean,
        udpFakeCount: Int,
        dropSack: Boolean,
        fakeOffset: Int,
    ): Int

    private external fun jniStartProxy(handle: Int): Int

    private external fun jniStopProxy(handle: Int): Int
}
