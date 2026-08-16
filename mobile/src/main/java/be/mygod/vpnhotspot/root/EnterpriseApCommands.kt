package be.mygod.vpnhotspot.root

import android.os.RemoteException
import be.mygod.librootkotlinx.ParcelableBoolean
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandNoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

/**
 * Root-only lifecycle for the custom Enterprise hostapd backend.
 *
 * This deliberately owns only the AP interface, hostapd, DHCP attachment and Android local-network plumbing.
 * VPN routing/NAT/DNS policy stays in VPN Hotspot's existing [be.mygod.vpnhotspot.RoutingManager].
 */
object EnterpriseApCommands {
    const val IFACE = "wlan2"
    const val GATEWAY = "192.168.77.1"
    const val SUBNET = "192.168.77.0/24"

    private const val DHCP_START = "192.168.77.20"
    private const val DHCP_END = "192.168.77.200"
    private const val EXTERNAL_BASE = "/data/local/tmp/mi9-enterprise"
    private const val HOSTAPD = "$EXTERNAL_BASE/hostapd"
    private const val STAGED_LIBDIR = "$EXTERNAL_BASE/lib"
    private const val TERMUX_LIBDIR = "$EXTERNAL_BASE/termux_libdir"

    private val SAFE_PATH = Regex("^/[A-Za-z0-9_./-]+$")
    private val BSSID = Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")

    private fun requirePath(path: String) {
        require(SAFE_PATH.matches(path)) { "Invalid Enterprise runtime path: $path" }
    }

    private fun requireBssid(value: String) {
        require(BSSID.matches(value)) { "Invalid Enterprise BSSID" }
        require(value.substringBefore(':').toInt(16) and 1 == 0) { "Enterprise BSSID must be unicast" }
    }

