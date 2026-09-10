package com.syncdb.core

/** Outcome of a successful [SyncEngine.synchronize] call. */
data class SyncResult(
    val newLastPulledAt: Long,
    val remoteApplied: ApplyCounts,
    val pushedCreated: Int,
    val pushedUpdated: Int,
    val pushedDeleted: Int,
    val rejected: Int,
) {
    val pushedTotal: Int get() = pushedCreated + pushedUpdated + pushedDeleted
}

/** Reactive sync status, exposed via [SyncEngine.observeSyncState]. */
sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val phase: SyncPhase) : SyncState
    data class Succeeded(val result: SyncResult) : SyncState
    data class Failed(val error: Throwable) : SyncState
}

/** Raised when a second sync is detected running against the same watermark. */
class ConcurrentSyncException(message: String) : IllegalStateException(message)

/** Raised when the remote returns an invalid pull timestamp. */
class InvalidPullTimestampException(message: String) : IllegalStateException(message)
