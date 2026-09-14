package io.github.dovecoteescapee.byedpi.core

/** JNI wrapper around the vendored MTProto-over-WebSocket core. */
object TelegramWsProxy {
    init {
        System.loadLibrary("tgwsproxy")
        try {
            System.loadLibrary("tgwsbridge")
        } catch (primary: UnsatisfiedLinkError) {
            try {
                System.loadLibrary("tgwsbridge_compat")
            } catch (fallback: UnsatisfiedLinkError) {
                fallback.addSuppressed(primary)
                throw fallback
            }
        }
    }

    fun configure(poolSize: Int, cacheDir: String, cloudflare: Boolean, domain: String) {
        nativeConfigure(poolSize.coerceIn(2, 16), cacheDir, cloudflare, domain)
    }

    fun start(host: String, port: Int, dcIps: String, secret: String): Int =
        nativeStart(host, port, dcIps, secret)

    fun stop(): Int = nativeStop()

    private external fun nativeConfigure(poolSize: Int, cacheDir: String, cloudflare: Boolean, domain: String)
    private external fun nativeStart(host: String, port: Int, dcIps: String, secret: String): Int
    private external fun nativeStop(): Int
}
