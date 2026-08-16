package be.mygod.vpnhotspot.manage

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.SuiteB192HotspotService
import be.mygod.vpnhotspot.root.SuiteB192Commands
import be.mygod.vpnhotspot.util.bindServiceFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SuiteB192HotspotTileService : NetlinkNeighbourMonitoringTileService() {
    private val tile by lazy { Icon.createWithResource(application, R.drawable.ic_wifi_lock) }

    private var binder: SuiteB192HotspotService.Binder? = null
    private var serviceJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        serviceJob = scope.launch {
            try {
                bindServiceFlow(Intent(this@SuiteB192HotspotTileService, SuiteB192HotspotService::class.java))
                    .collectLatest { service ->
                        val binder = service as SuiteB192HotspotService.Binder?
                        this@SuiteB192HotspotTileService.binder = binder
                        if (binder == null) return@collectLatest
                        resolveTapPending()
                        binder.active.collect { updateTile() }
                    }
            } finally {
                binder = null
            }
        }
    }

    override fun onStopListening() {
        serviceJob?.cancel()
        serviceJob = null
        super.onStopListening()
    }

    override fun updateTile() {
        val binder = binder ?: return
        qsTile?.run {
            icon = tile
            label = getText(R.string.tethering_suiteb192)
            subtitle = getText(R.string.tethering_suiteb192_summary)
            if (binder.active.value) {
                state = Tile.STATE_ACTIVE
                subtitleDevices { it == SuiteB192Commands.IFACE }
            } else state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val binder = binder
        when {
            binder == null -> tapPending = true
            !binder.active.value -> {
                SuiteB192HotspotService.dismissHandle = dismissHandle
                startForegroundServiceCompat(Intent(this, SuiteB192HotspotService::class.java))
            }
            else -> binder.stop()
        }
    }
}
