package com.syncdb.core

import app.cash.sqldelight.db.SqlDriver
import com.syncdb.core.db.SyncDatabase

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
        val database = SyncDatabase(driver)
        val local = SqlLocalDatabase(driver, database, schema, migrations)
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
 * Descriptors for the two example domain tables shipped in the SQLDelight schema.
 * A real app registers its own [SyncableTable] list the same way.
 */
object ExampleSchema {
    val posts = SyncableTable(
        name = "posts",
        columns = listOf(
            SyncableColumn("title", ColumnType.TEXT),
            SyncableColumn("body", ColumnType.TEXT),
            SyncableColumn("is_pinned", ColumnType.BOOLEAN),
        ),
    )

    val comments = SyncableTable(
        name = "comments",
        columns = listOf(
            SyncableColumn("post_id", ColumnType.TEXT),
            SyncableColumn("body", ColumnType.TEXT),
        ),
    )

    val schema = SyncSchema(listOf(posts, comments))
}
