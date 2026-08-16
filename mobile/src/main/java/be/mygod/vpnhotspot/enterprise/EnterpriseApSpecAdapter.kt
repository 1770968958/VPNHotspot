package be.mygod.vpnhotspot.enterprise

import android.net.wifi.SoftApConfiguration
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat

/** Narrow adapter from the upstream AP model to the Enterprise runtime model. */
fun SoftApConfigurationCompat.toEnterpriseApSpec(countryCode: String = "CN"): EnterpriseApSpec {
    val (band, channel) = SoftApConfigurationCompat.requireSingleBand(channels)
    val runtimeBand = when (band) {
        SoftApConfiguration.BAND_2GHZ -> EnterpriseApSpec.Band.BAND_2GHZ
        SoftApConfiguration.BAND_5GHZ -> EnterpriseApSpec.Band.BAND_5GHZ
        else -> throw IllegalArgumentException("Enterprise AP currently supports one 2.4 GHz or 5 GHz band")
    }
    val name = ssid?.decode() ?: throw IllegalArgumentException("Enterprise AP currently requires a UTF-8 SSID")
    return EnterpriseApSpec(
        ssid = name,
        band = runtimeBand,
        channel = channel,
        bssid = bssid?.toString(),
        hidden = isHiddenSsid,
        countryCode = countryCode,
    ).also(EnterpriseApSpec::validate)
}
