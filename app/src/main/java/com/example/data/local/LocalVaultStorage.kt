package com.example.data.local

import android.content.Context
import com.example.core.crypto.AndroidKeystoreKeyProvider
import com.example.core.crypto.DeviceKeyProvider
import com.example.domain.model.LocalVaultEnvelope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Encrypts and persists the local vault envelope at rest using Android Keystore AES-256-GCM.
 * Plaintext TOTP secrets and the VEK are never written to disk unencrypted.
 */
class LocalVaultStorage(
    private val storageDir: File,
    private val keyProvider: DeviceKeyProvider = AndroidKeystoreKeyProvider()
) {
    constructor(context: Context) : this(
        storageDir = File(context.applicationContext.filesDir, "zayan_vaults"),
        keyProvider = AndroidKeystoreKeyProvider()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun vaultFileFor(userId: String): File {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(storageDir, "vault_$digest.bin")
    }

    @Synchronized
    fun save(envelope: LocalVaultEnvelope) {
        val plaintext = json.encodeToString(envelope).toByteArray(StandardCharsets.UTF_8)
        val (iv, ciphertext) = keyProvider.encrypt(plaintext)
        val buffer = ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()

        val target = vaultFileFor(envelope.userId)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(buffer)
        if (!temp.renameTo(target)) {
            target.writeBytes(buffer)
            temp.delete()
        }
    }

    @Synchronized
    fun load(userId: String): LocalVaultEnvelope? {
        val file = vaultFileFor(userId)
        if (!file.exists()) return null
        return try {
            val raw = file.readBytes()
            if (raw.size < 16) return null
            val buffer = ByteBuffer.wrap(raw)
            val ivLen = buffer.int
            if (ivLen <= 0 || ivLen > 64 || 4 + ivLen >= raw.size) return null
            val iv = ByteArray(ivLen)
            buffer.get(iv)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)
            val plaintext = keyProvider.decrypt(iv, ciphertext)
            json.decodeFromString<LocalVaultEnvelope>(String(plaintext, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear(userId: String) {
        val file = vaultFileFor(userId)
        if (file.exists()) {
            file.delete()
        }
    }
}
