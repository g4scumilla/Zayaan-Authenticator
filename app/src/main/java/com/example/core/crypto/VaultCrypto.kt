package com.example.core.crypto

import com.example.domain.model.VaultContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class EncryptedExportEnvelope(
    val format: String = "zayan-authenticator-encrypted-export",
    val encryptionVersion: Int = 1,
    val kdfAlgorithm: String = VaultCrypto.KDF_ALGORITHM,
    val kdfIterations: Int = VaultCrypto.DEFAULT_KDF_ITERATIONS,
    val kdfSalt: String,
    val iv: String,
    val ciphertext: String,
    val exportedAtMs: Long
)

data class WrappedKeyPackage(
    val wrappedVekBase64: String,
    val wrapIvBase64: String,
    val kdfSaltBase64: String,
    val kdfIterations: Int
)

data class EncryptedPayloadPackage(
    val encryptedPayloadBase64: String,
    val vaultIvBase64: String
)

class InvalidRecoveryPasswordException(
    message: String = "Incorrect recovery password or corrupted vault data.",
    cause: Throwable? = null
) : Exception(message, cause)

class EncryptionDowngradeException(message: String) : Exception(message)

/**
 * Zero-knowledge client-side cryptographic engine:
 * - AES-256-GCM authenticated encryption with 96-bit (12-byte) random IVs and 128-bit auth tags.
 * - PBKDF2-HMAC-SHA256 with >= 600,000 iterations and 128-bit random salt.
 * - Key hierarchy: Recovery Password -> KEK -> wraps random 256-bit VEK -> encrypts VaultContent.
 */
object VaultCrypto {
    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    const val DEFAULT_KDF_ITERATIONS = 600_000
    const val MIN_KDF_ITERATIONS = 600_000
    const val ENCRYPTION_VERSION = 1
    const val MIN_RECOVERY_PASSWORD_LENGTH = 12

    private const val AES_KEY_BITS = 256
    private const val AES_KEY_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16

    private val secureRandom = SecureRandom()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun generateVaultKey(): ByteArray {
        val key = ByteArray(AES_KEY_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    fun generateIv(): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES)
        secureRandom.nextBytes(iv)
        return iv
    }

