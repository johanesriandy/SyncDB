package com.syncdb.core

/**
 * Declarative schema builder — SyncDB's single source of truth for tables,
 * mirroring WatermelonDB's `appSchema`. No SQL files: the engine derives all DDL
 * and statements from these descriptors.
 *
 * ```
 * val schema = syncSchema(version = 1) {
 *     table("posts") {
 *         text("title", nullable = false, default = "''")
 *         text("body", nullable = false, default = "''")
 *         bool("is_pinned", nullable = false, default = "0")
 *     }
 *     table("comments") {
 *         text("post_id", nullable = false, default = "''")
 *         text("body", nullable = false, default = "''")
 *     }
 * }
 * ```
 *
 * `id`, `_status`, and `_changed` are implicit — do not declare them.
 */
fun syncSchema(version: Int = 1, block: SchemaBuilder.() -> Unit): SyncSchema =
    SchemaBuilder().apply(block).build(version)

class SchemaBuilder {
    private val tables = mutableListOf<SyncableTable>()

    fun table(name: String, block: TableBuilder.() -> Unit) {
        tables.add(TableBuilder(name).apply(block).build())
    }

    internal fun build(version: Int): SyncSchema = SyncSchema(tables, version)
}

class TableBuilder(private val name: String) {
    private val columns = mutableListOf<SyncableColumn>()

    /** Add a column of any [ColumnType]. */
    fun column(name: String, type: ColumnType, nullable: Boolean = true, default: String? = null) {
        columns.add(SyncableColumn(name, type, nullable, default))
    }

    fun text(name: String, nullable: Boolean = true, default: String? = null) =
        column(name, ColumnType.TEXT, nullable, default)

    fun integer(name: String, nullable: Boolean = true, default: String? = null) =
        column(name, ColumnType.INTEGER, nullable, default)

    fun real(name: String, nullable: Boolean = true, default: String? = null) =
        column(name, ColumnType.REAL, nullable, default)

    fun bool(name: String, nullable: Boolean = true, default: String? = null) =
        column(name, ColumnType.BOOLEAN, nullable, default)

    internal fun build(): SyncableTable = SyncableTable(name, columns)
}
