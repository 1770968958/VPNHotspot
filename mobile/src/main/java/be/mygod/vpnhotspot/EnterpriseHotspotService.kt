package be.mygod.vpnhotspot

import android.content.Intent
import be.mygod.vpnhotspot.enterprise.EnterpriseApConfigurationStore
import be.mygod.vpnhotspot.enterprise.EnterpriseApRuntime
import be.mygod.vpnhotspot.enterprise.EnterpriseHostapdRuntime
import be.mygod.vpnhotspot.enterprise.EnterpriseProfiles
import be.mygod.vpnhotspot.enterprise.toEnterpriseApSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Foreground owner for the custom WPA2/WPA3-Enterprise AP and its VPN Hotspot routing session. */
class EnterpriseHotspotService : NetlinkNeighbourMonitoringService() {
    enum class Phase { IDLE, STARTING, ACTIVE, STOPPING }
    data class State(val phase: Phase = Phase.IDLE, val error: String? = null)

    companion object {
        const val ACTION_STOP = "be.mygod.vpnhotspot.enterprise.STOP"
        val state = MutableStateFlow(State())

        fun markStarting() {
            state.value = State(Phase.STARTING)
        }

        fun markStopping() {
            state.value = State(Phase.STOPPING)
        }
    }

    private val dispatcher = Dispatchers.Default.limitedParallelism(1, "EnterpriseHotspotService")
    override val coroutineContext = dispatcher + Job()
    override val interfaces = MutableStateFlow<Interfaces?>(null)
    private val mutex = Mutex()
    private val runtime = EnterpriseHostapdRuntime()
    private var session: EnterpriseApRuntime.Session? = null
    private var routingManager: RoutingManager? = null
    private var starting = false
    private var stopping = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceNotification.startForeground(this)
        if (intent?.action == ACTION_STOP) {
            markStopping()
            stopEnterprise()
            return START_NOT_STICKY
        }
        if (starting || routingManager != null || session != null || stopping) return START_STICKY
        starting = true
        markStarting()
        launch {
            mutex.withLock {
                if (stopping) {
                    starting = false
                    return@withLock
                }
                try {
                    val carrier = EnterpriseApConfigurationStore.load()
                        ?: error("No Enterprise AP configuration has been saved")
                    val profile = EnterpriseProfiles.load()
                        ?: error("No Enterprise authentication profile has been saved")
                    val request = EnterpriseApRuntime.Request(carrier.toEnterpriseApSpec(), profile)
                    val started = runtime.start(request)
                    session = started
                    val manager = RoutingManager.LocalOnly(this@EnterpriseHotspotService, started.interfaceName)
                    routingManager = manager
                    check(manager.start()) { "Failed to start VPN Hotspot routing for ${started.interfaceName}" }
                    interfaces.value = Interfaces(active = listOf(started.interfaceName))
                    state.value = State(Phase.ACTIVE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e)
                    cleanupAfterFailure(e)
                    state.value = State(Phase.IDLE, e.message ?: e.toString())
                    interfaces.value = null
                    ServiceNotification.stopForeground(this@EnterpriseHotspotService)
                    stopSelf()
                } finally {
                    starting = false
                }
            }
        }
        return START_STICKY
    }

    private suspend fun cleanupAfterFailure(original: Exception) {
        val manager = routingManager
        routingManager = null
        try {
            manager?.stop()
        } catch (cleanup: Exception) {
            original.addSuppressed(cleanup)
            Timber.w(cleanup)
        }
        val started = session
        session = null
        try {
            if (started != null) runtime.stop(started) else runtime.stopOwnedRuntime()
        } catch (cleanup: Exception) {
            original.addSuppressed(cleanup)
            Timber.w(cleanup)
        }
    }

    private fun stopEnterprise(exit: Boolean = false) {
        if (stopping) return
        stopping = true
        markStopping()
        launch {
            mutex.withLock {
                var failure: Exception? = null
                try {
                    val manager = routingManager
                    routingManager = null
                    manager?.stop()
                    val started = session
                    session = null
                    if (started != null) runtime.stop(started) else runtime.stopOwnedRuntime()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                    Timber.w(e)
                } finally {
                    starting = false
                    interfaces.value = null
                    state.value = State(Phase.IDLE, failure?.let { it.message ?: it.toString() })
                    ServiceNotification.stopForeground(this@EnterpriseHotspotService)
                    stopSelf()
                    if (!exit) stopping = false else cancel()
                }
            }
        }
    }

    override fun onDestroy() {
        if (starting || routingManager != null || session != null || state.value.phase == Phase.ACTIVE) {
            stopEnterprise(true)
        } else {
            state.value = State()
            ServiceNotification.stopForeground(this)
            cancel()
        }
        super.onDestroy()
    }
}
