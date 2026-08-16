package be.mygod.vpnhotspot.root

import android.os.RemoteException
import be.mygod.librootkotlinx.RootCommandNoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

/**
 * Transitional installer for the APK-bundled hostapd runtime.
 *
 * It materializes the verified APK asset into the legacy runtime location consumed by EnterpriseApCommands. Once
 * the bundled runtime passes device validation, EnterpriseApCommands can drop all legacy/Termux discovery in one
 * follow-up without changing UI/profile/routing layers.
 */
object EnterpriseBundledRuntimeInstaller {
    private const val DESTINATION = "/data/local/tmp/mi9-enterprise"
    private val SAFE_PATH = Regex("^/[A-Za-z0-9_./-]+$")
    private val SAFE_VERSION = Regex("^[A-Za-z0-9._-]{1,80}$")

    private suspend fun shell(script: String) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw RemoteException("Enterprise bundled runtime install exited with $exit: $output")
    }

    @Parcelize
    data class Install(val sourceDirectory: String, val version: String) : RootCommandNoResult {
        override suspend fun execute() = null.also {
            require(SAFE_PATH.matches(sourceDirectory)) { "Invalid Enterprise bundled runtime source" }
            require(SAFE_VERSION.matches(version)) { "Invalid Enterprise bundled runtime version" }
            shell("""
                set -eu
                SRC='$sourceDirectory'
                DST='$DESTINATION'
                VERSION='$version'
                TMP="${'$'}DST/.apk-runtime-${'$'}VERSION.${'$'}${'$'}"
                cleanup() { rm -rf "${'$'}TMP"; }
                trap 'cleanup' EXIT
                for F in hostapd lib/libcrypto.so.3 lib/libnl-3.so lib/libnl-genl-3.so lib/libssl.so.3; do
                    [ -r "${'$'}SRC/${'$'}F" ] || { echo "Bundled runtime missing ${'$'}F"; exit 20; }
                done
                mkdir -p "${'$'}DST"
                if [ -f "${'$'}DST/apk_runtime_version" ] && [ "${'$'}(cat "${'$'}DST/apk_runtime_version" 2>/dev/null || true)" = "${'$'}VERSION" ] && [ -x "${'$'}DST/hostapd" ]; then
                    exit 0
                fi
                rm -rf "${'$'}TMP"
                mkdir -p "${'$'}TMP/lib"
                cp "${'$'}SRC/hostapd" "${'$'}TMP/hostapd"
                cp "${'$'}SRC/lib/libcrypto.so.3" "${'$'}TMP/lib/libcrypto.so.3"
                cp "${'$'}SRC/lib/libnl-3.so" "${'$'}TMP/lib/libnl-3.so"
                cp "${'$'}SRC/lib/libnl-genl-3.so" "${'$'}TMP/lib/libnl-genl-3.so"
                cp "${'$'}SRC/lib/libssl.so.3" "${'$'}TMP/lib/libssl.so.3"
                chmod 755 "${'$'}TMP/hostapd" "${'$'}TMP/lib"
                chmod 644 "${'$'}TMP/lib/"*
                mkdir -p "${'$'}DST/lib"
                mv -f "${'$'}TMP/hostapd" "${'$'}DST/hostapd"
                for F in libcrypto.so.3 libnl-3.so libnl-genl-3.so libssl.so.3; do
                    mv -f "${'$'}TMP/lib/${'$'}F" "${'$'}DST/lib/${'$'}F"
                done
                printf '%s\n' "${'$'}VERSION" > "${'$'}DST/apk_runtime_version"
                chmod 755 "${'$'}DST/hostapd" "${'$'}DST/lib"
                chmod 644 "${'$'}DST/lib/"* "${'$'}DST/apk_runtime_version"
                trap - EXIT
                cleanup
            """.trimIndent())
        }
    }
}
