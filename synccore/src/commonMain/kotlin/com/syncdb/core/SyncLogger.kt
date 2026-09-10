package com.syncdb.core

/** A resolved conflict, surfaced to the [SyncLogger]. */
data class ConflictLog(
    val table: String,
    val id: Id,
    val local: Raw,
    val remote: Raw,
    val resolved: Raw,
)

/** Counts applied during a pull. */
data class ApplyCounts(
    val created: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val skipped: Int = 0,
)

/**
 * Observe/log each sync. Every method has a no-op default so implementations
 * override only what they care about.
 */
interface SyncLogger {
    fun onPhase(phase: SyncPhase) {}
    fun onRemoteApplied(counts: ApplyCounts) {}
    fun onConflict(conflict: ConflictLog) {}
    fun onLocalChangesCollected(created: Int, updated: Int, deleted: Int) {}
    fun onWarning(message: String) {}

    companion object {
        val NONE: SyncLogger = object : SyncLogger {}
    }
}

enum class SyncPhase {
    STARTING,
    PULLING,
    APPLYING_REMOTE,
    PUSHING,
    MARKING_SYNCED,
    DONE,
    FAILED,
}
