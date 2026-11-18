package com.example

import com.example.core.crypto.EncryptionDowngradeException
import com.example.core.crypto.InMemoryDeviceKeyProvider
import com.example.core.crypto.InvalidRecoveryPasswordException
import com.example.core.crypto.VaultCrypto
import com.example.data.local.LocalVaultStorage
import com.example.data.remote.VaultRemoteDataSource
import com.example.data.remote.VersionConflictException
import com.example.data.sync.ConnectivityChecker
import com.example.data.sync.VaultRepository
import com.example.domain.model.CloudVaultDocument
import com.example.domain.model.TotpAccount
import com.example.domain.model.TotpAlgorithm
import com.example.domain.model.VaultContent
import com.example.domain.model.VaultGateState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeVaultRemoteDataSource : VaultRemoteDataSource {
    private val docs = mutableMapOf<String, CloudVaultDocument>()
    private val flows = mutableMapOf<String, MutableStateFlow<CloudVaultDocument?>>()

    override suspend fun fetchCloudVault(userId: String): Result<CloudVaultDocument?> =
        Result.success(docs[userId])

    override fun observeCloudVault(userId: String): Flow<CloudVaultDocument?> =
        flows.getOrPut(userId) { MutableStateFlow(docs[userId]) }

    override suspend fun createInitialVault(userId: String, doc: CloudVaultDocument): Result<Unit> {
        docs[userId] = doc.copy(createdAtMs = 1000L, updatedAtMs = 1000L)
        flows[userId]?.value = docs[userId]
        return Result.success(Unit)
    }

    override suspend fun updateVaultOptimistically(
        userId: String,
        expectedRemoteVersion: Long,
        doc: CloudVaultDocument,
        includeWrappedKey: Boolean
    ): Result<Unit> {
        val existing = docs[userId]
        if (existing == null) {
            return createInitialVault(userId, doc)
        }
        if (existing.backupVersion != expectedRemoteVersion) {
            return Result.failure(VersionConflictException(existing))
        }
        val merged = if (includeWrappedKey) {
            doc.copy(createdAtMs = existing.createdAtMs, updatedAtMs = System.currentTimeMillis())
        } else {
            existing.copy(
                encryptedPayload = doc.encryptedPayload,
                vaultIv = doc.vaultIv,
                schemaVersion = doc.schemaVersion,
                backupVersion = doc.backupVersion,
                lastDeviceId = doc.lastDeviceId,
                updatedAtMs = System.currentTimeMillis()
            )
        }
        docs[userId] = merged
        flows[userId]?.value = merged
        return Result.success(Unit)
    }

    fun rawStoredDocument(userId: String): CloudVaultDocument? = docs[userId]
}

/**
 * Verifies zero-knowledge AES-256-GCM encryption, recovery password verification,
 * KDF downgrade protection, offline persistence, reinstallation recovery,
 * multi-device concurrent merges, and deletion synchronization.
 */
class VaultCryptoAndSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `AES-256-GCM encryption uses unique IVs and rejects wrong recovery password`() {
        val uid = "user_alpha"
        val password = "correct-horse-battery-staple-99"
        val vek = VaultCrypto.generateVaultKey()
        val wrapped = VaultCrypto.wrapVek(vek, password, uid)

        val account = TotpAccount(
            id = "acc-1",
            issuer = "GitHub",
            accountName = "alice@example.com",
            secretBase32 = "JBSWY3DPEHPK3PXP"
        )
        val content = VaultContent(accounts = listOf(account))

        val enc1 = VaultCrypto.encryptVault(content, vek, uid, 1L)
        val enc2 = VaultCrypto.encryptVault(content, vek, uid, 1L)
        assertNotEquals("Each encryption must generate a fresh nonce/IV", enc1.vaultIvBase64, enc2.vaultIvBase64)

        // Wrong password fails cleanly with InvalidRecoveryPasswordException
        var threwWrongPwd = false
        try {
            VaultCrypto.unwrapVek(
                wrappedVekBase64 = wrapped.wrappedVekBase64,
                wrapIvBase64 = wrapped.wrapIvBase64,
                kdfSaltBase64 = wrapped.kdfSaltBase64,
                kdfAlgorithm = VaultCrypto.KDF_ALGORITHM,
                kdfIterations = wrapped.kdfIterations,
                encryptionVersion = VaultCrypto.ENCRYPTION_VERSION,
                recoveryPassword = "wrong-recovery-password-123",
                uid = uid
            )
        } catch (_: InvalidRecoveryPasswordException) {
            threwWrongPwd = true
        }
        assertTrue(threwWrongPwd)

