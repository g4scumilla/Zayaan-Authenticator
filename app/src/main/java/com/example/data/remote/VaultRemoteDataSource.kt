package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.domain.model.CloudVaultDocument
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class VersionConflictException(
    val remoteDocument: CloudVaultDocument
) : Exception("Cloud vault has a newer version (v${remoteDocument.backupVersion}).")

interface VaultRemoteDataSource {
    suspend fun fetchCloudVault(userId: String): Result<CloudVaultDocument?>
    fun observeCloudVault(userId: String): Flow<CloudVaultDocument?>
    suspend fun createInitialVault(userId: String, doc: CloudVaultDocument): Result<Unit>
    suspend fun updateVaultOptimistically(
        userId: String,
        expectedRemoteVersion: Long,
        doc: CloudVaultDocument,
        includeWrappedKey: Boolean
    ): Result<Unit>
}

/**
 * Firestore remote data source anchored to users/{uid}/vault/main on the named database.
 */
class FirestoreVaultRemoteDataSource(
    private val db: FirebaseFirestore
) : VaultRemoteDataSource {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireAuthenticatedUser(expectedUserId: String): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        check(uid == expectedUserId) { "Signed-in user changed; refusing cross-user vault access." }
        return uid
    }

    private fun vaultDocRef(uid: String) =
        db.collection("users").document(uid).collection("vault").document("main")

    override suspend fun fetchCloudVault(userId: String): Result<CloudVaultDocument?> {
        val path = "users/$userId/vault/main"
        return try {
            val uid = requireAuthenticatedUser(userId)
            val snap = vaultDocRef(uid).get().await()
            Result.success(snap.toCloudVaultDocument())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    override fun observeCloudVault(userId: String): Flow<CloudVaultDocument?> = flow {
        val uid = requireAuthenticatedUser(userId)
        val path = "users/$uid/vault/main"
        emitAll(
            vaultDocRef(uid)
                .snapshots()
                .map { snapshot -> snapshot.toCloudVaultDocument() }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    override suspend fun createInitialVault(userId: String, doc: CloudVaultDocument): Result<Unit> {
        val path = "users/$userId/vault/main"
        return try {
            val uid = requireAuthenticatedUser(userId)
            val payload = mapOf(
                "userId" to uid,
                "encryptedPayload" to doc.encryptedPayload,
                "vaultIv" to doc.vaultIv,
                "wrappedVek" to doc.wrappedVek,
                "wrapIv" to doc.wrapIv,
                "kdfAlgorithm" to doc.kdfAlgorithm,
                "kdfIterations" to doc.kdfIterations,
                "kdfSalt" to doc.kdfSalt,
                "encryptionVersion" to doc.encryptionVersion,
                "schemaVersion" to doc.schemaVersion,
                "backupVersion" to doc.backupVersion,
                "lastDeviceId" to doc.lastDeviceId,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            vaultDocRef(uid).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    override suspend fun updateVaultOptimistically(
        userId: String,
        expectedRemoteVersion: Long,
        doc: CloudVaultDocument,
        includeWrappedKey: Boolean
    ): Result<Unit> {
        val path = "users/$userId/vault/main"
        return try {
            val uid = requireAuthenticatedUser(userId)
            val ref = vaultDocRef(uid)

            db.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                if (!snapshot.exists()) {
                    val createPayload = mapOf(
                        "userId" to uid,
                        "encryptedPayload" to doc.encryptedPayload,
                        "vaultIv" to doc.vaultIv,
                        "wrappedVek" to doc.wrappedVek,
                        "wrapIv" to doc.wrapIv,
                        "kdfAlgorithm" to doc.kdfAlgorithm,
                        "kdfIterations" to doc.kdfIterations,
                        "kdfSalt" to doc.kdfSalt,
                        "encryptionVersion" to doc.encryptionVersion,
                        "schemaVersion" to doc.schemaVersion,
                        "backupVersion" to doc.backupVersion,
                        "lastDeviceId" to doc.lastDeviceId,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    transaction.set(ref, createPayload)
                } else {
                    val remoteDoc = snapshot.toCloudVaultDocument()
                        ?: throw IllegalStateException("Existing cloud vault document is unreadable.")
                    if (remoteDoc.backupVersion != expectedRemoteVersion) {
                        throw VersionConflictException(remoteDoc)
                    }
                    val updateMap = mutableMapOf<String, Any>(
                        "encryptedPayload" to doc.encryptedPayload,
                        "vaultIv" to doc.vaultIv,
                        "schemaVersion" to doc.schemaVersion,
                        "backupVersion" to doc.backupVersion,
                        "lastDeviceId" to doc.lastDeviceId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (includeWrappedKey) {
                        updateMap["wrappedVek"] = doc.wrappedVek
                        updateMap["wrapIv"] = doc.wrapIv
                        updateMap["kdfAlgorithm"] = doc.kdfAlgorithm
                        updateMap["kdfIterations"] = doc.kdfIterations
                        updateMap["kdfSalt"] = doc.kdfSalt
                        updateMap["encryptionVersion"] = doc.encryptionVersion
                    }
                    transaction.update(ref, updateMap)
                }
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VersionConflictException) {
            Result.failure(e)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    private fun DocumentSnapshot.toCloudVaultDocument(): CloudVaultDocument? {
        if (!exists()) return null
        val userId = getString("userId") ?: return null
        val encryptedPayload = getString("encryptedPayload") ?: return null
        val vaultIv = getString("vaultIv") ?: return null
        val wrappedVek = getString("wrappedVek") ?: return null
        val wrapIv = getString("wrapIv") ?: return null
        val kdfAlgorithm = getString("kdfAlgorithm") ?: return null
        val kdfIterations = getLong("kdfIterations") ?: return null
        val kdfSalt = getString("kdfSalt") ?: return null
        val encryptionVersion = getLong("encryptionVersion") ?: return null
        val schemaVersion = getLong("schemaVersion") ?: 1L
        val backupVersion = getLong("backupVersion") ?: return null
        val lastDeviceId = getString("lastDeviceId") ?: "unknown"
        val createdAtMs = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            ?.toDate()?.time
        val updatedAtMs = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            ?.toDate()?.time

        return CloudVaultDocument(
            userId = userId,
            encryptedPayload = encryptedPayload,
            vaultIv = vaultIv,
            wrappedVek = wrappedVek,
            wrapIv = wrapIv,
            kdfAlgorithm = kdfAlgorithm,
            kdfIterations = kdfIterations,
            kdfSalt = kdfSalt,
            encryptionVersion = encryptionVersion,
            schemaVersion = schemaVersion,
            backupVersion = backupVersion,
            lastDeviceId = lastDeviceId,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs
        )
    }
}
