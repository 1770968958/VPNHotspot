package be.mygod.vpnhotspot.enterprise

/** Pure renderer for the non-authentication part of a hostapd configuration. */
object HostapdApConfig {
    fun render(spec: EnterpriseApSpec): String {
        spec.validate()
        val radio = when (spec.band) {
            EnterpriseApSpec.Band.BAND_2GHZ -> """
                hw_mode=g
                ieee80211n=1
            """.trimIndent()
            EnterpriseApSpec.Band.BAND_5GHZ -> """
                hw_mode=a
                ieee80211n=1
                ieee80211ac=1
            """.trimIndent()
        }
        return buildString {
            append("driver=nl80211\n")
            append("ssid=").append(spec.ssid).append('\n')
            append("country_code=").append(spec.countryCode.uppercase()).append('\n')
            append(radio).append('\n')
            append("channel=").append(spec.channel).append('\n')
            append("ignore_broadcast_ssid=").append(if (spec.hidden) 1 else 0).append('\n')
            spec.bssid?.let { append("bssid=").append(it.lowercase()).append('\n') }
        }
    }
}
