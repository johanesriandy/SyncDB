package com.syncdb.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.syncdb.core.db.SyncDatabase

/**
 * [LocalStore] backed by a real SQLite database via an [SqlDriver]. Every
 * statement is derived from the [SyncableTable] descriptors, so no per-table
 * SQL is hand-written. Also carries the watermark accessors, the transaction
 * boundary, and the app-facing [create]/[update]/[delete] write helpers that
 * keep `_status` / `_changed` / tombstone bookkeeping correct.
 */
class SqlLocalDatabase(
    private val driver: SqlDriver,
    private val database: SyncDatabase,
    override val schema: SyncSchema,
) : LocalStore {

    private companion object {
        const val STATE_KEY = "global"
    }

    /** Seed the single `_sync_state` row (idempotent). */
    fun initialize() {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO _sync_state(key, last_pulled_at, last_pulled_schema_version) VALUES ('$STATE_KEY', NULL, NULL)",
            0,
        )
    }

    /** Runs [body] in a single write transaction. */
    fun <R> transaction(body: () -> R): R =
        database.transactionWithResult { body() }

    // ------------------------------------------------------------------
    // Watermark
    // ------------------------------------------------------------------

    fun lastPulledAt(): Long? = driver.executeQuery(
        null,
        "SELECT last_pulled_at FROM _sync_state WHERE key = ?",
        { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        },
        1,
    ) { bindString(0, STATE_KEY) }.value

    fun lastPulledSchemaVersion(): Int? = driver.executeQuery(
        null,
        "SELECT last_pulled_schema_version FROM _sync_state WHERE key = ?",
        { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0)?.toInt() else null)
        },
        1,
    ) { bindString(0, STATE_KEY) }.value

    fun setWatermark(timestamp: Long, schemaVersion: Int?) {
        driver.execute(
            null,
            "UPDATE _sync_state SET last_pulled_at = ?, last_pulled_schema_version = ? WHERE key = ?",
            3,
        ) {
            bindLong(0, timestamp)
            bindLong(1, schemaVersion?.toLong())
            bindString(2, STATE_KEY)
        }
    }

    // ------------------------------------------------------------------
    // LocalStore
    // ------------------------------------------------------------------

    override fun find(table: String, id: Id): Raw? {
        val t = schema[table]
        return rowsWhere(t, "${q(SyncColumns.ID)} = ?", 1) { bindString(0, id) }.firstOrNull()
    }

    override fun findByStatus(table: String, status: String): List<Raw> {
        val t = schema[table]
        return rowsWhere(t, "${q(SyncColumns.STATUS)} = ?", 1) { bindString(0, status) }
    }

    override fun deletedIds(table: String): List<Id> = driver.executeQuery(
        null,
        "SELECT id FROM _sync_deleted WHERE table_name = ?",
        { cursor ->
            val out = ArrayList<Id>()
            while (cursor.next().value) {
                cursor.getString(0)?.let { out.add(it) }
            }
            QueryResult.Value(out)
        },
        1,
    ) { bindString(0, table) }.value

    override fun isTombstoned(table: String, id: Id): Boolean = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM _sync_deleted WHERE table_name = ? AND id = ?",
        { cursor ->
            cursor.next()
            QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
        },
        2,
    ) {
        bindString(0, table)
        bindString(1, id)
    }.value

    override fun insert(table: String, raw: Raw) {
        val t = schema[table]
        val cols = t.allColumnNames.filter { raw.containsKey(it) }
        require(cols.contains(SyncColumns.ID)) { "insert into '$table' requires an 'id'" }
        val placeholders = cols.joinToString(",") { "?" }
        val sql = "INSERT INTO ${q(t.name)} (${cols.joinToString(",") { q(it) }}) VALUES ($placeholders)"
        driver.execute(null, sql, cols.size) {
            cols.forEachIndexed { i, c -> bindValue(i, t.typeOf(c), raw[c]) }
        }
    }

    override fun updateRow(table: String, raw: Raw) {
        val t = schema[table]
        val setCols = t.allColumnNames.filter { it != SyncColumns.ID && raw.containsKey(it) }
        require(setCols.isNotEmpty()) { "updateRow on '$table' has nothing to set" }
        val assignments = setCols.joinToString(",") { "${q(it)} = ?" }
        val sql = "UPDATE ${q(t.name)} SET $assignments WHERE ${q(SyncColumns.ID)} = ?"
        driver.execute(null, sql, setCols.size + 1) {
            setCols.forEachIndexed { i, c -> bindValue(i, t.typeOf(c), raw[c]) }
            bindString(setCols.size, raw.id())
        }
    }

    override fun destroy(table: String, id: Id) {
        val t = schema[table]
        driver.execute(null, "DELETE FROM ${q(t.name)} WHERE ${q(SyncColumns.ID)} = ?", 1) {
            bindString(0, id)
        }
    }

    override fun addTombstone(table: String, id: Id) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO _sync_deleted(table_name, id) VALUES (?, ?)",
            2,
        ) {
            bindString(0, table)
            bindString(1, id)
        }
    }

    override fun removeTombstone(table: String, id: Id) {
        driver.execute(
            null,
            "DELETE FROM _sync_deleted WHERE table_name = ? AND id = ?",
            2,
        ) {
            bindString(0, table)
            bindString(1, id)
        }
    }

    override fun markSynced(table: String, id: Id) {
        val t = schema[table]
        driver.execute(
            null,
            "UPDATE ${q(t.name)} SET ${q(SyncColumns.STATUS)} = '${SyncStatus.SYNCED}', ${q(SyncColumns.CHANGED)} = '' WHERE ${q(SyncColumns.ID)} = ?",
            1,
        ) { bindString(0, id) }
    }

    // ------------------------------------------------------------------
    // App-facing write helpers (keep bookkeeping correct)
    // ------------------------------------------------------------------

    /** Create a brand-new local row (`_status='created'`). [values] must include `id`. */
    fun create(table: String, values: Raw): Unit = transaction {
        val t = schema[table]
        val row: MutableRaw = values.toMutableMap()
        require(row[SyncColumns.ID] is String && (row[SyncColumns.ID] as String).isNotEmpty()) {
            "create('$table') requires a non-empty 'id'"
        }
        row.keys.forEach {
            require(it == SyncColumns.ID || t.hasColumn(it)) {
                "Unknown column '$it' for table '$table'"
            }
        }
        row[SyncColumns.STATUS] = SyncStatus.CREATED
        row[SyncColumns.CHANGED] = ""
        insert(table, row)
    }

    /** Apply a local edit. Marks a previously-synced row `updated` and tracks changed columns. */
    fun update(table: String, id: Id, changes: Map<String, Any?>): Unit = transaction {
        val t = schema[table]
        val existing = find(table, id) ?: error("update: '$table.$id' does not exist")
        changes.keys.forEach {
            require(t.hasColumn(it) && it !in SyncColumns.INTERNAL && it != SyncColumns.ID) {
                "Cannot edit column '$it' on table '$table'"
            }
        }
        val row: MutableRaw = existing.toMutableMap()
        changes.forEach { (k, v) -> row[k] = v }

        if (existing.status() == SyncStatus.CREATED) {
            // Still a local-only creation: the whole row is pushed as 'created'.
            row[SyncColumns.STATUS] = SyncStatus.CREATED
            row[SyncColumns.CHANGED] = ""
        } else {
            row[SyncColumns.STATUS] = SyncStatus.UPDATED
            row[SyncColumns.CHANGED] =
                mergeChanged(existing[SyncColumns.CHANGED] as? String ?: "", changes.keys)
        }
        updateRow(table, row)
    }

    /** Delete a local row: created-only rows vanish; synced rows become tombstones. */
    fun delete(table: String, id: Id): Unit = transaction {
        val existing = find(table, id) ?: return@transaction
        destroy(table, id)
        if (existing.status() != SyncStatus.CREATED) {
            // Remote knows this row; record a tombstone to push the deletion.
            addTombstone(table, id)
        }
    }

    // ------------------------------------------------------------------
    // Row (de)serialization helpers
    // ------------------------------------------------------------------

    private fun rowsWhere(
        t: SyncableTable,
        where: String,
        paramCount: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): List<Raw> {
        val cols = t.allColumnNames
        val sql = buildString {
            append("SELECT ")
            append(cols.joinToString(",") { q(it) })
            append(" FROM ")
            append(q(t.name))
            if (where.isNotEmpty()) {
                append(" WHERE ")
                append(where)
            }
        }
        return driver.executeQuery(null, sql, { cursor ->
            val out = ArrayList<Raw>()
            while (cursor.next().value) {
                val row = LinkedHashMap<String, Any?>(cols.size)
                cols.forEachIndexed { i, c -> row[c] = cursor.readValue(i, t.typeOf(c)) }
                out.add(row)
            }
            QueryResult.Value(out)
        }, paramCount, binders).value
    }

    private fun SqlPreparedStatement.bindValue(index: Int, type: ColumnType, value: Any?) {
        when (type) {
            ColumnType.TEXT -> bindString(index, value as String?)
            ColumnType.INTEGER -> bindLong(index, (value as? Number)?.toLong())
            ColumnType.REAL -> bindDouble(index, (value as? Number)?.toDouble())
            ColumnType.BOOLEAN -> bindBoolean(index, value as? Boolean)
        }
    }

    private fun SqlCursor.readValue(index: Int, type: ColumnType): Any? = when (type) {
        ColumnType.TEXT -> getString(index)
        ColumnType.INTEGER -> getLong(index)
        ColumnType.REAL -> getDouble(index)
        ColumnType.BOOLEAN -> getBoolean(index)
    }

    /** Quote a SQL identifier so reserved words (e.g. `group`) and odd names are safe. */
    private fun q(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
