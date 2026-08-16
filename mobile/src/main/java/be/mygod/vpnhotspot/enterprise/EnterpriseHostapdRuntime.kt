package be.mygod.vpnhotspot.enterprise

import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.root.EnterpriseApCommands
import be.mygod.vpnhotspot.root.RootManager
import java.io.File

/**
 * Mi 9 phase-1 Enterprise backend.
 *
 * The hostapd executable is still the already-proven external build. All generated configuration, users and PKI
 * are app-owned. Replacing this class with an APK-packaged native backend later does not affect UI/profile code.
 */
class EnterpriseHostapdRuntime(
    private val rootDirectory: File = File(app.deviceStorage.filesDir, "enterprise-ap"),
) : EnterpriseApRuntime {
    companion object {
        const val BACKEND_ID = "hostapd-external-v1"
        const val IFACE = EnterpriseApCommands.IFACE
        private const val DEFAULT_BSSID = "6a:66:77:88:99:a8"
    }

    private val workspace = EnterpriseRuntimeWorkspace(rootDirectory)
    private val runtimeDirectory get() = File(rootDirectory, "runtime")
    private val configFile get() = File(runtimeDirectory, "hostapd.conf")
    private val pidFile get() = File(runtimeDirectory, "hostapd.pid")
    private val ownerFile get() = File(runtimeDirectory, "hostapd.owner")
    private val bssidFile get() = File(runtimeDirectory, "bssid")

    override suspend fun capability() = EnterpriseApRuntime.Capability(
        wpa2Enterprise = true,
        wpa3Enterprise = true,
        detail = "external hostapd phase-1 backend",
    )

    override suspend fun start(request: EnterpriseApRuntime.Request): EnterpriseApRuntime.Session {
        require(request.profile.mode != EnterpriseSecurityMode.WPA3_ENTERPRISE_192) {
            "WPA3-Enterprise 192-bit is not enabled in the password-user backend"
        }
        val resolvedAp = request.ap.copy(
            channel = when {
                request.ap.channel != 0 -> request.ap.channel
                request.ap.band == EnterpriseApSpec.Band.BAND_5GHZ -> 149
                else -> 6
            },
            bssid = request.ap.bssid ?: DEFAULT_BSSID,
        )
        val prepared = workspace.prepare(request.copy(ap = resolvedAp), IFACE)
        val bssid = checkNotNull(resolvedAp.bssid).lowercase()
        writeAtomic(bssidFile, "$bssid\n")
        RootManager.use { root ->
            root.execute(EnterpriseApCommands.Start(
                configPath = prepared.hostapdConfig.absolutePath,
                pidPath = prepared.pidFile.absolutePath,
                ownerPath = ownerFile.absolutePath,
                logPath = prepared.logFile.absolutePath,
                bssid = bssid,
            ))
        }
        return EnterpriseApRuntime.Session(IFACE, BACKEND_ID)
    }

    override suspend fun stop(session: EnterpriseApRuntime.Session) {
        require(session.backendId == BACKEND_ID) { "Unexpected Enterprise AP backend: ${session.backendId}" }
        require(session.interfaceName == IFACE) { "Unexpected Enterprise AP interface: ${session.interfaceName}" }
        stopOwnedRuntime()
    }

    suspend fun stopOwnedRuntime() {
        val bssid = rememberedBssid()
        RootManager.use { root ->
            root.execute(EnterpriseApCommands.Stop(
                configPath = configFile.absolutePath,
                pidPath = pidFile.absolutePath,
                ownerPath = ownerFile.absolutePath,
                bssid = bssid,
            ))
        }
    }

    suspend fun isActive(): Boolean {
        val bssid = rememberedBssid()
        return RootManager.use { root ->
            root.execute(EnterpriseApCommands.Status(ownerFile.absolutePath, bssid)).value
        }
    }

    private fun rememberedBssid() = runCatching {
        bssidFile.takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull() ?: DEFAULT_BSSID

    private fun writeAtomic(file: File, value: String) {
        val parent = checkNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Unable to create Enterprise runtime directory: $parent" }
        val temporary = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeText(value)
            if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
        } finally {
            temporary.delete()
        }
    }
}
