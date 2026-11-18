package com.example

import com.example.core.crypto.Base32
import com.example.core.crypto.TotpGenerator
import com.example.domain.model.OtpAuthParseResult
import com.example.domain.model.OtpAuthUriParser
import com.example.domain.model.TotpAccount
import com.example.domain.model.TotpAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Verifies RFC 6238 Appendix B test vectors, RFC 4226 HOTP, RFC 4648 Base32,
 * and otpauth:// URI parsing for QR & manual account workflows.
 */
class TotpAndUriTest {

    // Official RFC 6238 Appendix B seeds
    private val seedSha1 = "12345678901234567890".toByteArray(StandardCharsets.US_ASCII)
    private val seedSha256 = "12345678901234567890123456789012".toByteArray(StandardCharsets.US_ASCII)
    private val seedSha512 =
        "1234567890123456789012345678901234567890123456789012345678901234".toByteArray(StandardCharsets.US_ASCII)

    @Test
    fun `RFC 6238 official 8-digit test vectors for SHA1 SHA256 SHA512`() {
        data class Vector(val time: Long, val sha1: String, val sha256: String, val sha512: String)
        val vectors = listOf(
            Vector(59L, "94287082", "46119246", "90693936"),
            Vector(1111111109L, "07081804", "68084774", "25091201"),
            Vector(1111111111L, "14050471", "67062674", "99943326"),
            Vector(1234567890L, "89005924", "91819424", "93441116"),
            Vector(2000000000L, "69279037", "90698825", "38618901"),
            Vector(20000000000L, "65353130", "77737706", "47863826")
        )

        for (v in vectors) {
            assertEquals(
                "SHA1 mismatch at ${v.time}",
                v.sha1,
                TotpGenerator.generateCodeFromRawSecret(seedSha1, v.time, 30, 8, TotpAlgorithm.SHA1)
            )
            assertEquals(
                "SHA256 mismatch at ${v.time}",
                v.sha256,
                TotpGenerator.generateCodeFromRawSecret(seedSha256, v.time, 30, 8, TotpAlgorithm.SHA256)
            )
            assertEquals(
                "SHA512 mismatch at ${v.time}",
                v.sha512,
                TotpGenerator.generateCodeFromRawSecret(seedSha512, v.time, 30, 8, TotpAlgorithm.SHA512)
            )
        }
    }

    @Test
    fun `6-digit and 60-second period TOTP generation works offline`() {
        val secret = Base32.encode(seedSha1) // GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        val code6 = TotpGenerator.generateCodeForTime(secret, 59L, periodSeconds = 30, digits = 6)
        assertEquals("287082", code6)

        val code60s = TotpGenerator.generateCodeForTime(secret, 100L, periodSeconds = 60, digits = 6)
        val code60sSameWindow = TotpGenerator.generateCodeForTime(secret, 119L, periodSeconds = 60, digits = 6)
        assertEquals(code60s, code60sSameWindow)
    }

    @Test
    fun `Base32 normalization and validation rejects bad secrets`() {
        assertTrue(Base32.isValid("jbsw y3dp ehpk 3pxp"))
        assertEquals("JBSWY3DPEHPK3PXP", Base32.normalize("jbsw-y3dp ehpk 3pxp="))
        assertFalse(Base32.isValid("INVALID_1089"))
        assertFalse(Base32.isValid("ABC"))
    }

    @Test
    fun `otpauth URI parser extracts issuer account secret digits period and algorithm`() {
        val uri = "otpauth://totp/GitHub:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=60"
        val res = OtpAuthUriParser.parse(uri)
        assertTrue(res is OtpAuthParseResult.Success)
        val acc = (res as OtpAuthParseResult.Success).account
        assertEquals("GitHub", acc.issuer)
        assertEquals("alice@example.com", acc.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", acc.secretBase32)
        assertEquals(TotpAlgorithm.SHA256, acc.algorithm)
        assertEquals(8, acc.digits)
        assertEquals(60, acc.periodSeconds)

        // Roundtrip URI serialization
        val roundtrip = OtpAuthUriParser.parse(acc.toOtpAuthUri())
        assertTrue(roundtrip is OtpAuthParseResult.Success)
        assertEquals(acc.secretBase32, (roundtrip as OtpAuthParseResult.Success).account.secretBase32)
    }

    @Test
    fun `otpauth URI parser rejects migration QR and malformed URIs cleanly`() {
        val migration = OtpAuthUriParser.parse("otpauth-migration://offline?data=abc")
        assertTrue(migration is OtpAuthParseResult.Error)

        val missingSecret = OtpAuthUriParser.parse("otpauth://totp/Google:a@b.com?issuer=Google")
        assertTrue(missingSecret is OtpAuthParseResult.Error)
    }

    @Test
    fun `countdown state tracks remaining seconds accurately`() {
        val account = TotpAccount(
            issuer = "Test",
            accountName = "user",
            secretBase32 = "JBSWY3DPEHPK3PXP",
            periodSeconds = 30
        )
        val state = TotpGenerator.computeState(account, nowMillis = 25_000L)
        assertEquals(5, state.remainingSeconds)
        assertEquals(6, state.code.length)
    }
}
