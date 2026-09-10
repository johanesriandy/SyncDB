package com.syncdb.core

/** SQLite storage class of a synced column. */
enum class ColumnType {
    TEXT,
    INTEGER,
    REAL,
    BOOLEAN, // stored as INTEGER 0/1, surfaced as Kotlin Boolean
}

/** A single domain column of a synced table. */
data class SyncableColumn(
    val name: String,
    val type: ColumnType,
)

/**
 * Describes a table the sync engine manages. Registering a table is purely
 * declarative — list its domain columns and the engine derives every SQL
 * statement it needs. New tables = add a descriptor, no per-table sync code.
 *
 * The `id` primary key and the `_status` / `_changed` bookkeeping columns are
 * implicit and must NOT be listed in [columns].
 */
data class SyncableTable(
    val name: String,
    val columns: List<SyncableColumn>,
) {
    init {
        require(name.isNotBlank()) { "Table name must not be blank" }
        val reserved = columns.map { it.name }.filter {
            it == SyncColumns.ID || it in SyncColumns.INTERNAL
        }
        require(reserved.isEmpty()) {
            "Columns $reserved are implicit and must not be declared for table '$name'"
        }
    }

    /** Domain column names only (no id / bookkeeping). */
    val domainColumnNames: List<String> = columns.map { it.name }

    /** Every column that physically exists in the table, in a stable order. */
    val allColumnNames: List<String> =
        listOf(SyncColumns.ID) + domainColumnNames + listOf(SyncColumns.STATUS, SyncColumns.CHANGED)

    private val typeByName: Map<String, ColumnType> = buildMap {
        put(SyncColumns.ID, ColumnType.TEXT)
        columns.forEach { put(it.name, it.type) }
        put(SyncColumns.STATUS, ColumnType.TEXT)
        put(SyncColumns.CHANGED, ColumnType.TEXT)
    }

    fun typeOf(column: String): ColumnType =
        typeByName[column] ?: error("Unknown column '$column' on table '$name'")

    fun hasColumn(column: String): Boolean = typeByName.containsKey(column)
}

/**
 * `CREATE TABLE` DDL for this descriptor, including the implicit `id` PK and the
 * `_status` / `_changed` bookkeeping columns. Identifiers are quoted so reserved
 * words (e.g. `group`) are safe. Domain columns are nullable so a remote row may
 * omit or null any of them. Useful for registering tables at runtime (e.g. tests
 * or integration harnesses) without hand-writing a SQLDelight `.sq` file.
 */
fun SyncableTable.createTableSql(): String {
    fun q(id: String) = "\"" + id.replace("\"", "\"\"") + "\""
    fun sqlType(type: ColumnType) = when (type) {
        ColumnType.TEXT -> "TEXT"
        ColumnType.INTEGER, ColumnType.BOOLEAN -> "INTEGER"
        ColumnType.REAL -> "REAL"
    }
    val defs = buildList {
        add("${q(SyncColumns.ID)} TEXT NOT NULL PRIMARY KEY")
        columns.forEach { add("${q(it.name)} ${sqlType(it.type)}") }
        add("${q(SyncColumns.STATUS)} TEXT NOT NULL DEFAULT '${SyncStatus.CREATED}'")
        add("${q(SyncColumns.CHANGED)} TEXT NOT NULL DEFAULT ''")
    }
    return "CREATE TABLE IF NOT EXISTS ${q(name)} (${defs.joinToString(", ")})"
}

/** A registry of syncable tables, keyed by table name, in registration order. */
class SyncSchema(val tables: List<SyncableTable>) {
    private val byName = tables.associateBy { it.name }

    operator fun get(name: String): SyncableTable =
        byName[name] ?: error("Table '$name' is not registered in the sync schema")

    fun contains(name: String): Boolean = byName.containsKey(name)

    val tableNames: List<String> = tables.map { it.name }
}
