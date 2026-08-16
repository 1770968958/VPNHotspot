package be.mygod.vpnhotspot.enterprise

import android.os.Build
import android.os.Process
import be.mygod.vpnhotspot.App.Companion.app
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** APK-owned, device-verified hostapd runtime for arm64-v8a. */
object EnterpriseBundledRuntime {
    const val VERSION = "mi9-20260816-6fb878d958fc0996"
    private const val ASSET = "enterprise/arm64-v8a/mi9-hostapd-runtime.tar.gz"
    private const val MARKER = ".ready"

    private val expectedSha256 = linkedMapOf(
        "hostapd" to "7ae509ac0af1425660d25f37f019a5b79527eb0188e46cfdbe49434a45309583",
        "lib/libcrypto.so.3" to "4122f5c95088f11a43c14e49e257cc1ce700960e855840cf9b9f39a8acbcd7e8",
        "lib/libnl-3.so" to "0f5d4420927ff57e7ce3b99c3629add6794064adde920970199d2e0967cf83a2",
        "lib/libnl-genl-3.so" to "69bb2aad5baff6542f6c47cfccfa7e3a612fd57aaa2e68b1802f70989fc369b2",
        "lib/libssl.so.3" to "9bf626fadf689237cf641bf1108057f0cc87c587c17fbdeaa16bd4a12807d7ce",
    )

    fun supported(): Boolean = Process.is64Bit() && Build.SUPPORTED_ABIS.contains("arm64-v8a")

    @Synchronized
    fun ensureStaged(): File {
        check(supported()) { "Bundled Enterprise hostapd runtime currently supports arm64-v8a only" }
        val base = File(app.deviceStorage.filesDir, "enterprise-ap/bundled-runtime")
        val target = File(base, VERSION)
        val marker = File(target, MARKER)
        if (marker.readTextOrNull() == VERSION && expectedSha256.keys.all { File(target, it).isFile }) return target

        check(base.mkdirs() || base.isDirectory) { "Unable to create Enterprise bundled runtime directory: $base" }
        val temporary = File(base, ".${VERSION}.${System.nanoTime()}.tmp")
        temporary.deleteRecursively()
        check(temporary.mkdirs()) { "Unable to create Enterprise bundled runtime staging directory: $temporary" }
        try {
            app.assets.open(ASSET).use { raw -> GZIPInputStream(raw).use { extractTar(it, temporary) } }
            check(expectedSha256.keys.all { File(temporary, it).isFile }) { "Bundled Enterprise runtime is incomplete" }
            File(temporary, MARKER).writeText(VERSION)
            target.deleteRecursively()
            check(temporary.renameTo(target)) { "Unable to publish Enterprise bundled runtime" }
        } finally {
            temporary.deleteRecursively()
        }
        return target
    }

    private fun extractTar(input: InputStream, directory: File) {
        val extracted = mutableSetOf<String>()
        val header = ByteArray(512)
        while (true) {
            val count = input.readBlockOrEof(header)
            if (count == 0 || header.all { it == 0.toByte() }) break
            check(count == header.size) { "Truncated Enterprise runtime tar header" }
            val rawName = header.ascii(0, 100)
            val name = rawName.removePrefix("./")
            val size = header.octal(124, 12)
            val type = header[156].toInt().toChar()
            val expected = expectedSha256[name]
            if (expected != null) {
                check(type == '\u0000' || type == '0') { "Unexpected tar entry type for $name" }
                val output = File(directory, name)
                output.parentFile?.let { parent ->
                    check(parent.mkdirs() || parent.isDirectory) { "Unable to create Enterprise runtime directory: $parent" }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                output.outputStream().use { sink -> input.copyExactly(size, sink, digest) }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expected) { "Bundled Enterprise runtime checksum mismatch for $name" }
                extracted += name
            } else {
                input.skipExactly(size)
            }
            input.skipExactly((512 - size % 512) % 512)
        }
        check(extracted == expectedSha256.keys) {
            "Bundled Enterprise runtime entries missing: ${expectedSha256.keys - extracted}"
        }
    }

    private fun InputStream.readBlockOrEof(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset
            offset += read
        }
        return offset
    }

    private fun InputStream.copyExactly(size: Long, output: java.io.OutputStream, digest: MessageDigest) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Truncated Enterprise runtime tar entry")
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.skipExactly(size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Truncated Enterprise runtime tar entry")
            remaining -= read
        }
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && this[end] != 0.toByte()) end++
        return String(this, offset, end - offset, Charsets.US_ASCII)
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val text = ascii(offset, length).trim().trim('\u0000', ' ')
        return if (text.isEmpty()) 0 else text.toLong(8)
    }

    private fun File.readTextOrNull() = runCatching { takeIf(File::isFile)?.readText()?.trim() }.getOrNull()
}
