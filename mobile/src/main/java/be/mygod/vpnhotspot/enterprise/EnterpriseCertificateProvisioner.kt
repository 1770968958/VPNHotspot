package be.mygod.vpnhotspot.enterprise

import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Small dependency-free X.509 provisioner for the integrated hostapd EAP server.
 *
 * Android Keystore private keys cannot be exported to hostapd, while adding a full PKI library solely for two
 * certificates would significantly increase the APK. This class therefore emits only the DER structures we need:
 * a self-signed RSA CA and one RSA TLS server certificate carrying serverAuth and a DNS SAN.
 */
object EnterpriseCertificateProvisioner {
    data class Files(
        val caCertificate: File,
        val serverCertificate: File,
        val serverPrivateKey: File,
    )

    private const val CA_COMMON_NAME = "VPN Hotspot Enterprise Root"
    private const val MARKER_VERSION = "enterprise-pki-v1"
    private val random = SecureRandom()
    private val certificateFactory by lazy { CertificateFactory.getInstance("X.509") }

    fun ensure(directory: File, serverIdentity: String): Files {
        requireDnsName(serverIdentity)
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create Enterprise PKI directory: $directory" }
        val caFile = File(directory, "ca.pem")
        val serverFile = File(directory, "server.pem")
        val serverKeyFile = File(directory, "server.key")
        val markerFile = File(directory, "identity")
        val result = Files(caFile, serverFile, serverKeyFile)
        val marker = "$MARKER_VERSION\n$serverIdentity\n"
        if (markerFile.takeIf(File::isFile)?.readText() == marker && existingBundleValid(result)) return result

        val now = System.currentTimeMillis()
        val ca = generateRsa()
        val caDer = issueCertificate(
            subjectCommonName = CA_COMMON_NAME,
            issuerCommonName = CA_COMMON_NAME,
            publicKey = ca.public,
            issuerPrivateKey = ca.private,
            notBefore = Date(now - TimeUnit.DAYS.toMillis(1)),
            notAfter = Date(now + TimeUnit.DAYS.toMillis(3650)),
            isCa = true,
            dnsName = null,
        )
        val server = generateRsa()
        val serverDer = issueCertificate(
            subjectCommonName = serverIdentity,
            issuerCommonName = CA_COMMON_NAME,
            publicKey = server.public,
            issuerPrivateKey = ca.private,
            notBefore = Date(now - TimeUnit.DAYS.toMillis(1)),
            notAfter = Date(now + TimeUnit.DAYS.toMillis(825)),
            isCa = false,
            dnsName = serverIdentity,
        )

        // Parse and verify before publishing anything to the runtime directory.
        val caCertificate = parseCertificate(caDer)
        val serverCertificate = parseCertificate(serverDer)
        caCertificate.verify(caCertificate.publicKey)
        serverCertificate.verify(caCertificate.publicKey)
        caCertificate.checkValidity()
        serverCertificate.checkValidity()

        writeAtomic(caFile, pem("CERTIFICATE", caDer))
        writeAtomic(serverFile, pem("CERTIFICATE", serverDer))
        writeAtomic(serverKeyFile, pem("PRIVATE KEY", server.private.encoded))
        writeAtomic(markerFile, marker)
        return result
    }

    private fun existingBundleValid(files: Files): Boolean = try {
        if (!files.caCertificate.isFile || !files.serverCertificate.isFile || !files.serverPrivateKey.isFile) return false
        val ca = parseCertificate(readPem(files.caCertificate))
        val server = parseCertificate(readPem(files.serverCertificate))
        ca.verify(ca.publicKey)
        server.verify(ca.publicKey)
        val minimumLifetime = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(30))
        ca.checkValidity(minimumLifetime)
        server.checkValidity(minimumLifetime)
        readPem(files.serverPrivateKey).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    private fun generateRsa(): KeyPair = KeyPairGenerator.getInstance("RSA").run {
        initialize(2048, random)
        generateKeyPair()
    }

    private fun issueCertificate(
        subjectCommonName: String,
        issuerCommonName: String,
        publicKey: PublicKey,
        issuerPrivateKey: PrivateKey,
        notBefore: Date,
        notAfter: Date,
        isCa: Boolean,
        dnsName: String?,
    ): ByteArray {
        val serialBytes = ByteArray(16).also(random::nextBytes)
        val serial = BigInteger(1, serialBytes).max(BigInteger.ONE)
        val signatureAlgorithm = algorithmIdentifier(OID_SHA256_WITH_RSA)
        val extensions = if (isCa) arrayOf(
            extension(OID_BASIC_CONSTRAINTS, critical = true, value = sequence(boolean(true))),
            extension(OID_KEY_USAGE, critical = true, value = bitString(byteArrayOf(0x06), unusedBits = 1)),
        ) else arrayOf(
            extension(OID_BASIC_CONSTRAINTS, critical = true, value = sequence()),
            extension(OID_KEY_USAGE, critical = true, value = bitString(byteArrayOf(0xA0.toByte()), unusedBits = 5)),
            extension(OID_EXTENDED_KEY_USAGE, value = sequence(oid(OID_SERVER_AUTH))),
            extension(OID_SUBJECT_ALT_NAME, value = sequence(tag(0x82, dnsName!!.toByteArray(Charsets.US_ASCII)))),
        )
        val tbs = sequence(
            explicit(0, integer(BigInteger.valueOf(2))),
            integer(serial),
            signatureAlgorithm,
            name(issuerCommonName),
            sequence(time(notBefore), time(notAfter)),
            name(subjectCommonName),
            publicKey.encoded,
            explicit(3, sequence(*extensions)),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(issuerPrivateKey, random)
            update(tbs)
            sign()
        }
        return sequence(tbs, signatureAlgorithm, bitString(signature))
    }

