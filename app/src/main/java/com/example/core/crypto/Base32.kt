package com.example.core.crypto

/**
 * RFC 4648 Base32 encoder/decoder for TOTP secrets.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val LOOKUP = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, c ->
            this[c.code] = index
            this[c.lowercaseChar().code] = index
        }
    }

    fun normalize(input: String): String =
        input.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("=", "")
            .uppercase()

    fun isValid(input: String): Boolean {
        val clean = normalize(input)
        if (clean.length < 8) return false
        for (ch in clean) {
            if (ch.code >= 128 || LOOKUP[ch.code] == -1) return false
        }
        return decode(clean).isNotEmpty()
    }

    fun decode(input: String): ByteArray {
        val clean = normalize(input)
        if (clean.isEmpty()) return ByteArray(0)

        val out = ByteArray((clean.length * 5) / 8)
        var buffer = 0
        var bitsLeft = 0
        var outIndex = 0

        for (ch in clean) {
            val code = ch.code
            val value = if (code < 128) LOOKUP[code] else -1
            require(value >= 0) { "Invalid Base32 character: $ch" }

            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                if (outIndex < out.size) {
                    out[outIndex++] = ((buffer shr bitsLeft) and 0xFF).toByte()
                }
            }
        }
        return if (outIndex == out.size) out else out.copyOf(outIndex)
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(((data.size * 8) + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }
}
