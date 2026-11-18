package com.example.data.sync

import com.example.domain.model.DeletedAccountRecord
import com.example.domain.model.TotpAccount
import com.example.domain.model.VaultContent

data class VaultMergeResult(
    val merged: VaultContent,
    val conflictsResolvedCount: Int
)

/**
 * Deterministic CRDT-style merge engine for multi-device synchronization.
 * Preserves account additions, newest edits, and account deletions across devices.
 */
object VaultMergeEngine {
    private const val TOMBSTONE_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L

    fun merge(
        local: VaultContent,
        remote: VaultContent,
        nowMs: Long = System.currentTimeMillis()
    ): VaultMergeResult {
        val localAccounts = local.accounts.associateBy { it.id }
        val remoteAccounts = remote.accounts.associateBy { it.id }
        val localDeletions = local.deletedAccounts.associateBy { it.id }
        val remoteDeletions = remote.deletedAccounts.associateBy { it.id }

        val allIds = (localAccounts.keys + remoteAccounts.keys + localDeletions.keys + remoteDeletions.keys)
        val mergedAccounts = mutableListOf<TotpAccount>()
        val mergedDeletions = mutableListOf<DeletedAccountRecord>()
        var conflicts = 0

        for (id in allIds) {
            val lAcc = localAccounts[id]
            val rAcc = remoteAccounts[id]
            val lDel = localDeletions[id]
            val rDel = remoteDeletions[id]

            val latestDeletionMs = maxOf(lDel?.deletedAtMs ?: -1L, rDel?.deletedAtMs ?: -1L)
            val latestAccount: TotpAccount? = when {
                lAcc != null && rAcc != null -> {
                    if (lAcc != rAcc) {
                        conflicts++
                    }
                    if (lAcc.updatedAtMs >= rAcc.updatedAtMs) lAcc else rAcc
                }
                lAcc != null -> lAcc
                rAcc != null -> rAcc
                else -> null
            }

            if (latestAccount != null && latestAccount.updatedAtMs > latestDeletionMs) {
                mergedAccounts += latestAccount
            } else if (latestDeletionMs > 0L) {
                if (nowMs - latestDeletionMs <= TOMBSTONE_RETENTION_MS) {
                    mergedDeletions += DeletedAccountRecord(id = id, deletedAtMs = latestDeletionMs)
                }
            }
        }

        val sortedAccounts = mergedAccounts
            .sortedWith(compareBy<TotpAccount> { it.sortOrder }.thenBy { it.createdAtMs })
            .mapIndexed { index, account ->
                if (account.sortOrder == index) account else account.copy(sortOrder = index)
            }

        return VaultMergeResult(
            merged = VaultContent(
                schemaVersion = maxOf(local.schemaVersion, remote.schemaVersion),
                accounts = sortedAccounts,
                deletedAccounts = mergedDeletions
            ),
            conflictsResolvedCount = conflicts
        )
    }
}
