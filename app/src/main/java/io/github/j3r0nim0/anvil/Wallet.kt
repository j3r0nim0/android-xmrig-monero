package io.github.j3r0nim0.anvil

import org.bouncycastle.jcajce.provider.digest.Keccak
import java.security.MessageDigest

/**
 * Monero wallet address validation.
 *
 * A Monero address is a Base58-encoded binary blob:
 *   [network_byte(1)] [spend_key(32)] [view_key(32)] [+ payment_id(8)] [checksum(4)]
 *
 * Standard / subaddress: 69 bytes  →  95 Base58 chars  (prefix 4 or 8)
 * Integrated:            77 bytes  →  106 Base58 chars (prefix 4 only)
 *
 * Checksum = first 4 bytes of Keccak-256(data_bytes).
 */
object Wallet {

    private const val NETWORK_MAINNET: Byte = 18     // 0x12
    private const val NETWORK_INTEGRATED: Byte = 19  // 0x13
    private const val NETWORK_SUBADDRESS: Byte = 42  // 0x2A

    private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val B58_REV: ByteArray = run {
        val r = ByteArray(128) { -1 }
        for ((i, c) in B58.withIndex()) r[c.code] = i.toByte()
        r
    }

    /** Regex-prefilter: length + first character. */
    private val pattern = Regex("^(?:[48][$B58]{94}|4[$B58]{105})$")
    private val junk = Regex("[\\s\\u200B\\u200C\\u200D\\uFEFF\\u00AD]+")

    // Lazy Keccak-256 instance (thread-safe after init)
    private val keccak256: MessageDigest by lazy { Keccak.Digest256() }

    /**
     * Full validation: regex shape + Base58 decode + Keccak-256 checksum.
     */
    fun isValid(address: String): Boolean {
        val clean = parse(address)

        // Quick shape check first
        if (!pattern.matches(clean)) return false

        return try {
            val raw = b58decode(clean)
            when (raw.size) {
                69 -> checkSum(raw, 65)   // standard / subaddress
                77 -> checkSum(raw, 73)   // integrated
                else -> false             // unexpected length even after decode
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Strips URI scheme, zero-width characters, and other junk.
     * Does NOT validate — use [isValid] for that.
     */
    fun parse(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("monero:", ignoreCase = true)) {
            s = s.substring("monero:".length)
            val q = s.indexOf('?')
            if (q >= 0) s = s.substring(0, q)
        }
        return junk.replace(s, "")
    }

    // ── Base58 ──────────────────────────────────────────────────────────────

    /** Decode a Base58 string to a byte array. Returns empty array on invalid input. */
    private fun b58decode(input: String): ByteArray {
        // Count leading 1s (which encode to 0x00)
        var leading = 0
        for (c in input) {
            if (c != '1') break
            leading++
        }

        // Decode big-endian base-256 value
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (c in input) {
            val idx = if (c.code < 128) B58_REV[c.code].toInt() else -1
            if (idx < 0) return ByteArray(0) // invalid character
            num = num.multiply(base).add(java.math.BigInteger.valueOf(idx.toLong()))
        }

        val encoded = num.toByteArray()
        // BigInt may include a leading 0x00 sign byte — strip it
        val raw = if (encoded.size > 1 && encoded[0].toInt() == 0) {
            encoded.copyOfRange(1, encoded.size)
        } else {
            encoded
        }

        // Prepend leading zero bytes
        return ByteArray(leading) { 0 } + raw
    }

    // ── Checksum ────────────────────────────────────────────────────────────

    /**
     * Verifies that the last 4 bytes of [full] equal the first 4 bytes
     * of Keccak-256([full][0..dataLen)).
     */
    private fun checkSum(full: ByteArray, dataLen: Int): Boolean {
        val data = full.copyOfRange(0, dataLen)
        val expectedChecksum = full.copyOfRange(dataLen, full.size)
        val hash = keccak256.digest(data)
        return hash[0] == expectedChecksum[0] &&
                hash[1] == expectedChecksum[1] &&
                hash[2] == expectedChecksum[2] &&
                hash[3] == expectedChecksum[3]
    }

    /** Visible for testing: compute the checksum for a given data prefix. */
    internal fun computeChecksum(data: ByteArray): ByteArray =
        keccak256.digest(data).copyOfRange(0, 4)
}