package com.syncdb.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaDslTest {

    @Test
    fun createTableSqlHonorsNullableAndDefault() {
        val t = SyncableTable(
            "t",
            listOf(
                SyncableColumn("a", ColumnType.TEXT, nullable = false, default = "''"),
                SyncableColumn("b", ColumnType.INTEGER), // nullable, no default
            ),
        )
        val sql = t.createTableSql()

        assertTrue(sql.contains("\"a\" TEXT NOT NULL DEFAULT ''"), sql)
        assertTrue(sql.contains("\"b\" INTEGER"), sql)
        assertFalse(sql.contains("\"b\" INTEGER NOT NULL"), sql) // b stays nullable
        assertTrue(sql.contains("\"id\" TEXT NOT NULL PRIMARY KEY"), sql)
        assertTrue(sql.contains("\"_status\" TEXT NOT NULL DEFAULT 'created'"), sql)
        assertTrue(sql.contains("\"_changed\" TEXT NOT NULL DEFAULT ''"), sql)
    }

    @Test
    fun dslProducesEquivalentSchemaToExplicitForm() {
        val explicit = SyncSchema(
            listOf(
                SyncableTable(
                    "posts",
                    listOf(
                        SyncableColumn("title", ColumnType.TEXT, nullable = false, default = "''"),
                        SyncableColumn("is_pinned", ColumnType.BOOLEAN, nullable = false, default = "0"),
                    ),
                ),
            ),
            version = 2,
        )
        val dsl = syncSchema(version = 2) {
            table("posts") {
                text("title", nullable = false, default = "''")
                bool("is_pinned", nullable = false, default = "0")
            }
        }

        assertEquals(explicit.version, dsl.version)
        assertEquals(explicit.tableNames, dsl.tableNames)
        assertEquals(explicit["posts"].columns, dsl["posts"].columns)
        assertEquals(explicit["posts"].createTableSql(), dsl["posts"].createTableSql())
    }

    @Test
    fun defaultsAreAppliedWhenColumnsAreOmitted() {
        // ExampleSchema (via the DSL) declares posts columns NOT NULL DEFAULT.
        val db = newTestDb()
        db.create("posts", mapOf("id" to "p1", "title" to "hi")) // body + is_pinned omitted

        val row = db.find("posts", "p1")!!
        assertEquals("", row["body"])         // DEFAULT ''
        assertEquals(false, row["is_pinned"]) // DEFAULT 0 -> false
    }
}
