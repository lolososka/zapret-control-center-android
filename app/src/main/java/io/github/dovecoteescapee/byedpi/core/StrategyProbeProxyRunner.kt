package io.github.dovecoteescapee.byedpi.core

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Owns a short-lived native proxy used while probing strategy candidates.
 *
 * Unlike [io.github.dovecoteescapee.byedpi.services.ByeDpiProxyService], this
 * runner deliberately has no Android service lifecycle, notification, status,
 * or broadcast side effects.
 */
class StrategyProbeProxyRunner(
    private val readinessTimeoutMs: Long = 3_000L,
) : Closeable {
    private data class RunningProxy(
        val session: ByeDpiProxy.Session,
        val worker: Job,
    )

    private val ownerJob = SupervisorJob()
    private val scope = CoroutineScope(ownerJob + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var runningProxy: RunningProxy? = null

    /**
     * Replaces the previous probe proxy, if any, and waits for a UI-configured
     * SOCKS5 listener to answer its greeting before reporting success.
     */
    suspend fun start(preferences: ByeDpiProxyPreferences): Boolean =
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                stopCurrentLocked()
                if (closed.get()) return@withLock false

                val session = try {
                    ByeDpiProxy().prepareProxy(preferences)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@withLock false
                } catch (_: LinkageError) {
                    return@withLock false
                }

                val worker = scope.launch {
                    try {
                        session.run()
                    } catch (_: Exception) {
                        // Readiness (or worker completion) is reported by start().
                    } catch (_: LinkageError) {
                        // Readiness (or worker completion) is reported by start().
                    }
                }
                runningProxy = RunningProxy(session, worker)

                try {
                    val ready = when (preferences) {
                        is ByeDpiProxyUIPreferences -> Socks5Health.awaitReady(
                            preferences.ip,
                            preferences.port,
                            readinessTimeoutMs,
                        )

                        is ByeDpiProxyCmdPreferences -> {
                            // Command-line preferences may choose their own endpoint,
                            // so there is no reliable address to health-check here.
                            yield()
                            worker.isActive
                        }
                    }

                    if (!ready || !worker.isActive || closed.get()) {
                        stopCurrentLocked()
                        false
                    } else {
                        true
                    }
                } catch (error: CancellationException) {
                    withContext(NonCancellable) { stopCurrentLocked() }
                    throw error
                } catch (_: Exception) {
                    withContext(NonCancellable) { stopCurrentLocked() }
                    false
                } catch (_: LinkageError) {
                    withContext(NonCancellable) { stopCurrentLocked() }
                    false
                }
            }
        }

    /** Stops the active probe, waits for its native loop, and is safe to repeat. */
    suspend fun stop() {
        withContext(NonCancellable) {
            lifecycleMutex.withLock { stopCurrentLocked() }
        }
    }

    /** Alias useful for owners that model teardown as cancellation. */
    fun cancel() = close()

    /** Coroutine-friendly final teardown; callers on Main must prefer this to [close]. */
    suspend fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        try {
            stop()
        } finally {
            scope.cancel()
        }
    }

    override fun close() {
        runBlocking(Dispatchers.IO) { shutdown() }
    }

    private suspend fun stopCurrentLocked() {
        val current = runningProxy ?: return
        runningProxy = null
        try {
            current.session.stop()
        } finally {
            current.worker.join()
        }
    }
}
