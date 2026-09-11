package io.github.dovecoteescapee.byedpi.core

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Small ABI wrapper around the vendored MTProto-over-WebSocket core. */
interface TelegramWsProxyLibrary : Library {
    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(pointer: Pointer)
}

object TelegramWsProxy {
    private val native: TelegramWsProxyLibrary by lazy {
        Native.load("tgwsproxy", TelegramWsProxyLibrary::class.java)
    }

    fun configure(poolSize: Int, cacheDir: String, cloudflare: Boolean, domain: String) {
        native.SetPoolSize(poolSize.coerceIn(2, 16))
        native.SetCfProxyCacheDir(cacheDir)
        native.SetCfProxyConfig(if (cloudflare) 1 else 0, 1, domain)
    }

    fun start(host: String, port: Int, dcIps: String, secret: String): Int =
        native.StartProxy(host, port, dcIps, secret, 1)

    fun stop(): Int = native.StopProxy()

    fun secretWithPrefix(): String? = nativeString { native.GetSecretWithPrefix() }

    fun stats(): String? = nativeString { native.GetStats() }

    private inline fun nativeString(get: () -> Pointer?): String? {
        val pointer = get() ?: return null
        return try {
            pointer.getString(0)
        } finally {
            native.FreeString(pointer)
        }
    }
}
