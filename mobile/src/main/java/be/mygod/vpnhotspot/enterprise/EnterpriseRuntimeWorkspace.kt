package be.mygod.vpnhotspot.enterprise

import java.io.File

/** Materializes one complete hostapd Enterprise session without owning process or interface lifecycle. */
class EnterpriseRuntimeWorkspace(private val directory: File) {
    data class Prepared(
        val hostapdConfig: File,
        val eapUsers: File,
        val controlDirectory: File,
        val pidFile: File,
        val logFile: File,
        val certificates: EnterpriseCertificateProvisioner.Files,
    )

    fun prepare(request: EnterpriseApRuntime.Request, interfaceName: String): Prepared {
        request.ap.validate()
        request.profile.validate()
        require(IFACE.matches(interfaceName)) { "Invalid Enterprise AP interface name" }
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create Enterprise runtime directory: $directory" }

        val certificates = EnterpriseCertificateProvisioner.ensure(File(directory, "pki"), request.profile.serverIdentity)
        val runtime = File(directory, "runtime").apply {
            check(mkdirs() || isDirectory) { "Unable to create Enterprise runtime workspace: $this" }
        }
        val control = File(runtime, "ctrl").apply {
            check(mkdirs() || isDirectory) { "Unable to create hostapd control directory: $this" }
        }
        val config = File(runtime, "hostapd.conf")
        val users = File(runtime, "eap_user")
        val pid = File(runtime, "hostapd.pid")
        val log = File(runtime, "hostapd.log")
        val paths = EnterpriseRuntimePaths(
            eapUserFile = users.absolutePath,
            caCertificate = certificates.caCertificate.absolutePath,
            serverCertificate = certificates.serverCertificate.absolutePath,
            serverPrivateKey = certificates.serverPrivateKey.absolutePath,
        )
        val enterprise = HostapdEnterpriseConfig.render(request.profile, paths)
        writeAtomic(users, enterprise.eapUsers)
        writeAtomic(config, buildString {
            append("interface=").append(interfaceName).append('\n')
            append("ctrl_interface=").append(control.absolutePath).append('\n')
            append(HostapdApConfig.render(request.ap))
            append(enterprise.hostapdFragment)
        })
        pid.delete()
        return Prepared(config, users, control, pid, log, certificates)
    }

    private fun writeAtomic(file: File, text: String) {
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temp.writeText(text)
            if (!temp.renameTo(file)) temp.copyTo(file, overwrite = true)
        } finally {
            temp.delete()
        }
    }

    companion object {
        private val IFACE = Regex("^[A-Za-z0-9_.-]{1,15}$")
    }
}
