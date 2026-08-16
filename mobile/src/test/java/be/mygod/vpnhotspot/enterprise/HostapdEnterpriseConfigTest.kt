package be.mygod.vpnhotspot.enterprise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostapdEnterpriseConfigTest {
    private val paths = EnterpriseRuntimePaths(
        eapUserFile = "/data/user_de/0/be.mygod.vpnhotspot/files/enterprise/eap_user",
        caCertificate = "/data/user_de/0/be.mygod.vpnhotspot/files/enterprise/ca.pem",
        serverCertificate = "/data/user_de/0/be.mygod.vpnhotspot/files/enterprise/server.pem",
        serverPrivateKey = "/data/user_de/0/be.mygod.vpnhotspot/files/enterprise/server.key",
    )

    @Test
    fun renderWpa2Enterprise_multipleUsers() {
        val rendered = HostapdEnterpriseConfig.render(
            EnterpriseApProfile(
                EnterpriseSecurityMode.WPA2_ENTERPRISE,
                users = listOf(
                    EnterpriseUser("1", "alice", "alice-pass"),
                    EnterpriseUser("2", "bob", "bob-pass"),
                    EnterpriseUser("3", "disabled", "disabled-pass", enabled = false),
                ),
            ),
            paths,
        )
        assertTrue(rendered.hostapdFragment.contains("wpa_key_mgmt=WPA-EAP\n"))
        assertTrue(rendered.hostapdFragment.contains("ieee80211w=1\n"))
        assertEquals(
            "* PEAP\n\"alice\" MSCHAPV2 \"alice-pass\" [2]\n\"bob\" MSCHAPV2 \"bob-pass\" [2]\n",
            rendered.eapUsers,
        )
    }

    @Test
    fun renderWpa3Enterprise_requiresPmf() {
        val rendered = HostapdEnterpriseConfig.render(
            EnterpriseApProfile(
                EnterpriseSecurityMode.WPA3_ENTERPRISE,
                users = listOf(EnterpriseUser("1", "alice", "alice-pass")),
            ),
            paths,
        )
        assertTrue(rendered.hostapdFragment.contains("wpa_key_mgmt=WPA-EAP-SHA256\n"))
        assertTrue(rendered.hostapdFragment.contains("ieee80211w=2\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateEnabledUsernameRejected() {
        EnterpriseApProfile(
            EnterpriseSecurityMode.WPA2_ENTERPRISE,
            users = listOf(
                EnterpriseUser("1", "alice", "one"),
                EnterpriseUser("2", "alice", "two"),
            ),
        ).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsafeQuotedPasswordRejected() {
        EnterpriseApProfile(
            EnterpriseSecurityMode.WPA2_ENTERPRISE,
            users = listOf(EnterpriseUser("1", "alice", "bad\"password")),
        ).validate()
    }
}
