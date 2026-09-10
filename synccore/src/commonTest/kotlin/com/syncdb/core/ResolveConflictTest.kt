package com.syncdb.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ResolveConflictTest {

    @Test
    fun serverWinsExceptLocallyChangedColumns() {
        val local = postRow(
            id = "p1", title = "local title", body = "local body", isPinned = true,
            status = SyncStatus.UPDATED, changed = "title",
        )
        val remote = remotePost("p1", title = "remote title", body = "remote body", isPinned = false)

        val resolved = resolveConflict(local, remote)

        // title was locally changed -> kept
        assertEquals("local title", resolved["title"])
        // body/is_pinned not locally changed -> server wins
        assertEquals("remote body", resolved["body"])
        assertEquals(false, resolved["is_pinned"])
        // bookkeeping preserved from local
        assertEquals("p1", resolved["id"])
        assertEquals(SyncStatus.UPDATED, resolved[SyncColumns.STATUS])
        assertEquals("title", resolved[SyncColumns.CHANGED])
    }

    @Test
    fun multipleChangedColumnsPreserved() {
        val local = postRow(
            id = "p1", title = "L1", body = "L2", isPinned = true,
            status = SyncStatus.UPDATED, changed = "title,body",
        )
        val remote = remotePost("p1", title = "R1", body = "R2", isPinned = false)

        val resolved = resolveConflict(local, remote)

        assertEquals("L1", resolved["title"])
        assertEquals("L2", resolved["body"])
        assertEquals(false, resolved["is_pinned"]) // not changed locally
    }

    @Test
    fun deletedLocalShortCircuitsAndKeepsDeletion() {
        val local = postRow(
            id = "p1", title = "keep", status = SyncStatus.DELETED, changed = "",
        )
        val remote = remotePost("p1", title = "remote")

        val resolved = resolveConflict(local, remote)

        // returned as-is: the local deletion wins, still pushed later
        assertSame(local, resolved)
    }

    @Test
    fun noLocalChangesMeansServerWinsEverything() {
        val local = postRow(
            id = "p1", title = "old", body = "old", isPinned = true,
            status = SyncStatus.SYNCED, changed = "",
        )
        val remote = remotePost("p1", title = "new", body = "new", isPinned = false)

        val resolved = resolveConflict(local, remote)

        assertEquals("new", resolved["title"])
        assertEquals("new", resolved["body"])
        assertEquals(false, resolved["is_pinned"])
        assertEquals(SyncStatus.SYNCED, resolved[SyncColumns.STATUS])
    }
}
