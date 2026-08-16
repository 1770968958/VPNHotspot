package be.mygod.vpnhotspot.enterprise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class EnterpriseCertificateProvisionerTest {
    @Test
    fun provisionsParsableTrustedServerBundleAndReusesIt() {
        withTempDir { root ->
            val files = EnterpriseCertificateProvisioner.ensure(root, "vpn-hotspot.local")
            val ca = certificate(files.caCertificate)
            val server = certificate(files.serverCertificate)
            ca.verify(ca.publicKey)
            server.verify(ca.publicKey)
            ca.checkValidity()
            server.checkValidity()
            assertEquals(1, ca.basicConstraints.coerceAtMost(1))
            assertEquals(-1, server.basicConstraints)
            assertTrue(server.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"))
            assertTrue(server.subjectAlternativeNames.any { it[0] == 2 && it[1] == "vpn-hotspot.local" })
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(readPem(files.serverPrivateKey)))

            val caBefore = files.caCertificate.readBytes()
            val serverBefore = files.serverCertificate.readBytes()
            val again = EnterpriseCertificateProvisioner.ensure(root, "vpn-hotspot.local")
            assertArrayEquals(caBefore, again.caCertificate.readBytes())
            assertArrayEquals(serverBefore, again.serverCertificate.readBytes())
        }
    }

    @Test
    fun identityChangeRotatesBundle() {
        withTempDir { root ->
            val first = EnterpriseCertificateProvisioner.ensure(root, "one.example").serverCertificate.readBytes()
            val secondFiles = EnterpriseCertificateProvisioner.ensure(root, "two.example")
            assertNotEquals(first.toList(), secondFiles.serverCertificate.readBytes().toList())
            assertTrue(certificate(secondFiles.serverCertificate).subjectAlternativeNames.any {
                it[0] == 2 && it[1] == "two.example"
            })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonDnsIdentity() {
        withTempDir { root -> EnterpriseCertificateProvisioner.ensure(root, "not a dns name") }
    }

    @Test
    fun materializesCompleteHostapdWorkspace() {
        withTempDir { root ->
            val request = EnterpriseApRuntime.Request(
                ap = EnterpriseApSpec(
                    ssid = "MI9-Enterprise",
                    band = EnterpriseApSpec.Band.BAND_5GHZ,
                    channel = 149,
                    bssid = "6a:66:77:88:99:a8",
                ),
                profile = EnterpriseApProfile(
                    mode = EnterpriseSecurityMode.WPA3_ENTERPRISE,
                    users = listOf(EnterpriseUser("1", "alice", "secret")),
                    serverIdentity = "vpn-hotspot.local",
                ),
            )
            val prepared = EnterpriseRuntimeWorkspace(root).prepare(request, "wlan2")
            val config = prepared.hostapdConfig.readText()
            assertTrue(config.contains("interface=wlan2\n"))
            assertTrue(config.contains("ctrl_interface=${prepared.controlDirectory.absolutePath}\n"))
            assertTrue(config.contains("wpa_key_mgmt=WPA-EAP-SHA256\n"))
            assertTrue(config.contains("server_cert=${prepared.certificates.serverCertificate.absolutePath}\n"))
            assertTrue(prepared.eapUsers.readText().contains("\"alice\" MSCHAPV2 \"secret\" [2]\n"))
        }
    }

    private fun certificate(file: File): X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(readPem(file))) as X509Certificate

    private fun readPem(file: File): ByteArray = Base64.getDecoder().decode(
        file.readLines().filterNot { it.startsWith("-----") }.joinToString(""),
    )

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDir(prefix = "enterprise-pki-")
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
