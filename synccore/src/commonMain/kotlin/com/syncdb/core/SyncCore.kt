package com.syncdb.core

import app.cash.sqldelight.db.SqlDriver

/**
 * Assembly entry point. Wraps an [SqlDriver] in a [SqlLocalDatabase] with the
 * given [SyncSchema] and seeds the sync-state row.
 */
object SyncCore {
    /**
     * Open the local database: seed sync state, then run the local migrator to
     * bring the database structure to [schema]'s version (creating tables on a
     * fresh DB, applying [migrations] on an upgrade, or resetting when there is
     * no migration path). Pass [migrations] to enable schema evolution.
     */
    fun openDatabase(
        driver: SqlDriver,
        schema: SyncSchema,
        migrations: SyncMigrations? = null,
        logger: SyncLogger = SyncLogger.NONE,
    ): SqlLocalDatabase {
        val local = SqlLocalDatabase(driver, schema, migrations)
        local.initialize()
        SyncMigrator.migrate(local, logger)
        return local
    }

    fun createEngine(
        db: SqlLocalDatabase,
        transport: SyncTransport,
        options: SyncOptions = SyncOptions(),
    ): SyncEngine = SyncEngine(db, transport, options)
}

/**
 * Two example domain tables, declared with the [syncSchema] DSL. Descriptors are
 * the single source of truth — the engine creates these tables from them (there
 * are no SQL files). A real app declares its own schema the same way.
 */
object ExampleSchema {
    val schema = syncSchema(version = 1) {
        table("posts") {
            text("title", nullable = false, default = "''")
            text("body", nullable = false, default = "''")
            bool("is_pinned", nullable = false, default = "0")
        }
        table("comments") {
            text("post_id", nullable = false, default = "''")
            text("body", nullable = false, default = "''")
        }
    }
}
