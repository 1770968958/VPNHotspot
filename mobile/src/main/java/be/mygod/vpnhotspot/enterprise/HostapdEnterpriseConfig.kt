package be.mygod.vpnhotspot.enterprise

data class EnterpriseRuntimePaths(
    val eapUserFile: String,
    val caCertificate: String,
    val serverCertificate: String,
    val serverPrivateKey: String,
)

data class RenderedEnterpriseConfig(
    val hostapdFragment: String,
    val eapUsers: String,
)

/** Pure renderer: no Android services, filesystem access, root calls, or process lifecycle. */
object HostapdEnterpriseConfig {
    fun render(profile: EnterpriseApProfile, paths: EnterpriseRuntimePaths): RenderedEnterpriseConfig {
        profile.validate()
        requirePath(paths.eapUserFile)
        requirePath(paths.caCertificate)
        requirePath(paths.serverCertificate)
        requirePath(paths.serverPrivateKey)
        require(profile.mode != EnterpriseSecurityMode.WPA3_ENTERPRISE_192) {
            "WPA3-Enterprise 192-bit requires the certificate-identity model and is not enabled in phase 1"
        }

        val security = when (profile.mode) {
            EnterpriseSecurityMode.WPA2_ENTERPRISE -> """
                wpa=2
                wpa_key_mgmt=WPA-EAP
                rsn_pairwise=CCMP
                ieee80211w=1
            """.trimIndent()
            EnterpriseSecurityMode.WPA3_ENTERPRISE -> """
                wpa=2
                wpa_key_mgmt=WPA-EAP-SHA256
                rsn_pairwise=CCMP
                ieee80211w=2
            """.trimIndent()
            EnterpriseSecurityMode.WPA3_ENTERPRISE_192 -> error("checked above")
        }
        val common = """
            ieee8021x=1
            eap_server=1
            eap_user_file=${paths.eapUserFile}
            ca_cert=${paths.caCertificate}
            server_cert=${paths.serverCertificate}
            private_key=${paths.serverPrivateKey}
            server_id=${profile.serverIdentity}
        """.trimIndent()
        val eapUsers = buildString {
            append("* PEAP\n")
            for (user in profile.enabledUsers) {
                append('"').append(user.username).append("\" MSCHAPV2 \"")
                    .append(user.password).append("\" [2]\n")
            }
        }
        return RenderedEnterpriseConfig("$security\n$common\n", eapUsers)
    }

    private fun requirePath(path: String) {
        require(path.startsWith('/')) { "Enterprise runtime paths must be absolute" }
        require(path.none { it == '\n' || it == '\r' || it.code == 0 }) { "Invalid Enterprise runtime path" }
    }
}
