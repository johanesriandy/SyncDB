package com.syncdb.core

/**
 * The set of changes for a single table: full rows for created/updated,
 * ids only for deleted. This is the WatermelonDB per-table change shape.
 */
data class TableChangeSet(
    val created: List<Raw> = emptyList(),
    val updated: List<Raw> = emptyList(),
    val deleted: List<Id> = emptyList(),
) {
    val isEmpty: Boolean
        get() = created.isEmpty() && updated.isEmpty() && deleted.isEmpty()
}

/** Changes across every table, keyed by table name. */
typealias DatabaseChangeSet = Map<String, TableChangeSet>

fun DatabaseChangeSet.isEmpty(): Boolean = all { it.value.isEmpty }

/**
 * Result of [SyncTransport.pullChanges].
 *
 * @property changes rows the remote wants applied locally.
 * @property timestamp the remote clock; becomes the next `lastPulledAt`.
 *   MUST be a positive, non-zero number. Never a device clock.
 */
data class PullResult(
    val changes: DatabaseChangeSet,
    val timestamp: Long,
)

/**
 * Local changes gathered by [fetchLocalChanges], ready to push.
 *
 * @property changes rows to send. Created/updated rows are already stripped of
 *   the internal `_status` / `_changed` columns.
 * @property affectedRecords the exact stripped snapshots that were pushed, keyed
 *   by [affectedKey]. [markLocalChangesAsSynced] compares these against the
 *   current row to detect edits that happened during the push.
 */
data class LocalChanges(
    val changes: DatabaseChangeSet,
    val affectedRecords: Map<String, Raw>,
) {
    fun isEmpty(): Boolean = changes.isEmpty()

    companion object {
        fun affectedKey(table: String, id: Id): String = "$table|$id"
    }
}

/**
 * Result of [SyncTransport.pushChanges]. The remote may reject some ids
 * (e.g. server-side validation); rejected rows are left dirty to retry.
 */
data class PushResult(
    val rejectedIds: Map<String, List<Id>> = emptyMap(),
)

/**
 * Migration info passed to the remote so it can return only the changes needed
 * to move a client from one schema version to another (WatermelonDB migration
 * sync). The concrete shape is remote-defined; the engine only forwards it.
 */
data class Migration(
    val from: Int,
    val to: Int,
    val tables: List<String> = emptyList(),
    val columns: List<MigrationColumns> = emptyList(),
)

data class MigrationColumns(
    val table: String,
    val columns: List<String>,
)
