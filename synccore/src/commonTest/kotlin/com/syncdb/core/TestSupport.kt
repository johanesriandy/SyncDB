package com.syncdb.core

import app.cash.sqldelight.db.SqlDriver

/** Provides a fresh in-memory SQLite driver per test. Actual per test target. */
expect fun createTestDriver(): SqlDriver

/** Opens a fresh in-memory [SqlLocalDatabase] with the example schema. */
fun newTestDb(schema: SyncSchema = ExampleSchema.schema): SqlLocalDatabase =
    SyncCore.openDatabase(createTestDriver(), schema)

/** Build a post row map quickly. */
fun postRow(
    id: String,
    title: String = "",
    body: String = "",
    isPinned: Boolean = false,
    status: String? = null,
    changed: String? = null,
): MutableRaw = LinkedHashMap<String, Any?>().apply {
    put("id", id)
    put("title", title)
    put("body", body)
    put("is_pinned", isPinned)
    if (status != null) put(SyncColumns.STATUS, status)
    if (changed != null) put(SyncColumns.CHANGED, changed)
}

/** A remote-style post row (no _status/_changed). */
fun remotePost(id: String, title: String = "", body: String = "", isPinned: Boolean = false): Raw =
    linkedMapOf<String, Any?>(
        "id" to id,
        "title" to title,
        "body" to body,
        "is_pinned" to isPinned,
    )

/** Configurable fake transport that records interactions. */
class FakeTransport(
    var pullResult: PullResult = PullResult(emptyMap(), 1L),
    var pushResult: PushResult = PushResult(),
) : SyncTransport {
    var pullCalls = 0
    var pushCalls = 0
    var lastPulledAtSeen: Long? = null
    var lastPulledAtWasNull = false
    var migrationSeen: Migration? = null
    val pushedChanges = mutableListOf<DatabaseChangeSet>()

    /** Runs just before pullResult is returned — used to simulate a concurrent writer. */
    var onBeforePull: (() -> Unit)? = null

    override suspend fun pullChanges(
        lastPulledAt: Long?,
        schemaVersion: Int,
        migration: Migration?,
    ): PullResult {
        pullCalls++
        lastPulledAtSeen = lastPulledAt
        lastPulledAtWasNull = lastPulledAt == null
        migrationSeen = migration
        onBeforePull?.invoke()
        return pullResult
    }

    override suspend fun pushChanges(changes: DatabaseChangeSet, lastPulledAt: Long): PushResult {
        pushCalls++
        pushedChanges.add(changes)
        return pushResult
    }
}

/** Logger that records applied counts and conflicts for assertions. */
class RecordingLogger : SyncLogger {
    var lastApplied: ApplyCounts? = null
    val conflicts = mutableListOf<ConflictLog>()
    val warnings = mutableListOf<String>()
    val phases = mutableListOf<SyncPhase>()

    override fun onRemoteApplied(counts: ApplyCounts) { lastApplied = counts }
    override fun onConflict(conflict: ConflictLog) { conflicts.add(conflict) }
    override fun onWarning(message: String) { warnings.add(message) }
    override fun onPhase(phase: SyncPhase) { phases.add(phase) }
}
