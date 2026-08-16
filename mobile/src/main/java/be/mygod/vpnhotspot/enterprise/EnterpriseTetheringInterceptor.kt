package be.mygod.vpnhotspot.enterprise

import android.content.Intent
import android.net.TetheringManager
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.EnterpriseHotspotService
import be.mygod.vpnhotspot.widget.SmartSnackbar
import kotlinx.coroutines.CancellationException
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
        return try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Enterprise failures are already actionable inside VPN Hotspot. Handle them here so the generic tethering
            // error path does not redirect the user to Android's system tethering settings, which cannot configure this AP.
            SmartSnackbar.make(e).show()
            true
        }
    }

    suspend fun stopIfActive(type: Int): Boolean {
        if (type != TetheringManager.TETHERING_WIFI) return false
        val serviceActive = EnterpriseHotspotService.state.value.phase != EnterpriseHotspotService.Phase.IDLE
        if (!serviceActive && !runCatching { runtime.isActive() }.getOrDefault(false)) return false
        return try {
            EnterpriseHotspotService.markStopping()
            app.startForegroundService(Intent(app, EnterpriseHotspotService::class.java).setAction(
                EnterpriseHotspotService.ACTION_STOP,
            ))
            val result = withTimeout(20.seconds) {
                EnterpriseHotspotService.state.first { it.phase == EnterpriseHotspotService.Phase.IDLE }
            }
            result.error?.let { throw IllegalStateException(it) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SmartSnackbar.make(e).show()
            true
        }
    }
}
