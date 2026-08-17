package be.mygod.vpnhotspot.enterprise

/**
 * Stable Enterprise security modes. Persist [id], never enum ordinal or Android SoftApConfiguration constants.
 *
 * Android's public Soft AP configuration does not model these custom hostapd modes on our target, so these values
 * deliberately live outside the framework securityType namespace.
 */
enum class EnterpriseSecurityMode(val id: String) {
    WPA2_ENTERPRISE("wpa2-enterprise"),
    WPA3_ENTERPRISE("wpa3-enterprise"),
    WPA3_ENTERPRISE_192("wpa3-enterprise-192");

    val passwordUsersSupported get() = this != WPA3_ENTERPRISE_192

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

data class EnterpriseUser(
    val id: String,
    val username: String,
    val password: String,
    val enabled: Boolean = true,
)

data class EnterpriseApProfile(
    val mode: EnterpriseSecurityMode,
    val users: List<EnterpriseUser> = emptyList(),
    val serverIdentity: String = "vpn-hotspot.local",
) {
    val enabledUsers get() = users.filter(EnterpriseUser::enabled)

    fun validate() {
        require(serverIdentity.isNotBlank()) { "Server identity is required" }
        requireSafeHostapdValue(serverIdentity, "Server identity")
        require(users.map(EnterpriseUser::id).distinct().size == users.size) { "Duplicate Enterprise user IDs" }
        if (mode.passwordUsersSupported) {
            require(enabledUsers.isNotEmpty()) { "At least one enabled Enterprise user is required" }
            val enabledNames = enabledUsers.map(EnterpriseUser::username)
            require(enabledNames.distinct().size == enabledNames.size) { "Duplicate enabled Enterprise usernames" }
            for (user in users) {
                require(user.id.isNotBlank()) { "Enterprise user ID is required" }
                require(user.username.isNotBlank()) { "Enterprise username is required" }
                require(user.password.isNotEmpty()) { "Enterprise password is required" }
                requireSafeHostapdQuotedValue(user.username, "Enterprise username")
                requireSafeHostapdQuotedValue(user.password, "Enterprise password")
            }
        } else {
            require(users.isEmpty()) { "WPA3-Enterprise 192-bit uses certificate identities, not password users" }
        }
    }
}

private fun requireSafeHostapdQuotedValue(value: String, label: String) {
    require(value.none { it == '"' || it == '\\' || it == '\n' || it == '\r' || it == '\t' || it.code == 0 }) {
        "$label contains a character that cannot be represented safely in hostapd.eap_user"
    }
}

private fun requireSafeHostapdValue(value: String, label: String) {
    require(value.none { it == '\n' || it == '\r' || it.code == 0 }) {
        "$label contains a character that cannot be represented safely in hostapd configuration"
    }
}
