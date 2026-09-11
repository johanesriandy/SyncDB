package com.syncdb.core

/**
 * Brings the LOCAL database structure to the schema's target version, ported
 * from WatermelonDB's migration behavior. Runs inside a transaction.
 *
 * - **Fresh database** (no recorded version): create every table at the target
 *   version and record it.
 * - **Upgrade** (recorded < target) with a valid migration path: apply the
 *   registry's steps (`CREATE TABLE` / `ADD COLUMN`) and record the new version.
 * - **No migration path, or a downgrade** (recorded > target): **reset** — drop
 *   and recreate the tables and clear the pull watermark, so the next sync does a
 *   full re-pull. Like WatermelonDB, there is no schema rollback; the local DB is
 *   a cache and the remote is the source of truth.
 */
object SyncMigrator {

    fun migrate(db: SqlLocalDatabase, logger: SyncLogger = SyncLogger.NONE) {
        val schema = db.schema
        val migrations = db.migrations
        val target = schema.version

        db.transaction {
            val stored = db.localSchemaVersion()
            when {
                stored == null -> {
                    // Brand-new (or pre-migration) database: create tables at target.
                    schema.tables.forEach { db.createTable(it) }
                    db.setLocalSchemaVersion(target)
                }

                stored == target -> {
                    // Up to date. Ensure any newly-registered tables still exist.
                    schema.tables.forEach { db.createTable(it) }
                }

                stored < target && migrations != null && migrations.canMigrate(stored, target) -> {
                    applySteps(db, migrations.stepsBetween(stored, target))
                    db.setLocalSchemaVersion(target)
                }

                else -> {
                    // Downgrade, or no migration path available -> reset (full resync).
                    logger.onWarning(
                        "Cannot migrate local schema $stored -> $target; resetting local " +
                            "database (a full re-sync will follow).",
                    )
                    schema.tables.forEach {
                        db.dropTable(it.name)
                        db.createTable(it)
                    }
                    db.resetSyncWatermark()
                    db.setLocalSchemaVersion(target)
                }
            }
        }
    }

    private fun applySteps(db: SqlLocalDatabase, steps: List<MigrationStep>) {
        steps.forEach { step ->
            when (step) {
                is MigrationStep.CreateTable -> db.createTable(step.table)
                is MigrationStep.AddColumns ->
                    step.columns.forEach { db.addColumnIfMissing(step.table, it) }
            }
        }
    }
}
