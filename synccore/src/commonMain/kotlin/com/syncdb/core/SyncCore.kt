package com.syncdb.core

import app.cash.sqldelight.db.SqlDriver
import com.syncdb.core.db.SyncDatabase

/**
 * Assembly entry point. Wraps an [SqlDriver] in a [SqlLocalDatabase] with the
 * given [SyncSchema] and seeds the sync-state row.
 */
object SyncCore {
    fun openDatabase(driver: SqlDriver, schema: SyncSchema): SqlLocalDatabase {
        val database = SyncDatabase(driver)
        val local = SqlLocalDatabase(driver, database, schema)
        local.initialize()
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
