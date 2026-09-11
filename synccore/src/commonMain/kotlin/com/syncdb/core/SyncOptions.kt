package com.syncdb.core

/**
 * Tuning flags matching WatermelonDB's sync options.
 *
 * @property schemaVersion overrides the schema version forwarded to the remote.
 *   When null (default), the engine uses `SyncSchema.version`.
 * @property sendCreatedAsUpdated when true, locally-created rows are pushed in the
 *   `updated` bucket instead of `created` (for remotes that upsert and don't
 *   distinguish create vs update).
 * @property migrationsEnabledAtVersion when non-null, migration sync is enabled:
 *   a [Migration] is computed and passed to `pullChanges`, and the schema version
 *   is persisted with the watermark.
 * @property conflictResolver optional override of the default per-column conflict rule.
 * @property logger observe/log each sync (phases, counts, resolved conflicts).
 */
data class SyncOptions(
    val schemaVersion: Int? = null,
    val sendCreatedAsUpdated: Boolean = false,
    val migrationsEnabledAtVersion: Int? = null,
    val conflictResolver: ConflictResolver? = null,
    val logger: SyncLogger = SyncLogger.NONE,
)
