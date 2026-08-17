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
 *
 * A running Enterprise backend is also treated as authoritative even after the user changes the saved security mode.
 * This is important for Enterprise -> platform handoff: the custom wlan2/hostapd session must be stopped before the
 * framework is allowed to start its own Wi-Fi tethering backend, otherwise both stacks race for DHCP/netd resources.
 */
object EnterpriseTetheringInterceptor {
    private val runtime by lazy { EnterpriseHostapdRuntime() }

    fun configured(): Boolean = runCatching {
        EnterpriseApConfigurationStore.load() != null && EnterpriseProfiles.load() != null
    }.getOrDefault(false)

    private suspend fun runtimePresent(): Boolean {
        if (EnterpriseHotspotService.state.value.phase != EnterpriseHotspotService.Phase.IDLE) return true
        return runCatching { runtime.isActive() }.getOrDefault(false)
    }

    /** Stop the custom backend and wait until its service state is fully idle. Errors are propagated to the caller. */
    private suspend fun stopEnterpriseStrict() {
        if (!runtimePresent()) return
        EnterpriseHotspotService.markStopping()
        app.startForegroundService(Intent(app, EnterpriseHotspotService::class.java).setAction(
            EnterpriseHotspotService.ACTION_STOP,
        ))
        val result = withTimeout(20.seconds) {
            EnterpriseHotspotService.state.first { it.phase == EnterpriseHotspotService.Phase.IDLE }
        }
        result.error?.let { throw IllegalStateException(it) }
        // The service stop path owns RoutingManager and hostapd cleanup. Verify that no root-owned Enterprise AP
        // survived a process/service race before handing Wi-Fi back to Android's platform tethering stack.
        check(!runCatching { runtime.isActive() }.getOrDefault(false)) {
            "Enterprise hotspot backend is still active after stop"
        }
    }

    suspend fun startIfConfigured(type: Int): Boolean {
        if (type != TetheringManager.TETHERING_WIFI) return false

        // The user may save WPA2-PSK/WPA3-SAE while an Enterprise AP is still running. The saved Enterprise carrier
        // is cleared immediately by the AP editor, so configuration state alone cannot decide ownership here. Tear
        // down any surviving custom backend first, then let the ordinary platform start continue.
        if (!configured()) {
            return try {
                stopEnterpriseStrict()
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SmartSnackbar.make(e).show()
                true
            }
        }

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
        if (type != TetheringManager.TETHERING_WIFI || !runtimePresent()) return false
        return try {
            stopEnterpriseStrict()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SmartSnackbar.make(e).show()
            true
        }
    }
}