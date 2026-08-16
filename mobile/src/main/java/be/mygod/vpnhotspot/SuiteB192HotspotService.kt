package be.mygod.vpnhotspot

import android.content.Intent
import be.mygod.vpnhotspot.root.RootManager
import be.mygod.vpnhotspot.root.SuiteB192Commands
import be.mygod.vpnhotspot.util.TileServiceDismissHandle
import be.mygod.vpnhotspot.widget.SmartSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class SuiteB192HotspotService : NetlinkNeighbourMonitoringService() {
    companion object {
        private const val IFACE = SuiteB192Commands.IFACE

        var dismissHandle: TileServiceDismissHandle? = null
        private fun dismissIfApplicable() = dismissHandle?.run {
            get()?.dismiss()
            dismissHandle = null
        }
    }

    class Binder(owner: SuiteB192HotspotService) : android.os.Binder() {
        private var service: SuiteB192HotspotService? = owner
        val active = owner.active.asStateFlow()

        fun stop() = service?.stopSuiteB192()

        fun detach() {
            service = null
        }
    }

    private val dispatcher = Dispatchers.Default.limitedParallelism(1, "SuiteB192HotspotService")
    override val coroutineContext = dispatcher + Job()
    private val mutex = Mutex()
    private val active = MutableStateFlow(false)
    override val interfaces = MutableStateFlow<Interfaces?>(null)
    private val binder = Binder(this)
    private var routingManager: RoutingManager? = null
    private var stopping = false

    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active.value || stopping) return START_STICKY
        ServiceNotification.startForeground(this)
        launch {
            mutex.withLock {
                if (active.value || stopping) return@withLock
                try {
                    RootManager.use { it.execute(SuiteB192Commands.Start()) }
                    val manager = RoutingManager.LocalOnly(this@SuiteB192HotspotService, IFACE)
                    routingManager = manager
                    check(manager.start()) { "Failed to start VPN Hotspot routing for $IFACE" }
                    active.value = true
                    interfaces.value = Interfaces(active = listOf(IFACE))
                    dismissIfApplicable()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e)
                    SmartSnackbar.make(e).show()
                    routingManager?.stop()
                    routingManager = null
                    try {
                        RootManager.use { it.execute(SuiteB192Commands.Stop()) }
                    } catch (cleanup: Exception) {
                        e.addSuppressed(cleanup)
                        Timber.w(cleanup)
                    }
                    active.value = false
                    interfaces.value = null
                    dismissIfApplicable()
                    ServiceNotification.stopForeground(this@SuiteB192HotspotService)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun stopSuiteB192(exit: Boolean = false) {
        if (stopping) return
        stopping = true
        launch {
            mutex.withLock {
                try {
                    val manager = routingManager
                    routingManager = null
                    manager?.stop()
                    RootManager.use { it.execute(SuiteB192Commands.Stop()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e)
                    SmartSnackbar.make(e).show()
                } finally {
                    active.value = false
                    interfaces.value = null
                    ServiceNotification.stopForeground(this@SuiteB192HotspotService)
                    if (!exit) stopping = false
                    stopSelf()
                    if (exit) cancel()
                }
            }
        }
    }

    override fun onDestroy() {
        binder.detach()
        if (active.value || routingManager != null) stopSuiteB192(true) else cancel()
        super.onDestroy()
    }
}
