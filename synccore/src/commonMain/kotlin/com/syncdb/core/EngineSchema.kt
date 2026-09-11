package com.syncdb.core

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * SyncDB's internal engine tables, created from code (no SQLDelight `.sq` files).
 *
 * - `_sync_state`: the watermark + local schema version (single row, key='global').
 * - `_sync_deleted`: local tombstones.
 *
 * This [SqlSchema] is what the platform SQLite drivers run on first open. Its
 * `version` covers only these stable engine tables; DOMAIN tables are created and
 * versioned separately by [SyncMigrator] from [SyncableTable] descriptors.
 */
object EngineSchema : SqlSchema<QueryResult.Value<Unit>> {

    override val version: Long = 1

    const val CREATE_SYNC_STATE: String =
        "CREATE TABLE IF NOT EXISTS _sync_state (" +
            "key TEXT NOT NULL PRIMARY KEY, " +
            "last_pulled_at INTEGER, " +
            "last_pulled_schema_version INTEGER, " +
            "local_schema_version INTEGER" +
            ")"

    const val CREATE_SYNC_DELETED: String =
        "CREATE TABLE IF NOT EXISTS _sync_deleted (" +
            "table_name TEXT NOT NULL, " +
            "id TEXT NOT NULL, " +
            "PRIMARY KEY (table_name, id)" +
            ")"

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(null, CREATE_SYNC_STATE, 0)
        driver.execute(null, CREATE_SYNC_DELETED, 0)
        return QueryResult.Value(Unit)
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
}
