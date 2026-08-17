package be.mygod.vpnhotspot.enterprise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostapdApConfigTest {
    @Test
    fun render5Ghz() {
        val rendered = HostapdApConfig.render(EnterpriseApSpec(
            ssid = "MI9-Enterprise",
            band = EnterpriseApSpec.Band.BAND_5GHZ,
            channel = 149,
            bssid = "6a:66:77:88:99:a8",
        ))
        assertTrue(rendered.contains("hw_mode=a\n"))
        assertTrue(rendered.contains("ieee80211ac=1\n"))
        assertTrue(rendered.contains("channel=149\n"))
        assertTrue(rendered.contains("bssid=6a:66:77:88:99:a8\n"))
    }

    @Test
    fun render2GhzHidden() {
        val rendered = HostapdApConfig.render(EnterpriseApSpec(
            ssid = "lab",
            band = EnterpriseApSpec.Band.BAND_2GHZ,
            channel = 6,
            hidden = true,
        ))
        assertTrue(rendered.contains("hw_mode=g\n"))
        assertTrue(rendered.contains("ignore_broadcast_ssid=1\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectMultilineSsid() {
        EnterpriseApSpec("bad\nssid", EnterpriseApSpec.Band.BAND_2GHZ, 1).validate()
    }

    @Test
    fun backendIdsAreStableStrings() {
        assertEquals("wpa2-enterprise", EnterpriseSecurityMode.WPA2_ENTERPRISE.id)
        assertEquals("wpa3-enterprise", EnterpriseSecurityMode.WPA3_ENTERPRISE.id)
    }
}
