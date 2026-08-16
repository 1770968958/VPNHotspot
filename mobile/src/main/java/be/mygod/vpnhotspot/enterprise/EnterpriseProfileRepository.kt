package be.mygod.vpnhotspot.enterprise

import android.content.SharedPreferences
import androidx.core.content.edit
import be.mygod.vpnhotspot.App.Companion.app
import org.json.JSONArray
import org.json.JSONObject

interface EnterpriseProfileRepository {
    fun load(): EnterpriseApProfile?
    fun save(profile: EnterpriseApProfile?)
}

/**
 * Phase-1 persistence backend. Callers depend only on [EnterpriseProfileRepository] so this can be replaced by a
 * Keystore-backed credential store without changing AP configuration UI or hostapd rendering.
 */
class SharedPreferencesEnterpriseProfileRepository(
    private val preferences: SharedPreferences,
    private val key: String = KEY,
) : EnterpriseProfileRepository {
    override fun load(): EnterpriseApProfile? = preferences.getString(key, null)?.let(::decode)

    override fun save(profile: EnterpriseApProfile?) {
        profile?.validate()
        preferences.edit(commit = true) {
            if (profile == null) remove(key) else putString(key, encode(profile))
        }
    }

    private fun encode(profile: EnterpriseApProfile) = JSONObject().apply {
        put("version", VERSION)
        put("mode", profile.mode.id)
        put("serverIdentity", profile.serverIdentity)
        put("users", JSONArray().apply {
            for (user in profile.users) put(JSONObject().apply {
                put("id", user.id)
                put("username", user.username)
                put("password", user.password)
                put("enabled", user.enabled)
            })
        })
    }.toString()

    private fun decode(encoded: String): EnterpriseApProfile {
        val root = JSONObject(encoded)
        require(root.getInt("version") == VERSION) { "Unsupported Enterprise profile version" }
        val mode = EnterpriseSecurityMode.fromId(root.getString("mode"))
            ?: throw IllegalArgumentException("Unknown Enterprise security mode")
        val usersJson = root.optJSONArray("users") ?: JSONArray()
        val users = List(usersJson.length()) { index ->
            val user = usersJson.getJSONObject(index)
            EnterpriseUser(
                id = user.getString("id"),
                username = user.getString("username"),
                password = user.getString("password"),
                enabled = user.optBoolean("enabled", true),
            )
        }
        return EnterpriseApProfile(
            mode = mode,
            users = users,
            serverIdentity = root.optString("serverIdentity", "vpn-hotspot.local"),
        ).also(EnterpriseApProfile::validate)
    }

    companion object {
        private const val KEY = "enterprise.ap.profile.v1"
        private const val VERSION = 1
    }
}

object EnterpriseProfiles : EnterpriseProfileRepository by SharedPreferencesEnterpriseProfileRepository(app.pref)
