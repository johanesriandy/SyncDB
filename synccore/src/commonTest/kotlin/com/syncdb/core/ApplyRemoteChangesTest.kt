package com.syncdb.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplyRemoteChangesTest {

    private fun changes(vararg pairs: Pair<String, TableChangeSet>): DatabaseChangeSet = mapOf(*pairs)

    @Test
    fun createdNewRowIsInsertedAsSynced() {
        val db = newTestDb()
        db.transaction {
            applyRemoteChanges(db, changes("posts" to TableChangeSet(created = listOf(remotePost("p1", "hello")))))
        }
        val row = db.find("posts", "p1")!!
        assertEquals("hello", row["title"])
        assertEquals(SyncStatus.SYNCED, row.status())
        assertEquals("", row[SyncColumns.CHANGED])
    }

    @Test
    fun createdButExistsLocallyIsTreatedAsUpdate() {
        val db = newTestDb()
        db.create("posts", remotePost("p1", "original"))
        // simulate it already synced
        db.transaction { db.markSynced("posts", "p1") }

        val logger = RecordingLogger()
        db.transaction {
            applyRemoteChanges(
                db,
                changes("posts" to TableChangeSet(created = listOf(remotePost("p1", "from server")))),
                logger = logger,
            )
        }
        assertEquals("from server", db.find("posts", "p1")!!["title"])
        assertTrue(logger.warnings.any { it.contains("already exists") })
    }

    @Test
    fun createdButLocallyDeletedRecreatesAndClearsTombstone() {
        val db = newTestDb()
        db.create("posts", remotePost("p1", "x"))
        db.transaction { db.markSynced("posts", "p1") }
        db.delete("posts", "p1") // now tombstoned
        assertTrue(db.isTombstoned("posts", "p1"))

        db.transaction {
            applyRemoteChanges(db, changes("posts" to TableChangeSet(created = listOf(remotePost("p1", "reborn")))))
        }
        assertEquals("reborn", db.find("posts", "p1")!!["title"])
        assertFalse(db.isTombstoned("posts", "p1"))
    }

    @Test
    fun updatedButMissingIsInserted() {
        val db = newTestDb()
        db.transaction {
            applyRemoteChanges(db, changes("posts" to TableChangeSet(updated = listOf(remotePost("p1", "ins")))))
        }
        val row = db.find("posts", "p1")!!
        assertEquals("ins", row["title"])
        assertEquals(SyncStatus.SYNCED, row.status())
    }

    @Test
    fun updatedButLocallyDeletedIsSkipped() {
        val db = newTestDb()
        db.create("posts", remotePost("p1", "x"))
        db.transaction { db.markSynced("posts", "p1") }
        db.delete("posts", "p1")

        db.transaction {
            applyRemoteChanges(db, changes("posts" to TableChangeSet(updated = listOf(remotePost("p1", "should not resurrect")))))
        }
        assertNull(db.find("posts", "p1")) // still deleted
        assertTrue(db.isTombstoned("posts", "p1")) // tombstone preserved for push
    }

    @Test
    fun deleteRemovesRowAndClearsTombstone() {
        val db = newTestDb()
        db.create("posts", remotePost("p1", "x"))
        db.transaction { db.markSynced("posts", "p1") }
        db.delete("posts", "p1") // tombstoned locally too

        db.transaction {
            applyRemoteChanges(db, changes("posts" to TableChangeSet(deleted = listOf("p1"))))
        }
        assertNull(db.find("posts", "p1"))
        assertFalse(db.isTombstoned("posts", "p1"))
    }

    @Test
    fun requiresUpdateSkipsWriteWhenSyncedRowUnchanged() {
        val db = newTestDb()
        // insert a synced row identical to what the remote will send
        db.transaction {
            applyRemoteChanges(db, changes("posts" to TableChangeSet(created = listOf(remotePost("p1", "same", "body", true)))))
        }
        val logger = RecordingLogger()
        db.transaction {
            applyRemoteChanges(
                db,
                changes("posts" to TableChangeSet(updated = listOf(remotePost("p1", "same", "body", true)))),
                logger = logger,
            )
        }
        val counts = logger.lastApplied!!
        assertEquals(0, counts.updated)
        assertEquals(1, counts.skipped)
    }

    @Test
    fun localChangesArePreservedOnRemoteUpdate() {
        val db = newTestDb()
        db.create("posts", remotePost("p1", "t", "b"))
        db.transaction { db.markSynced("posts", "p1") }
        db.update("posts", "p1", mapOf("title" to "my local title")) // _changed = title

        db.transaction {
            applyRemoteChanges(db, changes("posts" to TableChangeSet(updated = listOf(remotePost("p1", "server title", "server body")))))
        }
        val row = db.find("posts", "p1")!!
        assertEquals("my local title", row["title"]) // local edit preserved
        assertEquals("server body", row["body"])      // server wins on untouched column
        assertEquals(SyncStatus.UPDATED, row.status()) // still dirty -> will push
    }

    @Test
    fun pluggableResolverOverridesDefault() {
        val db = newTestDb()
        db.create("posts", remotePost("p1", "t", "b"))
        db.transaction { db.markSynced("posts", "p1") }
        db.update("posts", "p1", mapOf("title" to "local"))

        // Resolver that always takes the remote value wholesale.
        val serverWins = ConflictResolver { _, _, remote, _ ->
            remote.toMutableMap().apply {
                put(SyncColumns.STATUS, SyncStatus.SYNCED)
                put(SyncColumns.CHANGED, "")
            }
        }
        db.transaction {
            applyRemoteChanges(
                db,
                changes("posts" to TableChangeSet(updated = listOf(remotePost("p1", "server", "sb")))),
                conflictResolver = serverWins,
            )
        }
        val row = db.find("posts", "p1")!!
        assertEquals("server", row["title"]) // override beat the default preservation
        assertEquals(SyncStatus.SYNCED, row.status())
    }

    @Test
    fun rejectsRemoteRowWithoutId() {
        val db = newTestDb()
        assertFailsWith<IllegalArgumentException> {
            db.transaction {
                applyRemoteChanges(db, changes("posts" to TableChangeSet(created = listOf(mapOf("title" to "no id")))))
            }
        }
    }

    @Test
    fun rejectsRemoteRowCarryingInternalColumns() {
        val db = newTestDb()
        assertFailsWith<IllegalArgumentException> {
            db.transaction {
                applyRemoteChanges(
                    db,
                    changes("posts" to TableChangeSet(created = listOf(mapOf("id" to "p1", SyncColumns.STATUS to "created")))),
                )
            }
        }
    }
}
