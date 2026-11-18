package com.example.domain.model

import com.example.core.crypto.Base32
import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
enum class TotpAlgorithm(val hmacName: String, val displayName: String) {
    SHA1("HmacSHA1", "SHA-1"),
    SHA256("HmacSHA256", "SHA-256"),
    SHA512("HmacSHA512", "SHA-512");

    companion object {
        fun fromUriParam(raw: String?): TotpAlgorithm {
            return when (raw?.trim()?.uppercase()?.replace("-", "")) {
                "SHA256" -> SHA256
                "SHA512" -> SHA512
                else -> SHA1
            }
        }
    }
}

@Serializable
data class TotpAccount(
    val id: String = UUID.randomUUID().toString(),
    val issuer: String = "",
    val accountName: String = "",
    val secretBase32: String = "",
    val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    val digits: Int = 6,
    val periodSeconds: Int = 30,
    val sortOrder: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun displayIssuer(): String = issuer.ifBlank { "Authenticator" }

    fun toOtpAuthUri(): String {
        val label = if (issuer.isNotBlank()) {
            "${urlEncode(issuer)}:${urlEncode(accountName)}"
        } else {
            urlEncode(accountName)
        }
        val params = buildList {
            add("secret=${Base32.normalize(secretBase32)}")
            if (issuer.isNotBlank()) add("issuer=${urlEncode(issuer)}")
            add("algorithm=${algorithm.name}")
            add("digits=$digits")
            add("period=$periodSeconds")
        }.joinToString("&")
        return "otpauth://totp/$label?$params"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

@Serializable
data class DeletedAccountRecord(
    val id: String,
    val deletedAtMs: Long
)

@Serializable
data class VaultContent(
    val schemaVersion: Int = 1,
    val accounts: List<TotpAccount> = emptyList(),
    val deletedAccounts: List<DeletedAccountRecord> = emptyList()
)

sealed interface OtpAuthParseResult {
    data class Success(val account: TotpAccount) : OtpAuthParseResult
    data class Error(val message: String) : OtpAuthParseResult
}

object OtpAuthUriParser {
    fun parse(rawUri: String, nextSortOrder: Int = 0): OtpAuthParseResult {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) {
            return OtpAuthParseResult.Error("URI cannot be empty.")
        }
        if (trimmed.startsWith("otpauth-migration://", ignoreCase = true)) {
            return OtpAuthParseResult.Error(
                "Google Authenticator batch migration QR detected. Export individual QR codes or setup keys to import."
            )
        }
        if (!trimmed.startsWith("otpauth://", ignoreCase = true)) {
            return OtpAuthParseResult.Error("Invalid URI. Must start with otpauth://totp/.")
        }

        val withoutScheme = trimmed.substringAfter("://")
        val type = withoutScheme.substringBefore("/").lowercase()
        if (type == "hotp") {
            return OtpAuthParseResult.Error("Counter-based HOTP is not supported; only time-based TOTP is supported.")
        }
        if (type != "totp") {
            return OtpAuthParseResult.Error("Unsupported OTP type '$type'. Expected 'totp'.")
        }

        val afterType = withoutScheme.substringAfter("/", "")
        val rawLabel = afterType.substringBefore("?")
        val queryString = afterType.substringAfter("?", "")
        if (queryString.isEmpty()) {
            return OtpAuthParseResult.Error("Missing query parameters in otpauth:// URI.")
        }

        val params = mutableMapOf<String, String>()
        for (pair in queryString.split("&")) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore("=").lowercase()
            val value = urlDecode(pair.substringAfter("=", ""))
            params[key] = value
        }

        val rawSecret = params["secret"].orEmpty()
        val normalizedSecret = Base32.normalize(rawSecret)
        if (!Base32.isValid(normalizedSecret)) {
            return OtpAuthParseResult.Error("Invalid or missing Base32 secret key in URI.")
        }

        val decodedLabel = urlDecode(rawLabel)
        val labelIssuer: String
        val labelAccount: String
        if (decodedLabel.contains(":")) {
            labelIssuer = decodedLabel.substringBefore(":").trim()
            labelAccount = decodedLabel.substringAfter(":").trim()
        } else {
            labelIssuer = ""
            labelAccount = decodedLabel.trim()
        }

        val paramIssuer = params["issuer"]?.trim().orEmpty()
        val issuer = paramIssuer.ifBlank { labelIssuer }.ifBlank { "Unknown Service" }
        val accountName = labelAccount.ifBlank { "Account" }

        val digits = params["digits"]?.toIntOrNull() ?: 6
        if (digits != 6 && digits != 8) {
            return OtpAuthParseResult.Error("Unsupported digit length '$digits'. Only 6 or 8 digits are supported.")
        }

        val period = params["period"]?.toIntOrNull() ?: 30
        if (period != 30 && period != 60) {
            return OtpAuthParseResult.Error("Unsupported period '${period}s'. Only 30s or 60s periods are supported.")
        }

        val algorithm = TotpAlgorithm.fromUriParam(params["algorithm"])
        val now = System.currentTimeMillis()
        return OtpAuthParseResult.Success(
            TotpAccount(
                id = UUID.randomUUID().toString(),
                issuer = issuer,
                accountName = accountName,
                secretBase32 = normalizedSecret,
                algorithm = algorithm,
                digits = digits,
                periodSeconds = period,
                sortOrder = nextSortOrder,
                createdAtMs = now,
                updatedAtMs = now
            )
        )
    }

    private fun urlDecode(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
}