    private fun requireDnsName(value: String) {
        require(value.length in 1..253) { "Server identity must be a DNS name" }
        val labels = value.split('.')
        require(labels.all { label ->
            label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                    label.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }) { "Server identity must be an ASCII DNS name" }
    }

    private fun parseCertificate(der: ByteArray): X509Certificate =
        certificateFactory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun readPem(file: File): ByteArray {
        val payload = file.readLines().filterNot { it.startsWith("-----") }.joinToString("")
        return Base64.getDecoder().decode(payload)
    }

    private fun pem(type: String, der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
    }

    private fun writeAtomic(file: File, text: String) {
        val parent = file.parentFile
        check(parent == null || parent.mkdirs() || parent.isDirectory) { "Unable to create directory for $file" }
        val temp = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temp.writeText(text)
            if (!temp.renameTo(file)) temp.copyTo(file, overwrite = true)
        } finally {
            temp.delete()
        }
    }

    private fun name(commonName: String) = sequence(set(sequence(oid(OID_COMMON_NAME), utf8(commonName))))

    private fun extension(oid: IntArray, critical: Boolean = false, value: ByteArray): ByteArray = sequence(
        oid(oid),
        *if (critical) arrayOf(boolean(true), octetString(value)) else arrayOf(octetString(value)),
    )

    private fun algorithmIdentifier(oid: IntArray) = sequence(oid(oid), nullValue())

    private fun sequence(vararg values: ByteArray) = tag(0x30, concatenate(values))
    private fun set(vararg values: ByteArray) = tag(0x31, concatenate(values))
    private fun explicit(number: Int, value: ByteArray) = tag(0xA0 + number, value)
    private fun integer(value: BigInteger) = tag(0x02, value.toByteArray())
    private fun utf8(value: String) = tag(0x0C, value.toByteArray(Charsets.UTF_8))
    private fun boolean(value: Boolean) = tag(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0.toByte()))
    private fun nullValue() = tag(0x05, byteArrayOf())
    private fun octetString(value: ByteArray) = tag(0x04, value)
    private fun bitString(value: ByteArray, unusedBits: Int = 0) =
        tag(0x03, byteArrayOf(unusedBits.toByte()) + value)

    private fun time(value: Date): ByteArray {
        val calendar = Calendar.getInstance(UTC).apply { time = value }
        val year = calendar.get(Calendar.YEAR)
        val pattern = if (year in 1950..2049) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'"
        val tag = if (year in 1950..2049) 0x17 else 0x18
        return tag(tag, SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }.format(value).toByteArray())
    }

    private fun oid(parts: IntArray): ByteArray {
        require(parts.size >= 2 && parts[0] in 0..2 && parts[1] >= 0)
        val body = ArrayList<Byte>()
        appendBase128(body, parts[0].toLong() * 40 + parts[1])
        for (index in 2 until parts.size) appendBase128(body, parts[index].toLong())
        return tag(0x06, body.toByteArray())
    }

    private fun appendBase128(output: MutableList<Byte>, value: Long) {
        require(value >= 0)
        var current = value
        val bytes = ArrayList<Byte>()
        bytes.add((current and 0x7F).toByte())
        current = current ushr 7
        while (current != 0L) {
            bytes.add(((current and 0x7F) or 0x80).toByte())
            current = current ushr 7
        }
        for (index in bytes.indices.reversed()) output.add(bytes[index])
    }

    private fun tag(tag: Int, value: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + length(value.size) + value

    private fun length(size: Int): ByteArray {
        require(size >= 0)
        if (size < 0x80) return byteArrayOf(size.toByte())
        var value = size
        var count = 0
        while (value != 0) {
            count++
            value = value ushr 8
        }
        return ByteArray(count + 1).also { result ->
            result[0] = (0x80 or count).toByte()
            for (index in 0 until count) result[count - index] = (size ushr (index * 8)).toByte()
        }
    }

    private fun concatenate(values: Array<out ByteArray>): ByteArray {
        val result = ByteArray(values.sumOf(ByteArray::size))
        var offset = 0
        for (value in values) {
            value.copyInto(result, offset)
            offset += value.size
        }
        return result
    }

    private val UTC = TimeZone.getTimeZone("UTC")
    private val OID_COMMON_NAME = intArrayOf(2, 5, 4, 3)
    private val OID_SHA256_WITH_RSA = intArrayOf(1, 2, 840, 113549, 1, 1, 11)
    private val OID_BASIC_CONSTRAINTS = intArrayOf(2, 5, 29, 19)
    private val OID_KEY_USAGE = intArrayOf(2, 5, 29, 15)
    private val OID_EXTENDED_KEY_USAGE = intArrayOf(2, 5, 29, 37)
    private val OID_SUBJECT_ALT_NAME = intArrayOf(2, 5, 29, 17)
    private val OID_SERVER_AUTH = intArrayOf(1, 3, 6, 1, 5, 5, 7, 3, 1)
}
