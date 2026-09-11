package com.syncdb.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncEngineMigrationTest {

    // widgets v2 (name, color) + migration that adds `color` at v2.
    private val schemaV2 = SyncSchema(
        listOf(
            SyncableTable(
                "widgets",
                listOf(SyncableColumn("name", ColumnType.TEXT), SyncableColumn("color", ColumnType.TEXT)),
            ),
        ),
        version = 2,
    )
    private val migrations = syncMigrations {
        migration(2) { addColumn("widgets", "color", ColumnType.TEXT) }
    }

    private fun openAtV2() = SyncCore.openDatabase(createTestDriver(), schemaV2, migrations)

    @Test
    fun migrationSyncSendsNewColumnsAndKeepsCursor() = runTest {
        val db = openAtV2()
        db.setWatermark(100L, 1) // we last pulled at schema version 1
        val transport = FakeTransport(pullResult = PullResult(emptyMap(), 200L))
        val engine = SyncCore.createEngine(db, transport, SyncOptions(migrationsEnabledAtVersion = 1))

        engine.synchronize()

        // incremental pull kept its cursor, and carried the migration descriptor
        assertEquals(100L, transport.lastPulledAtSeen)
        val m = transport.migrationSeen!!
        assertEquals(1, m.from)
        assertEquals(2, m.to)
        assertEquals(listOf(MigrationColumns("widgets", listOf("color"))), m.columns)
        // schema version advanced in the watermark
        assertEquals(2, db.lastPulledSchemaVersion())
        assertEquals(200L, db.lastPulledAt())
    }

    @Test
    fun belowEnabledVersionForcesFullResync() = runTest {
        val db = openAtV2()
        db.setWatermark(100L, 1)
        val transport = FakeTransport(pullResult = PullResult(emptyMap(), 200L))
        // migration sync only trusted from v5 onward; v1 is too old -> full resync
        val engine = SyncCore.createEngine(db, transport, SyncOptions(migrationsEnabledAtVersion = 5))

        engine.synchronize()

        assertNull(transport.lastPulledAtSeen)  // cursor dropped -> full snapshot
        assertNull(transport.migrationSeen)
        assertEquals(2, db.lastPulledSchemaVersion())
    }

    @Test
    fun noMigrationWhenVersionUnchanged() = runTest {
        val db = openAtV2()
        db.setWatermark(100L, 2) // already pulled at v2
        val transport = FakeTransport(pullResult = PullResult(emptyMap(), 200L))
        val engine = SyncCore.createEngine(db, transport, SyncOptions(migrationsEnabledAtVersion = 1))

        engine.synchronize()

        assertEquals(100L, transport.lastPulledAtSeen)
        assertNull(transport.migrationSeen)
    }

    @Test
    fun migrationsDisabledSendsNoMigration() = runTest {
        val db = openAtV2()
        db.setWatermark(100L, 1)
        val transport = FakeTransport(pullResult = PullResult(emptyMap(), 200L))
        val engine = SyncCore.createEngine(db, transport, SyncOptions()) // migrationsEnabledAtVersion = null

        engine.synchronize()

        assertEquals(100L, transport.lastPulledAtSeen)
        assertNull(transport.migrationSeen)
        // with migrations disabled the persisted schema version is left untouched
        assertEquals(1, db.lastPulledSchemaVersion())
    }
}
