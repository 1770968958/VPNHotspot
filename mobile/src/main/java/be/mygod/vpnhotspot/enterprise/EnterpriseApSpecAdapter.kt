package be.mygod.vpnhotspot.enterprise

import android.net.wifi.SoftApConfiguration
import android.util.SparseIntArray
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat

/** Narrow adapter from the upstream AP model to the Enterprise runtime model. */
fun SoftApConfigurationCompat.toEnterpriseApSpec(countryCode: String = "CN"): EnterpriseApSpec {
    val (runtimeBand, channel) = selectEnterpriseRadio(channels)
    val name = ssid?.decode() ?: throw IllegalArgumentException("Enterprise AP currently requires a UTF-8 SSID")
    return EnterpriseApSpec(
        ssid = name,
        band = runtimeBand,
        channel = channel,
        // The platform Soft AP BSSID belongs to its own interface (typically wlan1). Reusing it for our
        // independent Enterprise interface can make the Wi-Fi driver reject the MAC with EINVAL. The
        // Enterprise backend therefore owns a separate BSSID policy and must not inherit this value.
        bssid = null,
        hidden = isHiddenSsid,
        countryCode = countryCode,
    ).also(EnterpriseApSpec::validate)
}

/**
 * Android 11 commonly represents "2.4/5 GHz" as a single BAND_2GHZ|BAND_5GHZ bitmask with ACS channel 0.
 * Newer Android releases can also expose multiple channel entries for bridged AP. Our hostapd backend owns one
 * physical AP interface, so collapse either representation deterministically instead of rejecting it.
 *
 * Preserve an explicit channel when possible. If the framework only supplied an ACS-capable band mask, prefer 5 GHz
 * (the device backend may then choose its known-safe default, currently channel 149) and fall back to 2.4 GHz.
 */
internal fun selectEnterpriseRadio(channels: SparseIntArray): Pair<EnterpriseApSpec.Band, Int> {
    require(channels.size() > 0) { "Enterprise AP has no configured Wi-Fi band" }
    data class Candidate(val band: EnterpriseApSpec.Band, val channel: Int)
    val candidates = ArrayList<Candidate>(channels.size() * 2)
    for (index in 0 until channels.size()) {
        val mask = channels.keyAt(index)
        val channel = channels.valueAt(index)
        val supports2 = mask and SoftApConfiguration.BAND_2GHZ != 0
        val supports5 = mask and SoftApConfiguration.BAND_5GHZ != 0
        if (channel > 0 && supports2 && supports5) {
            // A non-zero channel disambiguates the legacy combined band mask on the devices we target.
            candidates += Candidate(
                if (channel <= 14) EnterpriseApSpec.Band.BAND_2GHZ else EnterpriseApSpec.Band.BAND_5GHZ,
                channel,
            )
        } else {
            if (supports5) candidates += Candidate(EnterpriseApSpec.Band.BAND_5GHZ, channel)
            if (supports2) candidates += Candidate(EnterpriseApSpec.Band.BAND_2GHZ, channel)
        }
    }
    return (candidates.firstOrNull { it.band == EnterpriseApSpec.Band.BAND_5GHZ && it.channel > 0 }
        ?: candidates.firstOrNull { it.band == EnterpriseApSpec.Band.BAND_2GHZ && it.channel > 0 }
        ?: candidates.firstOrNull { it.band == EnterpriseApSpec.Band.BAND_5GHZ }
        ?: candidates.firstOrNull { it.band == EnterpriseApSpec.Band.BAND_2GHZ }
        ?: throw IllegalArgumentException("Enterprise AP currently supports 2.4 GHz or 5 GHz bands"))
        .let { it.band to it.channel }
}
