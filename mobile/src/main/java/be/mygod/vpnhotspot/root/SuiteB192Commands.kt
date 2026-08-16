package be.mygod.vpnhotspot.root

import android.os.RemoteException
import be.mygod.librootkotlinx.RootCommandNoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

object SuiteB192Commands {
    const val IFACE = "wlan2"
    const val GATEWAY = "192.168.77.1"
    const val SUBNET = "192.168.77.0/24"

    private const val DHCP_START = "192.168.77.20"
    private const val DHCP_END = "192.168.77.200"
    private const val BASE = "/data/local/tmp/mi9-enterprise"
    private const val HOSTAPD = "$BASE/hostapd"
    private const val CONFIG = "$BASE/suiteb192/suiteb192.conf"
    private const val TERMUX_LIBDIR = "$BASE/termux_libdir"
    private const val PID = "$BASE/vpnhotspot-suiteb192.pid"
    private const val LOG = "$BASE/vpnhotspot-suiteb192.log"

    private suspend fun shell(script: String) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw RemoteException("Suite-B-192 command exited with $exit: $output")
    }

    @Parcelize
    class Start : RootCommandNoResult {
        override suspend fun execute() = null.also {
            shell("""
                set -eu
                IFACE='$IFACE'
                HOSTAPD='$HOSTAPD'
                CONFIG='$CONFIG'
                PID='$PID'
                LOG='$LOG'
                DHCP_START='$DHCP_START'
                DHCP_END='$DHCP_END'
                [ -x "${'$'}HOSTAPD" ] || { echo "Missing ${'$'}HOSTAPD"; exit 10; }
                [ -f "${'$'}CONFIG" ] || { echo "Missing ${'$'}CONFIG"; exit 11; }
                LIBDIR=/data/data/com.termux/files/usr/lib
                [ ! -r '$TERMUX_LIBDIR' ] || LIBDIR="${'$'}(cat '$TERMUX_LIBDIR')"
                cleanup() {
                    ndc network route remove local "${'$'}IFACE" '$SUBNET' >/dev/null 2>&1 || true
                    ndc network interface remove local "${'$'}IFACE" >/dev/null 2>&1 || true
                    ndc tether interface remove "${'$'}IFACE" >/dev/null 2>&1 || true
                    if [ -r "${'$'}PID" ]; then
                        HPID="${'$'}(cat "${'$'}PID" 2>/dev/null || true)"
                        [ -z "${'$'}HPID" ] || kill "${'$'}HPID" >/dev/null 2>&1 || true
                    fi
                    rm -f "${'$'}PID"
                    iw dev "${'$'}IFACE" del >/dev/null 2>&1 || true
                }
                trap 'cleanup' EXIT
                HOSTAPD_RUNNING=0
                if [ -r "${'$'}PID" ]; then
                    HPID="${'$'}(cat "${'$'}PID" 2>/dev/null || true)"
                    if [ -n "${'$'}HPID" ] && kill -0 "${'$'}HPID" >/dev/null 2>&1 && ip link show "${'$'}IFACE" >/dev/null 2>&1; then
                        HOSTAPD_RUNNING=1
                    else
                        cleanup
                    fi
                fi
                if [ "${'$'}HOSTAPD_RUNNING" -eq 0 ]; then
                    iw dev "${'$'}IFACE" del >/dev/null 2>&1 || true
                    iw phy phy0 interface add "${'$'}IFACE" type __ap
                    ip link set "${'$'}IFACE" down >/dev/null 2>&1 || true
                    ip link set "${'$'}IFACE" address 6a:66:77:88:99:a8
                    : > "${'$'}LOG"
                    LD_LIBRARY_PATH="${'$'}LIBDIR" "${'$'}HOSTAPD" -B -P "${'$'}PID" -f "${'$'}LOG" -dd -t "${'$'}CONFIG"
                    HPID="${'$'}(cat "${'$'}PID")"
                    kill -0 "${'$'}HPID"
                fi
                ndc interface setcfg "${'$'}IFACE" '$GATEWAY' 24 up >/dev/null
                ndc tether start "${'$'}DHCP_START" "${'$'}DHCP_END" >/dev/null 2>&1 || true
                if ! ndc tether interface add "${'$'}IFACE" >/dev/null 2>&1; then
                    ndc tether interface list 2>/dev/null | grep -qw "${'$'}IFACE" || {
                        echo "Failed to add ${'$'}IFACE to legacy tethering"
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
                    echo "Legacy tether dnsmasq does not own ${'$'}DHCP_START-${'$'}DHCP_END"
                    exit 14
                }
                trap - EXIT
            """.trimIndent())
        }
    }

    @Parcelize
    class Stop : RootCommandNoResult {
        override suspend fun execute() = null.also {
            shell("""
                IFACE='$IFACE'
                PID='$PID'
                ndc network route remove local "${'$'}IFACE" '$SUBNET' >/dev/null 2>&1 || true
                ndc network interface remove local "${'$'}IFACE" >/dev/null 2>&1 || true
                ndc tether interface remove "${'$'}IFACE" >/dev/null 2>&1 || true
                if [ -r "${'$'}PID" ]; then
                    HPID="${'$'}(cat "${'$'}PID" 2>/dev/null || true)"
                    [ -z "${'$'}HPID" ] || kill "${'$'}HPID" >/dev/null 2>&1 || true
                fi
                rm -f "${'$'}PID"
                iw dev "${'$'}IFACE" del >/dev/null 2>&1 || true
            """.trimIndent())
        }
    }
}
