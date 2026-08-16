package be.mygod.vpnhotspot.enterprise

import android.content.Intent
import android.net.TetheringManager
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.EnterpriseHotspotService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Narrow hook used by TetheringManagerCompat. Ordinary tethering is untouched unless the saved system AP security
 * mode is Enterprise. This keeps upstream call sites and Quick Settings behavior on the normal tethering API path.
 */
object EnterpriseTetheringInterceptor {
    private val runtime by lazy { EnterpriseHostapdRuntime() }

    fun configured(): Boolean = runCatching {
        EnterpriseApConfigurationStore.load() != null && EnterpriseProfiles.load() != null
    }.getOrDefault(false)

    suspend fun startIfConfigured(type: Int): Boolean {
        if (type != TetheringManager.TETHERING_WIFI || !configured()) return false
        // Validate synchronously so damaged saved state is surfaced by the same start call that the UI already uses.
        val carrier = EnterpriseApConfigurationStore.load() ?: return false
        val profile = EnterpriseProfiles.load() ?: return false
        carrier.toEnterpriseApSpec()
        profile.validate()

        if (EnterpriseHotspotService.state.value.phase == EnterpriseHotspotService.Phase.ACTIVE) return true
        // Always (re)enter the service. Its hostapd start is ownership-aware and idempotent, so this also repairs
        // routing after an app-process/service restart while a root-owned hostapd is still alive.
        EnterpriseHotspotService.markStarting()
        app.startForegroundService(Intent(app, EnterpriseHotspotService::class.java))
        val result = withTimeout(20.seconds) {
            EnterpriseHotspotService.state.first {
                it.phase == EnterpriseHotspotService.Phase.ACTIVE ||
                        it.phase == EnterpriseHotspotService.Phase.IDLE && it.error != null
            }
        }
        if (result.phase == EnterpriseHotspotService.Phase.ACTIVE) return true
        throw IllegalStateException(result.error ?: "Enterprise hotspot failed to start")
    }

    suspend fun stopIfActive(type: Int): Boolean {
        if (type != TetheringManager.TETHERING_WIFI) return false
        val serviceActive = EnterpriseHotspotService.state.value.phase != EnterpriseHotspotService.Phase.IDLE
        if (!serviceActive && !runCatching { runtime.isActive() }.getOrDefault(false)) return false
        EnterpriseHotspotService.markStopping()
        app.startForegroundService(Intent(app, EnterpriseHotspotService::class.java).setAction(
            EnterpriseHotspotService.ACTION_STOP,
        ))
        val result = withTimeout(20.seconds) {
            EnterpriseHotspotService.state.first { it.phase == EnterpriseHotspotService.Phase.IDLE }
        }
        result.error?.let { throw IllegalStateException(it) }
        return true
    }
}
