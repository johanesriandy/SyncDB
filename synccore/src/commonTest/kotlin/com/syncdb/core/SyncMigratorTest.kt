package com.syncdb.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMigratorTest {

    private fun widgets(vararg cols: Pair<String, ColumnType>, version: Int) = SyncSchema(
        listOf(SyncableTable("widgets", cols.map { SyncableColumn(it.first, it.second) })),
        version = version,
    )

    @Test
    fun freshDatabaseCreatesTablesAtTargetVersion() {
        val driver = createTestDriver()
        val db = SyncCore.openDatabase(driver, widgets("name" to ColumnType.TEXT, version = 1))
        assertEquals(1, db.localSchemaVersion())
        assertTrue("name" in db.tableColumnNames("widgets"))
        assertTrue("_status" in db.tableColumnNames("widgets"))
    }

    @Test
    fun upgradeAddsColumnAndPreservesData() {
        val driver = createTestDriver()
        val v1 = SyncCore.openDatabase(driver, widgets("name" to ColumnType.TEXT, version = 1))
        v1.create("widgets", mapOf("id" to "w1", "name" to "Sprocket"))

        val migrations = syncMigrations { migration(2) { addColumn("widgets", "color", ColumnType.TEXT) } }
        val v2 = SyncCore.openDatabase(
            driver,
            widgets("name" to ColumnType.TEXT, "color" to ColumnType.TEXT, version = 2),
            migrations,
        )

        assertEquals(2, v2.localSchemaVersion())
        assertTrue("color" in v2.tableColumnNames("widgets"))
        val row = v2.find("widgets", "w1")!!
        assertEquals("Sprocket", row["name"]) // data preserved through the migration
        assertEquals(null, row["color"])       // new column is null
    }

    @Test
    fun upgradeCreatesNewTable() {
        val driver = createTestDriver()
        SyncCore.openDatabase(driver, widgets("name" to ColumnType.TEXT, version = 1))

        val gadgets = SyncableTable("gadgets", listOf(SyncableColumn("kind", ColumnType.TEXT)))
        val migrations = syncMigrations { migration(2) { createTable(gadgets) } }
        val schemaV2 = SyncSchema(
            listOf(SyncableTable("widgets", listOf(SyncableColumn("name", ColumnType.TEXT))), gadgets),
            version = 2,
        )
        val v2 = SyncCore.openDatabase(driver, schemaV2, migrations)

        assertTrue("kind" in v2.tableColumnNames("gadgets"))
        assertEquals(2, v2.localSchemaVersion())
    }

    @Test
    fun noMigrationPathResetsAndClearsWatermark() {
        val driver = createTestDriver()
        val v1 = SyncCore.openDatabase(driver, widgets("name" to ColumnType.TEXT, version = 1))
        v1.create("widgets", mapOf("id" to "w1", "name" to "gone"))
        v1.setWatermark(500L, 1)

        // Bump to v2 with NO migrations -> reset (WatermelonDB behavior).
        val v2 = SyncCore.openDatabase(driver, widgets("name" to ColumnType.TEXT, version = 2))

        assertEquals(2, v2.localSchemaVersion())
        assertNull(v2.find("widgets", "w1"))       // data wiped
        assertNull(v2.lastPulledAt())              // watermark reset -> full re-pull next sync
    }

    @Test
    fun downgradeResets() {
        val driver = createTestDriver()
        SyncCore.openDatabase(driver, widgets("name" to ColumnType.TEXT, "color" to ColumnType.TEXT, version = 2))
        val back = SyncCore.openDatabase(driver, widgets("name" to ColumnType.TEXT, version = 1))
        assertEquals(1, back.localSchemaVersion())
        // color column is gone after the reset/recreate at v1
        assertFalse("color" in back.tableColumnNames("widgets"))
    }
}