    fun deriveKek(
        recoveryPassword: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_KDF_ITERATIONS
    ): SecretKey {
        if (iterations < MIN_KDF_ITERATIONS) {
            throw EncryptionDowngradeException("Refusing weak KDF iteration count: $iterations")
        }
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val spec = PBEKeySpec(recoveryPassword, salt, iterations, AES_KEY_BITS)
        return try {
            val secret = factory.generateSecret(spec)
            SecretKeySpec(secret.encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun wrapVek(
        vekBytes: ByteArray,
        recoveryPassword: String,
        uid: String,
        iterations: Int = DEFAULT_KDF_ITERATIONS
    ): WrappedKeyPackage {
        val salt = generateSalt()
        val pwdChars = recoveryPassword.toCharArray()
        val kek = deriveKek(pwdChars, salt, iterations)
        pwdChars.fill('\u0000')

        val iv = generateIv()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD("zayan-vek-v1:$uid".toByteArray(StandardCharsets.UTF_8))
        val wrapped = cipher.doFinal(vekBytes)

        return WrappedKeyPackage(
            wrappedVekBase64 = Base64.getEncoder().encodeToString(wrapped),
            wrapIvBase64 = Base64.getEncoder().encodeToString(iv),
            kdfSaltBase64 = Base64.getEncoder().encodeToString(salt),
            kdfIterations = iterations
        )
    }

    fun unwrapVek(
        wrappedVekBase64: String,
        wrapIvBase64: String,
        kdfSaltBase64: String,
        kdfAlgorithm: String,
        kdfIterations: Int,
        encryptionVersion: Int,
        recoveryPassword: String,
        uid: String
    ): ByteArray {
        if (encryptionVersion != ENCRYPTION_VERSION) {
            throw EncryptionDowngradeException("Unsupported encryption version: $encryptionVersion")
        }
        if (kdfAlgorithm != KDF_ALGORITHM) {
            throw EncryptionDowngradeException("Unsupported KDF algorithm: $kdfAlgorithm")
        }
        if (kdfIterations < MIN_KDF_ITERATIONS) {
            throw EncryptionDowngradeException("Refusing downgraded KDF iterations: $kdfIterations")
        }

        val salt = Base64.getDecoder().decode(kdfSaltBase64)
        val iv = Base64.getDecoder().decode(wrapIvBase64)
        val wrapped = Base64.getDecoder().decode(wrappedVekBase64)

        val pwdChars = recoveryPassword.toCharArray()
        val kek = deriveKek(pwdChars, salt, kdfIterations)
        pwdChars.fill('\u0000')

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD("zayan-vek-v1:$uid".toByteArray(StandardCharsets.UTF_8))
            val vek = cipher.doFinal(wrapped)
            if (vek.size != AES_KEY_BYTES) {
                throw InvalidRecoveryPasswordException("Invalid decrypted VEK length.")
            }
            vek
        } catch (e: AEADBadTagException) {
            throw InvalidRecoveryPasswordException("Incorrect recovery password.", e)
        } catch (e: Exception) {
            if (e is InvalidRecoveryPasswordException || e is EncryptionDowngradeException) throw e
            throw InvalidRecoveryPasswordException("Failed to decrypt recovery key.", e)
        }
    }

    fun encryptVault(
        vaultContent: VaultContent,
        vekBytes: ByteArray,
        uid: String,
        backupVersion: Long
    ): EncryptedPayloadPackage {
        val plaintext = json.encodeToString(vaultContent).toByteArray(StandardCharsets.UTF_8)
        val iv = generateIv()
        val keySpec = SecretKeySpec(vekBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD("zayan-vault-v1:$uid:v$backupVersion".toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)

        return EncryptedPayloadPackage(
            encryptedPayloadBase64 = Base64.getEncoder().encodeToString(ciphertext),
            vaultIvBase64 = Base64.getEncoder().encodeToString(iv)
        )
    }

    fun decryptVault(
        encryptedPayloadBase64: String,
        vaultIvBase64: String,
        vekBytes: ByteArray,
        uid: String,
        backupVersion: Long
    ): VaultContent {
        val iv = Base64.getDecoder().decode(vaultIvBase64)
        val ciphertext = Base64.getDecoder().decode(encryptedPayloadBase64)
        val keySpec = SecretKeySpec(vekBytes, "AES")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD("zayan-vault-v1:$uid:v$backupVersion".toByteArray(StandardCharsets.UTF_8))
            val plaintext = cipher.doFinal(ciphertext)
            json.decodeFromString<VaultContent>(String(plaintext, StandardCharsets.UTF_8))
        } catch (e: AEADBadTagException) {
            throw InvalidRecoveryPasswordException("Vault integrity check failed.", e)
        }
    }

    fun encryptExportEnvelope(
        vaultContent: VaultContent,
        passphrase: String
    ): String {
        val salt = generateSalt()
        val iv = generateIv()
        val pwdChars = passphrase.toCharArray()
        val key = deriveKek(pwdChars, salt, DEFAULT_KDF_ITERATIONS)
        pwdChars.fill('\u0000')

        val plaintext = json.encodeToString(vaultContent).toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD("zayan-export-v1".toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)

        val envelope = EncryptedExportEnvelope(
            kdfSalt = Base64.getEncoder().encodeToString(salt),
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            exportedAtMs = System.currentTimeMillis()
        )
        return json.encodeToString(envelope)
    }

    fun decryptExportEnvelope(
        rawJson: String,
        passphrase: String
    ): VaultContent {
        val envelope = json.decodeFromString<EncryptedExportEnvelope>(rawJson)
        if (envelope.encryptionVersion != ENCRYPTION_VERSION ||
            envelope.kdfAlgorithm != KDF_ALGORITHM ||
            envelope.kdfIterations < MIN_KDF_ITERATIONS
        ) {
            throw EncryptionDowngradeException("Unsupported or weak export encryption parameters.")
        }

        val salt = Base64.getDecoder().decode(envelope.kdfSalt)
        val iv = Base64.getDecoder().decode(envelope.iv)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertext)
        val pwdChars = passphrase.toCharArray()
        val key = deriveKek(pwdChars, salt, envelope.kdfIterations)
        pwdChars.fill('\u0000')

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD("zayan-export-v1".toByteArray(StandardCharsets.UTF_8))
            val plaintext = cipher.doFinal(ciphertext)
            json.decodeFromString<VaultContent>(String(plaintext, StandardCharsets.UTF_8))
        } catch (e: AEADBadTagException) {
            throw InvalidRecoveryPasswordException("Incorrect export passphrase.", e)
        }
    }

    fun evaluatePasswordStrength(password: String): Int {
        var score = 0
        if (password.length >= 12) score += 1
        if (password.length >= 16) score += 1
        if (password.any { it.isUpperCase() } && password.any { it.isLowerCase() }) score += 1
        if (password.any { it.isDigit() }) score += 1
        if (password.any { !it.isLetterOrDigit() }) score += 1
        return score.coerceIn(0, 5)
    }
}
