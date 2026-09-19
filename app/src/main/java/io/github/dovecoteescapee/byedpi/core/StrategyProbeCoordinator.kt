package io.github.dovecoteescapee.byedpi.core

import io.github.dovecoteescapee.byedpi.data.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide gate preventing UI recreation and Quick Settings from overlapping a probe. */
object StrategyProbeCoordinator {
    data class Request(val mode: Mode, val shouldRunAfterProbe: Boolean)

    private val _active = MutableStateFlow(false)
    private val stateLock = Any()
    @Volatile private var locked = false
    private var permissionRequest: Request? = null

    val active = _active.asStateFlow()
    val isActive: Boolean
        get() = locked

    fun tryBegin(pendingPermission: Request? = null): Boolean = synchronized(stateLock) {
        if (locked) return@synchronized false
        locked = true
        permissionRequest = pendingPermission
        _active.value = true
        true
    }

    fun takePermissionRequest(): Request? = synchronized(stateLock) {
        permissionRequest.also { permissionRequest = null }
    }

    fun finish() {
        synchronized(stateLock) {
            permissionRequest = null
            locked = false
            _active.value = false
        }
    }
}