        // Correct password unwraps & decrypts
        val unwrapped = VaultCrypto.unwrapVek(
            wrappedVekBase64 = wrapped.wrappedVekBase64,
            wrapIvBase64 = wrapped.wrapIvBase64,
            kdfSaltBase64 = wrapped.kdfSaltBase64,
            kdfAlgorithm = VaultCrypto.KDF_ALGORITHM,
            kdfIterations = wrapped.kdfIterations,
            encryptionVersion = VaultCrypto.ENCRYPTION_VERSION,
            recoveryPassword = password,
            uid = uid
        )
        val decrypted = VaultCrypto.decryptVault(
            enc1.encryptedPayloadBase64,
            enc1.vaultIvBase64,
            unwrapped,
            uid,
            1L
        )
        assertEquals(listOf(account), decrypted.accounts)
    }

    @Test
    fun `refuses silent KDF iteration downgrade`() {
        var threw = false
        try {
            VaultCrypto.deriveKek("twelve-char-pwd!".toCharArray(), VaultCrypto.generateSalt(), iterations = 10_000)
        } catch (_: EncryptionDowngradeException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `encrypted export envelope decrypts with correct passphrase and rejects wrong one`() {
        val content = VaultContent(
            accounts = listOf(
                TotpAccount(issuer = "Amazon", accountName = "shopper@test.com", secretBase32 = "JBSWY3DPEHPK3PXP")
            )
        )
        val exported = VaultCrypto.encryptExportEnvelope(content, "my-strong-export-passphrase")
        val restored = VaultCrypto.decryptExportEnvelope(exported, "my-strong-export-passphrase")
        assertEquals(1, restored.accounts.size)
        assertEquals("Amazon", restored.accounts.first().issuer)

        var failed = false
        try {
            VaultCrypto.decryptExportEnvelope(exported, "wrong-passphrase")
        } catch (_: InvalidRecoveryPasswordException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `multi-device recovery, offline edits, concurrent changes, and deletions synchronize safely`() = runBlocking {
        val uid = "uid_multi_device"
        val recoveryPwd = "zayan-zero-knowledge-vault-2026"
        val cloud = FakeVaultRemoteDataSource()

        var deviceAOnline = true
        var deviceBOnline = true

        val deviceAStorage = LocalVaultStorage(tempFolder.newFolder("devA"), InMemoryDeviceKeyProvider())
        val deviceARepo = VaultRepository(
            userId = uid,
            localStorage = deviceAStorage,
            remoteDataSource = cloud,
            connectivityChecker = object : ConnectivityChecker {
                override fun isOnline(): Boolean = deviceAOnline
            }
        )

        // Device A initializes vault & adds Facebook
        assertTrue(deviceARepo.initialize().isSuccess)
        assertEquals(VaultGateState.NeedsRecoverySetup, deviceARepo.gateState.value)
        assertTrue(deviceARepo.createInitialVaultWithRecoveryPassword(recoveryPwd).isSuccess)

        val fb = TotpAccount(
            id = "fb-id",
            issuer = "Facebook",
            accountName = "zayan@fb.com",
            secretBase32 = "JBSWY3DPEHPK3PXP",
            algorithm = TotpAlgorithm.SHA1
        )
        assertTrue(deviceARepo.addAccount(fb).isSuccess)

        // Verify cloud document contains ZERO plaintext secrets or recovery password
        val rawCloud = cloud.rawStoredDocument(uid)!!
        assertTrue(!rawCloud.encryptedPayload.contains("JBSWY3DPEHPK3PXP"))
        assertTrue(!rawCloud.encryptedPayload.contains("Facebook"))

        // Device B (fresh install / new device) recovers using same Google UID + recovery password
        val deviceBStorage = LocalVaultStorage(tempFolder.newFolder("devB"), InMemoryDeviceKeyProvider())
        val deviceBRepo = VaultRepository(
            userId = uid,
            localStorage = deviceBStorage,
            remoteDataSource = cloud,
            connectivityChecker = object : ConnectivityChecker {
                override fun isOnline(): Boolean = deviceBOnline
            }
        )
        assertTrue(deviceBRepo.initialize().isSuccess)
        assertTrue(deviceBRepo.gateState.value is VaultGateState.NeedsRecoveryUnlock)

        // Wrong password on Device B fails without corrupting Device A or cloud
        assertTrue(deviceBRepo.unlockAndRestoreWithRecoveryPassword("wrong-pass-12345").isFailure)
        assertTrue(deviceBRepo.unlockAndRestoreWithRecoveryPassword(recoveryPwd).isSuccess)
        assertEquals(listOf("Facebook"), deviceBRepo.accounts.value.map { it.issuer })

        // Concurrent offline edits: Device A adds GitHub, Device B adds Google
        deviceAOnline = false
        deviceBOnline = false

        val github = TotpAccount(id = "gh-id", issuer = "GitHub", accountName = "dev@gh.com", secretBase32 = "GEZDGNBVGY3TQOJQ")
        val google = TotpAccount(id = "gg-id", issuer = "Google", accountName = "user@gmail.com", secretBase32 = "MFRGGZDFMZTWQ2LK")
        assertTrue(deviceARepo.addAccount(github).isSuccess)
        assertTrue(deviceBRepo.addAccount(google).isSuccess)

        // Both come back online & sync -> neither addition is lost!
        deviceAOnline = true
        assertTrue(deviceARepo.syncNow().isSuccess)

        deviceBOnline = true
        assertTrue(deviceBRepo.syncNow().isSuccess)

        // Device A syncs again -> both devices converge to all 3 accounts
        assertTrue(deviceARepo.syncNow().isSuccess)
        assertEquals(setOf("Facebook", "GitHub", "Google"), deviceARepo.accounts.value.map { it.issuer }.toSet())
        assertEquals(setOf("Facebook", "GitHub", "Google"), deviceBRepo.accounts.value.map { it.issuer }.toSet())

        // Device A deletes Facebook -> Device B syncs and sees deletion preserved
        assertTrue(deviceARepo.deleteAccount("fb-id").isSuccess)
        assertTrue(deviceBRepo.syncNow().isSuccess)
        assertEquals(setOf("GitHub", "Google"), deviceBRepo.accounts.value.map { it.issuer }.toSet())
    }
}
