package be.mygod.vpnhotspot.ui.apconfiguration

import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.enterprise.EnterpriseSecurityMode

/**
 * UI-only sentinel values. They are never written into SoftApConfiguration.securityType.
 * Keeping them here isolates the existing Android securityType model from Enterprise modes.
 */
internal object EnterpriseSecurityAdapter {
    const val WPA2_ENTERPRISE = -10_001
    const val WPA3_ENTERPRISE = -10_002

    val options = listOf(
        SecurityOption(R.string.wifi_security_wpa2_enterprise, WPA2_ENTERPRISE),
        SecurityOption(R.string.wifi_security_wpa3_enterprise, WPA3_ENTERPRISE),
    )

    fun mode(value: Int) = when (value) {
        WPA2_ENTERPRISE -> EnterpriseSecurityMode.WPA2_ENTERPRISE
        WPA3_ENTERPRISE -> EnterpriseSecurityMode.WPA3_ENTERPRISE
        else -> null
    }

    fun value(mode: EnterpriseSecurityMode?) = when (mode) {
        EnterpriseSecurityMode.WPA2_ENTERPRISE -> WPA2_ENTERPRISE
        EnterpriseSecurityMode.WPA3_ENTERPRISE -> WPA3_ENTERPRISE
        else -> null
    }
}
