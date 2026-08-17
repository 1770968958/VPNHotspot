package be.mygod.vpnhotspot.enterprise

/**
 * Runtime boundary for Enterprise AP implementations.
 *
 * UI and profile persistence must not depend on a concrete hostapd binary, linker namespace, interface name,
 * certificate backend, or Android release. A device backend can therefore be replaced without rewriting the
 * user-facing AP configuration flow.
 */
interface EnterpriseApRuntime {
    data class Capability(
        val wpa2Enterprise: Boolean,
        val wpa3Enterprise: Boolean,
        val wpa3Enterprise192: Boolean = false,
        val detail: String? = null,
    )

    data class Request(
        val ap: EnterpriseApSpec,
        val profile: EnterpriseApProfile,
    )

    data class Session(
        val interfaceName: String,
        val backendId: String,
    )

    suspend fun capability(): Capability
    suspend fun start(request: Request): Session
    suspend fun stop(session: Session)
}

/** Hostapd-facing fields intentionally independent from Android SoftApConfiguration. */
data class EnterpriseApSpec(
    val ssid: String,
    val band: Band,
    val channel: Int,
    val bssid: String? = null,
    val hidden: Boolean = false,
    val countryCode: String = "CN",
) {
    enum class Band { BAND_2GHZ, BAND_5GHZ }

    fun validate() {
        require(ssid.isNotEmpty()) { "SSID is required" }
        require(ssid.toByteArray(Charsets.UTF_8).size <= 32) { "SSID is longer than 32 bytes" }
        require(ssid.none { it == '\n' || it == '\r' || it.code == 0 }) { "SSID contains an unsupported character" }
        require(channel >= 0) { "Invalid channel" }
        require(countryCode.length == 2 && countryCode.all(Char::isLetter)) { "Invalid country code" }
        bssid?.let {
            require(BSSID.matches(it)) { "Invalid BSSID" }
            require(it.substringBefore(':').toInt(16) and 1 == 0) { "BSSID must be unicast" }
        }
    }

    companion object {
        private val BSSID = Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
    }
}
