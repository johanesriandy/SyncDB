package com.syncdb.core

/**
 * A single, explicit local-schema migration step. Additive only — matching
 * WatermelonDB, there is no "remove column/table" step. Removals are handled by
 * a schema reset (see [SyncMigrator]), not a migration.
 */
sealed interface MigrationStep {
    /** Create a brand-new table (with its implicit `id`/`_status`/`_changed`). */
    data class CreateTable(val table: SyncableTable) : MigrationStep

    /** Add columns to an existing table. */
    data class AddColumns(val table: String, val columns: List<SyncableColumn>) : MigrationStep
}

/** The migration steps that bring the schema up to [toVersion] (from `toVersion - 1`). */
data class SchemaMigration(
    val toVersion: Int,
    val steps: List<MigrationStep>,
)

/**
 * The migrations registry — the single source of truth for schema evolution,
 * ported from WatermelonDB's `schemaMigrations`. It drives BOTH:
 *  - the local structure migrator ([SyncMigrator]), and
 *  - migration sync ([migrationInfo]), which tells the remote to backfill data
 *    for tables/columns that are new since the last pull.
 *
 * Migrations must be forward-only and contiguous: to migrate a database across a
 * range, a [SchemaMigration] must exist for every intermediate version.
 */
class SyncMigrations(migrations: List<SchemaMigration>) {
    val migrations: List<SchemaMigration> = migrations.sortedBy { it.toVersion }

    init {
        val versions = this.migrations.map { it.toVersion }
        require(versions.toSet().size == versions.size) {
            "Duplicate migration toVersion in registry: $versions"
        }
        require(versions.all { it >= 2 }) {
            "Migration toVersion must be >= 2 (version 1 is the initial schema): $versions"
        }
    }

    /** True if every version in `(fromVersion, toVersion]` has a migration. */
    fun canMigrate(fromVersion: Int, toVersion: Int): Boolean {
        if (fromVersion >= toVersion) return true
        val present = migrations.map { it.toVersion }.toSet()
        return ((fromVersion + 1)..toVersion).all { it in present }
    }

    /** All steps to migrate from [fromVersion] to [toVersion], in version order. */
    fun stepsBetween(fromVersion: Int, toVersion: Int): List<MigrationStep> =
        migrations
            .filter { it.toVersion in (fromVersion + 1)..toVersion }
            .flatMap { it.steps }

    /**
     * The migration-sync descriptor for a `fromVersion -> toVersion` upgrade:
     * the newly-created tables (fully re-synced) and the newly-added columns per
     * existing table. Returns `null` when there is nothing new (no backfill needed).
     */
    fun migrationInfo(fromVersion: Int, toVersion: Int): Migration? {
        val steps = stepsBetween(fromVersion, toVersion)
        if (steps.isEmpty()) return null

        val createdTables = steps.filterIsInstance<MigrationStep.CreateTable>()
            .map { it.table.name }
        val createdSet = createdTables.toSet()

        val addedColumns = LinkedHashMap<String, MutableList<String>>()
        steps.filterIsInstance<MigrationStep.AddColumns>().forEach { step ->
            // Columns added to a table that is itself new in this range are
            // redundant — the whole table is re-synced anyway.
            if (step.table !in createdSet) {
                addedColumns.getOrPut(step.table) { mutableListOf() }
                    .addAll(step.columns.map { it.name })
            }
        }

        if (createdTables.isEmpty() && addedColumns.isEmpty()) return null

        return Migration(
            from = fromVersion,
            to = toVersion,
            tables = createdTables,
            columns = addedColumns.map { (table, cols) -> MigrationColumns(table, cols) },
        )
    }
}

// -----------------------------------------------------------------------------
// DSL
// -----------------------------------------------------------------------------

/**
 * Build a [SyncMigrations] registry:
 * ```
 * val migrations = syncMigrations {
 *     migration(toVersion = 2) {
 *         addColumn("posts", "author", ColumnType.TEXT)
 *     }
 *     migration(toVersion = 3) {
 *         createTable(SyncableTable("tags", listOf(SyncableColumn("label", ColumnType.TEXT))))
 *     }
 * }
 * ```
 */
fun syncMigrations(block: SyncMigrationsBuilder.() -> Unit): SyncMigrations =
    SyncMigrationsBuilder().apply(block).build()

class SyncMigrationsBuilder {
    private val migrations = mutableListOf<SchemaMigration>()

    fun migration(toVersion: Int, block: MigrationStepsBuilder.() -> Unit) {
        migrations.add(SchemaMigration(toVersion, MigrationStepsBuilder().apply(block).build()))
    }

    fun build(): SyncMigrations = SyncMigrations(migrations)
}

class MigrationStepsBuilder {
    private val steps = mutableListOf<MigrationStep>()

    fun createTable(table: SyncableTable) {
        steps.add(MigrationStep.CreateTable(table))
    }

    fun addColumns(table: String, columns: List<SyncableColumn>) {
        steps.add(MigrationStep.AddColumns(table, columns))
    }

    fun addColumn(table: String, name: String, type: ColumnType) {
        steps.add(MigrationStep.AddColumns(table, listOf(SyncableColumn(name, type))))
    }

    fun build(): List<MigrationStep> = steps.toList()
}
