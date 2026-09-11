package com.syncdb.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationsTest {

    private fun col(name: String) = SyncableColumn(name, ColumnType.TEXT)
    private fun table(name: String, vararg cols: String) =
        SyncableTable(name, cols.map { col(it) })

    private val migrations = syncMigrations {
        migration(toVersion = 2) {
            addColumn("posts", "author", ColumnType.TEXT)
        }
        migration(toVersion = 3) {
            createTable(table("tags", "label"))
            addColumn("posts", "slug", ColumnType.TEXT)
        }
    }

    @Test
    fun canMigrateRequiresContiguousVersions() {
        assertTrue(migrations.canMigrate(1, 3))
        assertTrue(migrations.canMigrate(2, 3))
        assertTrue(migrations.canMigrate(3, 3))
        // missing a migration for an intermediate version
        val gappy = syncMigrations { migration(3) { addColumn("posts", "x", ColumnType.TEXT) } }
        assertFalse(gappy.canMigrate(1, 3)) // no migration for v2
    }

    @Test
    fun stepsBetweenAreOrderedAndScoped() {
        val steps = migrations.stepsBetween(1, 3)
        assertEquals(3, steps.size) // v2: addColumns; v3: createTable + addColumns
        assertTrue(steps[0] is MigrationStep.AddColumns)      // v2 first
        assertTrue(steps.any { it is MigrationStep.CreateTable })
    }

    @Test
    fun migrationInfoListsNewTablesAndColumns() {
        val info = migrations.migrationInfo(1, 3)!!
        assertEquals(1, info.from)
        assertEquals(3, info.to)
        assertEquals(listOf("tags"), info.tables)
        assertEquals(listOf(MigrationColumns("posts", listOf("author", "slug"))), info.columns)
    }

    @Test
    fun migrationInfoFromV2OnlySeesV3() {
        val info = migrations.migrationInfo(2, 3)!!
        assertEquals(listOf("tags"), info.tables)
        assertEquals(listOf(MigrationColumns("posts", listOf("slug"))), info.columns)
    }

    @Test
    fun columnsAddedToANewlyCreatedTableAreNotDoubleCounted() {
        val m = syncMigrations {
            migration(2) {
                createTable(table("tags", "label"))
                addColumn("tags", "color", ColumnType.TEXT) // same range as its createTable
            }
        }
        val info = m.migrationInfo(1, 2)!!
        assertEquals(listOf("tags"), info.tables)
        assertTrue(info.columns.isEmpty()) // whole table is re-synced; no separate column entry
    }

    @Test
    fun migrationInfoIsNullWhenNothingNew() {
        assertNull(migrations.migrationInfo(3, 3))
    }
}
