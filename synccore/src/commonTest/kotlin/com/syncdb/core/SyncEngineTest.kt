package com.syncdb.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    @Test
    fun firstSyncSendsNullWatermarkAndStoresRemoteTimestamp() = runTest {
        val db = newTestDb()
        val transport = FakeTransport(
            pullResult = PullResult(
                changes = mapOf("posts" to TableChangeSet(created = listOf(remotePost("p1", "snap")))),
                timestamp = 5000L,
            ),
        )
        val engine = SyncCore.createEngine(db, transport)

        val result = engine.synchronize()

        assertTrue(transport.lastPulledAtWasNull)             // first sync
        assertEquals(5000L, result.newLastPulledAt)
        assertEquals(5000L, db.lastPulledAt())                // watermark = remote clock, not device
        assertEquals("snap", db.find("posts", "p1")!!["title"])
        assertEquals(0, transport.pushCalls)                  // nothing local to push
    }

    @Test
    fun secondSyncSendsStoredWatermark() = runTest {
        val db = newTestDb()
        db.setWatermark(1234L, null)
        val transport = FakeTransport(pullResult = PullResult(emptyMap(), 2000L))
        val engine = SyncCore.createEngine(db, transport)

        engine.synchronize()

        assertEquals(1234L, transport.lastPulledAtSeen)
        assertEquals(2000L, db.lastPulledAt())
    }

    @Test
    fun localChangesArePushedThenMarkedSynced() = runTest {
        val db = newTestDb()
        db.create("posts", remotePost("p1", "local"))
        val transport = FakeTransport(pullResult = PullResult(emptyMap(), 10L))
        val engine = SyncCore.createEngine(db, transport)

        val result = engine.synchronize()

        assertEquals(1, transport.pushCalls)
        val pushed = transport.pushedChanges.single()["posts"]!!
        assertEquals(listOf("p1"), pushed.created.map { it["id"] })
        // pushed row must not carry internal columns
        assertTrue(pushed.created.first().keys.none { it in SyncColumns.INTERNAL })
        assertEquals(1, result.pushedCreated)
        assertEquals(SyncStatus.SYNCED, db.find("posts", "p1")!!.status())
    }

    @Test
    fun concurrencyGuardAbortsWhenWatermarkChangesDuringSync() = runTest {
        val db = newTestDb()
        db.setWatermark(100L, null)
        val transport = FakeTransport(pullResult = PullResult(emptyMap(), 200L))
        // Simulate a competing sync advancing the watermark after we read it.
        transport.onBeforePull = { db.setWatermark(999L, null) }
        val engine = SyncCore.createEngine(db, transport)

        assertFailsWith<ConcurrentSyncException> { engine.synchronize() }
        // aborted: our timestamp (200) was never written
        assertEquals(999L, db.lastPulledAt())
    }

    @Test
    fun nonPositiveTimestampIsRejected() = runTest {
        val db = newTestDb()
        val transport = FakeTransport(pullResult = PullResult(emptyMap(), 0L))
        val engine = SyncCore.createEngine(db, transport)

        assertFailsWith<InvalidPullTimestampException> { engine.synchronize() }
        assertNull(db.lastPulledAt())
    }

    @Test
    fun sendCreatedAsUpdatedFoldsCreatedIntoUpdatedBucket() = runTest {
        val db = newTestDb()
        db.create("posts", remotePost("p1", "local"))
        val transport = FakeTransport(pullResult = PullResult(emptyMap(), 10L))
        val engine = SyncCore.createEngine(db, transport, SyncOptions(sendCreatedAsUpdated = true))

        engine.synchronize()

        val pushed = transport.pushedChanges.single()["posts"]!!
        assertTrue(pushed.created.isEmpty())
        assertEquals(listOf("p1"), pushed.updated.map { it["id"] })
        // still correctly marked synced afterward
        assertEquals(SyncStatus.SYNCED, db.find("posts", "p1")!!.status())
    }

    @Test
    fun hasUnsyncedChangesReflectsLocalState() = runTest {
        val db = newTestDb()
        val engine = SyncCore.createEngine(db, FakeTransport())
        assertEquals(false, engine.hasUnsyncedChanges())
        db.create("posts", remotePost("p1", "x"))
        assertEquals(true, engine.hasUnsyncedChanges())
    }

    @Test
    fun rejectedIdsFromPushLeaveRowDirty() = runTest {
        val db = newTestDb()
        db.create("posts", remotePost("p1", "x"))
        val transport = FakeTransport(
            pullResult = PullResult(emptyMap(), 10L),
            pushResult = PushResult(rejectedIds = mapOf("posts" to listOf("p1"))),
        )
        val engine = SyncCore.createEngine(db, transport)

        val result = engine.synchronize()

        assertEquals(1, result.rejected)
        assertEquals(SyncStatus.CREATED, db.find("posts", "p1")!!.status())
    }
}
