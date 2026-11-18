package com.example.core.crypto

import com.example.domain.model.TotpAccount
import com.example.domain.model.TotpAlgorithm
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

data class TotpCodeState(
    val code: String,
    val nextCode: String,
    val remainingSeconds: Int,
    val periodSeconds: Int,
    val progressFraction: Float
)

/**
 * RFC 6238 (TOTP) and RFC 4226 (HOTP) compliant authenticator engine.
 * Operates completely offline using standard JCA HMAC primitives.
 */
object TotpGenerator {

    fun generateCodeForTime(
        secretBase32: String,
        timeSeconds: Long,
        periodSeconds: Int = 30,
        digits: Int = 6,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    ): String {
        val keyBytes = Base32.decode(secretBase32)
        return generateCodeFromRawSecret(
            secretBytes = keyBytes,
            timeSeconds = timeSeconds,
            periodSeconds = periodSeconds,
            digits = digits,
            algorithm = algorithm
        )
    }

    fun generateCodeFromRawSecret(
        secretBytes: ByteArray,
        timeSeconds: Long,
        periodSeconds: Int = 30,
        digits: Int = 6,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    ): String {
        require(secretBytes.isNotEmpty()) { "TOTP secret cannot be empty" }
        require(periodSeconds > 0) { "TOTP period must be positive" }
        require(digits == 6 || digits == 8) { "TOTP digits must be 6 or 8" }

        val counter = Math.floorDiv(timeSeconds, periodSeconds.toLong())
        return generateHotp(secretBytes, counter, digits, algorithm)
    }

    fun generateHotp(
        secretBytes: ByteArray,
        counter: Long,
        digits: Int = 6,
        algorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    ): String {
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance(algorithm.hmacName)
        mac.init(SecretKeySpec(secretBytes, algorithm.hmacName))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val modulo = 10.0.pow(digits).toInt()
        val otp = binary % modulo
        return otp.toString().padStart(digits, '0')
    }

    fun computeState(account: TotpAccount, nowMillis: Long = System.currentTimeMillis()): TotpCodeState {
        val period = account.periodSeconds.coerceAtLeast(1)
        val nowSeconds = Math.floorDiv(nowMillis, 1000L)
        val elapsedInPeriod = Math.floorMod(nowSeconds, period.toLong()).toInt()
        val remaining = period - elapsedInPeriod
        val elapsedMillisInPeriod = Math.floorMod(nowMillis, period * 1000L)
        val progress = 1f - (elapsedMillisInPeriod.toFloat() / (period * 1000f))

        val currentCode = generateCodeForTime(
            secretBase32 = account.secretBase32,
            timeSeconds = nowSeconds,
            periodSeconds = period,
            digits = account.digits,
            algorithm = account.algorithm
        )
        val nextCode = generateCodeForTime(
            secretBase32 = account.secretBase32,
            timeSeconds = nowSeconds + period,
            periodSeconds = period,
            digits = account.digits,
            algorithm = account.algorithm
        )
        return TotpCodeState(
            code = currentCode,
            nextCode = nextCode,
            remainingSeconds = remaining,
            periodSeconds = period,
            progressFraction = progress.coerceIn(0f, 1f)
        )
    }

    fun formatCode(code: String, groupDigits: Boolean): String {
        if (!groupDigits) return code
        return when (code.length) {
            6 -> "${code.substring(0, 3)} ${code.substring(3, 6)}"
            8 -> "${code.substring(0, 4)} ${code.substring(4, 8)}"
            else -> code
        }
    }
}
