package com.syncdb.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchAndMarkTest {

    @Test
    fun fetchLocalChangesBucketsAndStripsInternalColumns() {
        val db = newTestDb()
        db.create("posts", remotePost("c1", "created"))          // created
        db.create("posts", remotePost("u1", "will-sync"))
        db.transaction { db.markSynced("posts", "u1") }
        db.update("posts", "u1", mapOf("title" to "edited"))     // updated
        db.create("posts", remotePost("d1", "to-delete"))
        db.transaction { db.markSynced("posts", "d1") }
        db.delete("posts", "d1")                                  // tombstone

        val local = fetchLocalChanges(db)
        val posts = local.changes["posts"]!!

        assertEquals(listOf("c1"), posts.created.map { it["id"] })
        assertEquals(listOf("u1"), posts.updated.map { it["id"] })
        assertEquals(listOf("d1"), posts.deleted)

        // stripped: no internal columns on the wire
        assertFalse(posts.created.first().containsKey(SyncColumns.STATUS))
        assertFalse(posts.updated.first().containsKey(SyncColumns.CHANGED))
    }

    @Test
    fun markSyncedMarksCleanRowsAndClearsTombstones() {
        val db = newTestDb()
        db.create("posts", remotePost("c1", "created"))
        db.create("posts", remotePost("d1", "del"))
        db.transaction { db.markSynced("posts", "d1") }
        db.delete("posts", "d1")

        val local = fetchLocalChanges(db)
        db.transaction { markLocalChangesAsSynced(db, local) }

        assertEquals(SyncStatus.SYNCED, db.find("posts", "c1")!!.status())
        assertFalse(db.isTombstoned("posts", "d1"))
    }

    @Test
    fun rowEditedDuringPushIsNotMarkedSynced() {
        val db = newTestDb()
        db.create("posts", remotePost("c1", "v1"))

        val local = fetchLocalChanges(db) // snapshot captured here

        // user edits the same row while the push is "in flight"
        db.update("posts", "c1", mapOf("title" to "v2"))

        db.transaction { markLocalChangesAsSynced(db, local) }

        // still dirty -> will re-sync next time
        assertEquals(SyncStatus.CREATED, db.find("posts", "c1")!!.status())
    }

    @Test
    fun rejectedIdsAreLeftDirty() {
        val db = newTestDb()
        db.create("posts", remotePost("ok", "a"))
        db.create("posts", remotePost("bad", "b"))

        val local = fetchLocalChanges(db)
        val rejected = mapOf("posts" to listOf("bad"))
        db.transaction { markLocalChangesAsSynced(db, local, rejected) }

        assertEquals(SyncStatus.SYNCED, db.find("posts", "ok")!!.status())
        assertEquals(SyncStatus.CREATED, db.find("posts", "bad")!!.status()) // left to retry
    }

    @Test
    fun rejectedDeletionKeepsTombstone() {
        val db = newTestDb()
        db.create("posts", remotePost("d1", "x"))
        db.transaction { db.markSynced("posts", "d1") }
        db.delete("posts", "d1")

        val local = fetchLocalChanges(db)
        db.transaction { markLocalChangesAsSynced(db, local, mapOf("posts" to listOf("d1"))) }

        assertTrue(db.isTombstoned("posts", "d1")) // deletion not confirmed -> keep tombstone
    }
}