    private suspend fun shellResult(script: String) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor() to output
    }

    private suspend fun shell(script: String) {
        val (exit, output) = shellResult(script)
        if (exit != 0) throw RemoteException("Enterprise AP command exited with $exit: $output")
    }

    @Parcelize
    data class Start(
        val configPath: String,
        val pidPath: String,
        val ownerPath: String,
        val logPath: String,
        val bssid: String,
    ) : RootCommandNoResult {
        override suspend fun execute() = null.also {
            requirePath(configPath)
            requirePath(pidPath)
            requirePath(ownerPath)
            requirePath(logPath)
            requireBssid(bssid)
            shell("""
                set -eu
                PATH=/system/bin:/system/xbin:/vendor/bin:${'$'}PATH
                IFACE='$IFACE'
                BSSID='${bssid.lowercase()}'
                HOSTAPD='$HOSTAPD'
                CONFIG='$configPath'
                PID='$pidPath'
                OWNER='$ownerPath'
                LOG='$logPath'
                DHCP_START='$DHCP_START'
                DHCP_END='$DHCP_END'
                STAGED_LIBDIR='$STAGED_LIBDIR'
                TERMUX_LIBDIR='$TERMUX_LIBDIR'
                BOOT_ID="${'$'}(cat /proc/sys/kernel/random/boot_id)"
                CREATED_IFACE=0
                [ -x "${'$'}HOSTAPD" ] || { echo "Missing ${'$'}HOSTAPD"; exit 10; }
                [ -f "${'$'}CONFIG" ] || { echo "Missing ${'$'}CONFIG"; exit 11; }
                LIBDIR_HINT=''
                [ ! -r "${'$'}TERMUX_LIBDIR" ] || LIBDIR_HINT="${'$'}(cat "${'$'}TERMUX_LIBDIR" | tr -d '\r\n')"
                LIBDIR=''
                for CANDIDATE in "${'$'}STAGED_LIBDIR" "${'$'}LIBDIR_HINT" /data/user/0/com.termux/files/usr/lib /data/data/com.termux/files/usr/lib; do
                    [ -n "${'$'}CANDIDATE" ] || continue
                    [ -r "${'$'}CANDIDATE/libnl-3.so" ] || continue
                    [ -r "${'$'}CANDIDATE/libssl.so.3" ] || continue
                    [ -r "${'$'}CANDIDATE/libcrypto.so.3" ] || continue
                    LIBDIR="${'$'}CANDIDATE"
                    break
                done
                [ -n "${'$'}LIBDIR" ] || {
                    echo "Missing hostapd runtime libraries. Stage Termux dependencies in ${'$'}STAGED_LIBDIR"
                    exit 16
                }
                pid_matches() {
                    [ -n "${'$'}1" ] && [ -r "/proc/${'$'}1/cmdline" ] || return 1
                    tr '\000' ' ' < "/proc/${'$'}1/cmdline" 2>/dev/null | grep -F -- "${'$'}CONFIG" >/dev/null
                }
                owner_boot_current() {
                    [ -r "${'$'}OWNER" ] || return 1
                    [ "${'$'}(awk 'NR == 1 { print ${'$'}1 }' "${'$'}OWNER" 2>/dev/null)" = "${'$'}BOOT_ID" ]
                }
                owned_iface_matches() {
                    owner_boot_current || return 1
                    OWNER_IFINDEX="${'$'}(awk 'NR == 1 { print ${'$'}2 }' "${'$'}OWNER" 2>/dev/null)"
                    case "${'$'}OWNER_IFINDEX" in *[!0-9]*|'') return 1 ;; esac
                    [ -r "/sys/class/net/${'$'}IFACE/ifindex" ] || return 1
                    [ "${'$'}(cat "/sys/class/net/${'$'}IFACE/ifindex")" = "${'$'}OWNER_IFINDEX" ] || return 1
                    [ -r "/sys/class/net/${'$'}IFACE/address" ] || return 1
                    [ "${'$'}(cat "/sys/class/net/${'$'}IFACE/address")" = "${'$'}BSSID" ]
                }
                cleanup() {
                    if owner_boot_current; then
                        ndc network route remove local "${'$'}IFACE" '$SUBNET' >/dev/null 2>&1 || true
                        ndc network interface remove local "${'$'}IFACE" >/dev/null 2>&1 || true
                        ndc tether interface remove "${'$'}IFACE" >/dev/null 2>&1 || true
                        if [ -r "${'$'}PID" ]; then
                            HPID="${'$'}(cat "${'$'}PID" 2>/dev/null || true)"
                            if pid_matches "${'$'}HPID"; then kill "${'$'}HPID" >/dev/null 2>&1 || true; fi
                        fi
                        if [ "${'$'}CREATED_IFACE" -eq 1 ] || owned_iface_matches; then
                            iw dev "${'$'}IFACE" del >/dev/null 2>&1 || true
                        fi
                    fi
                    rm -f "${'$'}PID" "${'$'}OWNER"
                }
                HOSTAPD_RUNNING=0
                if owner_boot_current && owned_iface_matches; then
                    HPID="${'$'}(cat "${'$'}PID" 2>/dev/null || true)"
                    if pid_matches "${'$'}HPID"; then
                        HOSTAPD_RUNNING=1
                    else
                        cleanup
                    fi
                else
                    rm -f "${'$'}PID" "${'$'}OWNER"
                fi
                if [ "${'$'}HOSTAPD_RUNNING" -eq 0 ] && ip link show "${'$'}IFACE" >/dev/null 2>&1; then
                    echo "${'$'}IFACE already exists without a matching Enterprise AP ownership marker"
                    exit 15
                fi
                trap 'cleanup' EXIT
                if [ "${'$'}HOSTAPD_RUNNING" -eq 0 ]; then
                    printf '%s pending %s\n' "${'$'}BOOT_ID" "${'$'}BSSID" > "${'$'}OWNER"
                    iw phy phy0 interface add "${'$'}IFACE" type __ap
                    CREATED_IFACE=1
                    IFINDEX="${'$'}(cat "/sys/class/net/${'$'}IFACE/ifindex")"
                    ip link set "${'$'}IFACE" down >/dev/null 2>&1 || true
                    ip link set "${'$'}IFACE" address "${'$'}BSSID"
                    printf '%s %s %s\n' "${'$'}BOOT_ID" "${'$'}IFINDEX" "${'$'}BSSID" > "${'$'}OWNER"
                    : > "${'$'}LOG"
                    LD_LIBRARY_PATH="${'$'}LIBDIR:/vendor/lib64:/vendor/lib64/hw:/system/lib64" "${'$'}HOSTAPD" -B -P "${'$'}PID" -f "${'$'}LOG" -dd -t "${'$'}CONFIG"
                    HPID="${'$'}(cat "${'$'}PID")"
                    pid_matches "${'$'}HPID" || { echo "hostapd exited during startup"; exit 17; }
                fi
                ndc interface setcfg "${'$'}IFACE" '$GATEWAY' 24 up >/dev/null
                ndc tether start "${'$'}DHCP_START" "${'$'}DHCP_END" >/dev/null 2>&1 || true
                if ! ndc tether interface add "${'$'}IFACE" >/dev/null 2>&1; then
                    ndc tether interface list 2>/dev/null | grep -qw "${'$'}IFACE" || {
                        echo "Failed to add ${'$'}IFACE to Android tethering"
                        exit 12
                    }
                fi
                ndc network interface add local "${'$'}IFACE" >/dev/null 2>&1 || true
                ndc network route add local "${'$'}IFACE" '$SUBNET' >/dev/null 2>&1 || true
                ip -4 route show table local_network | grep -F '$SUBNET dev '"${'$'}IFACE" >/dev/null || {
                    echo "Missing local_network route for ${'$'}IFACE"
                    exit 13
                }
                DHCP_OK=0
                for P in ${'$'}(pidof dnsmasq 2>/dev/null || true); do
                    if tr '\000' ' ' < "/proc/${'$'}P/cmdline" 2>/dev/null | grep -F -- "--dhcp-range=${'$'}DHCP_START,${'$'}DHCP_END" >/dev/null; then
                        DHCP_OK=1
                        break
                    fi
                done
                [ "${'$'}DHCP_OK" -eq 1 ] || {
                    echo "Android tethering DHCP server does not own ${'$'}DHCP_START-${'$'}DHCP_END"
                    exit 14
                }
                trap - EXIT
            """.trimIndent())
        }
    }

    @Parcelize
    data class Stop(
        val configPath: String,
        val pidPath: String,
        val ownerPath: String,
        val bssid: String,
    ) : RootCommandNoResult {
        override suspend fun execute() = null.also {
            requirePath(configPath)
            requirePath(pidPath)
            requirePath(ownerPath)
            requireBssid(bssid)
            shell("""
                PATH=/system/bin:/system/xbin:/vendor/bin:${'$'}PATH
                IFACE='$IFACE'
                BSSID='${bssid.lowercase()}'
                CONFIG='$configPath'
                PID='$pidPath'
                OWNER='$ownerPath'
                BOOT_ID="${'$'}(cat /proc/sys/kernel/random/boot_id)"
                pid_matches() {
                    [ -n "${'$'}1" ] && [ -r "/proc/${'$'}1/cmdline" ] || return 1
                    tr '\000' ' ' < "/proc/${'$'}1/cmdline" 2>/dev/null | grep -F -- "${'$'}CONFIG" >/dev/null
                }
                owner_boot_current() {
                    [ -r "${'$'}OWNER" ] || return 1
                    [ "${'$'}(awk 'NR == 1 { print ${'$'}1 }' "${'$'}OWNER" 2>/dev/null)" = "${'$'}BOOT_ID" ]
                }
                owned_iface_matches() {
                    owner_boot_current || return 1
                    OWNER_IFINDEX="${'$'}(awk 'NR == 1 { print ${'$'}2 }' "${'$'}OWNER" 2>/dev/null)"
                    case "${'$'}OWNER_IFINDEX" in *[!0-9]*|'') return 1 ;; esac
                    [ -r "/sys/class/net/${'$'}IFACE/ifindex" ] || return 1
                    [ "${'$'}(cat "/sys/class/net/${'$'}IFACE/ifindex")" = "${'$'}OWNER_IFINDEX" ] || return 1
                    [ -r "/sys/class/net/${'$'}IFACE/address" ] || return 1
                    [ "${'$'}(cat "/sys/class/net/${'$'}IFACE/address")" = "${'$'}BSSID" ]
                }
                if owner_boot_current; then
                    ndc network route remove local "${'$'}IFACE" '$SUBNET' >/dev/null 2>&1 || true
                    ndc network interface remove local "${'$'}IFACE" >/dev/null 2>&1 || true
                    ndc tether interface remove "${'$'}IFACE" >/dev/null 2>&1 || true
                    if [ -r "${'$'}PID" ]; then
                        HPID="${'$'}(cat "${'$'}PID" 2>/dev/null || true)"
                        if pid_matches "${'$'}HPID"; then kill "${'$'}HPID" >/dev/null 2>&1 || true; fi
                    fi
                    if owned_iface_matches; then iw dev "${'$'}IFACE" del >/dev/null 2>&1 || true; fi
                fi
                rm -f "${'$'}PID" "${'$'}OWNER"
            """.trimIndent())
        }
    }

    @Parcelize
    data class Status(
        val ownerPath: String,
        val bssid: String,
    ) : RootCommand<ParcelableBoolean> {
        override suspend fun execute(): ParcelableBoolean {
            requirePath(ownerPath)
            requireBssid(bssid)
            val (exit, _) = shellResult("""
                PATH=/system/bin:/system/xbin:/vendor/bin:${'$'}PATH
                IFACE='$IFACE'
                BSSID='${bssid.lowercase()}'
                OWNER='$ownerPath'
                BOOT_ID="${'$'}(cat /proc/sys/kernel/random/boot_id)"
                [ -r "${'$'}OWNER" ] || exit 1
                [ "${'$'}(awk 'NR == 1 { print ${'$'}1 }' "${'$'}OWNER" 2>/dev/null)" = "${'$'}BOOT_ID" ] || exit 1
                OWNER_IFINDEX="${'$'}(awk 'NR == 1 { print ${'$'}2 }' "${'$'}OWNER" 2>/dev/null)"
                case "${'$'}OWNER_IFINDEX" in *[!0-9]*|'') exit 1 ;; esac
                [ -r "/sys/class/net/${'$'}IFACE/ifindex" ] || exit 1
                [ "${'$'}(cat "/sys/class/net/${'$'}IFACE/ifindex")" = "${'$'}OWNER_IFINDEX" ] || exit 1
                [ -r "/sys/class/net/${'$'}IFACE/address" ] || exit 1
                [ "${'$'}(cat "/sys/class/net/${'$'}IFACE/address")" = "${'$'}BSSID" ]
            """.trimIndent())
            return ParcelableBoolean(exit == 0)
        }
    }
}
