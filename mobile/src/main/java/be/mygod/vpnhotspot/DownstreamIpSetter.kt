package be.mygod.vpnhotspot

import android.net.LinkAddress
import androidx.core.content.edit
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.root.daemon.DaemonController
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Runtime downstream-interface address override for real tethering interfaces.
 *
 * This deliberately replaces only IPv4 addresses requested by the UI and carries current IPv6
 * addresses through to vpnhotspotd. vpnhotspotd's ReplaceStaticAddressesCommand deletes every
 * address that is not in the requested set, so preserving IPv6 here avoids accidentally removing
 * link-local/router-advertisement state from wlan/rndis interfaces.
 */
object DownstreamIpSetter {
    const val KIND_WIFI = "wifi"
    const val KIND_USB = "usb"
    const val DEFAULT_WIFI_ADDRESS = "192.168.48.1/24"
    const val DEFAULT_WIFI_INTERFACE = "ap0"
    const val DEFAULT_USB_ADDRESS = "192.168.49.1/24"
    const val DEFAULT_USB_INTERFACE = "rndis0"

    private const val KEY_IFACE = "service.downstreamIp.iface."
    private const val KEY_ADDRESS = "service.downstreamIp.address."

    fun iface(kind: String, fallback: String) = app.pref.getString(KEY_IFACE + kind, null)?.takeIf {
        it.isNotBlank()
    } ?: fallback

    fun addresses(dev: String, fallback: String) = app.pref.getString(KEY_ADDRESS + dev, null)?.takeIf {
        it.isNotBlank()
    } ?: fallback

    fun parseIpv4Addresses(value: String): List<LinkAddress> {
        val addresses = try {
            StaticIpSetter.parseAddresses(value).toList()
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
        require(addresses.isNotEmpty()) { app.getString(R.string.tethering_downstream_ip_error_no_address) }
        for (address in addresses) {
            require(address.address is Inet4Address) {
                app.getString(R.string.tethering_downstream_ip_error_ipv4_only)
            }
        }
        return addresses
    }

    suspend fun apply(kind: String, dev: String, value: String) {
        val normalizedDev = dev.trim()
        require(normalizedDev.isNotEmpty()) { app.getString(R.string.tethering_downstream_ip_error_interface) }
        val requested = parseIpv4Addresses(value.trim())
        val preservedIpv6 = currentIpv6Addresses(normalizedDev)
        DaemonController.replaceStaticAddresses(normalizedDev, requested + preservedIpv6)
        app.pref.edit {
            putString(KEY_IFACE + kind, normalizedDev)
            putString(KEY_ADDRESS + normalizedDev, value.trim())
        }
    }

    private fun currentIpv6Addresses(dev: String): List<LinkAddress> = buildList {
        val iface = NetworkInterface.getByName(dev) ?: return@buildList
        for (address in iface.interfaceAddresses) {
            val inet = address.address
            if (inet !is Inet6Address || inet.isLoopbackAddress) continue
            val host = inet.hostAddress ?: continue
            add(StaticIpSetter.parseAddresses("$host/${address.networkPrefixLength}").single())
        }
    }
}
