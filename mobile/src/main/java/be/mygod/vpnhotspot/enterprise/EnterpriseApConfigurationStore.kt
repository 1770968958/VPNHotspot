package be.mygod.vpnhotspot.enterprise

import android.util.Base64
import androidx.core.content.edit
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat
import be.mygod.vpnhotspot.util.toByteArray
import be.mygod.vpnhotspot.util.toParcelable

/**
 * Persists the ordinary SSID/channel/BSSID/etc. carrier used by the Enterprise runtime.
 *
 * Enterprise authentication itself is deliberately not encoded in Android SoftApConfiguration. Keeping this state
 * separate means we never have to write an invented security type to WifiManager, and it isolates future upstream
 * changes to SoftApConfigurationCompat from the Enterprise profile schema.
 */
object EnterpriseApConfigurationStore {
    private const val KEY = "enterprise.ap.common_config.v1"
    private const val FLAGS = Base64.NO_PADDING or Base64.NO_WRAP

    fun load(): SoftApConfigurationCompat? = app.pref.getString(KEY, null)?.let { encoded ->
        Base64.decode(encoded, FLAGS).toParcelable<SoftApConfigurationCompat>(SoftApConfigurationCompat::class.java.classLoader)
    }

    fun save(configuration: SoftApConfigurationCompat) {
        val encoded = Base64.encodeToString(configuration.toByteArray(), FLAGS)
        app.pref.edit(commit = true) { putString(KEY, encoded) }
    }

    fun clear() {
        app.pref.edit(commit = true) { remove(KEY) }
    }
}
