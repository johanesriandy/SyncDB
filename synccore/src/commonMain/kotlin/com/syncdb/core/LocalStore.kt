package com.syncdb.core

/**
 * Row-level local storage the sync algorithm operates against. Kept as an
 * interface so the pure algorithm functions ([applyRemoteChanges],
 * [fetchLocalChanges], [markLocalChangesAsSynced]) can be unit-tested against
 * an in-memory SQLite driver (or a fake) with no engine wiring.
 *
 * All methods are synchronous; callers wrap groups of them in a transaction
 * via [LocalDatabase.transaction].
 */
interface LocalStore {

    /** The registered schema. */
    val schema: SyncSchema

    /** Find a row by id, or `null` if absent. Includes bookkeeping columns. */
    fun find(table: String, id: Id): Raw?

    /** All rows in [table] whose `_status` equals [status]. */
    fun findByStatus(table: String, status: String): List<Raw>

    /** The tombstoned ids for [table] (from `_sync_deleted`). */
    fun deletedIds(table: String): List<Id>

    /** True if [id] currently has a tombstone in [table]. */
    fun isTombstoned(table: String, id: Id): Boolean

    /** Insert a full row (must include `id`, `_status`, `_changed`). */
    fun insert(table: String, raw: Raw)

    /** Replace an existing row by its `id` (must include `_status`, `_changed`). */
    fun updateRow(table: String, raw: Raw)

    /** Hard-delete a row by id (no tombstone written). */
    fun destroy(table: String, id: Id)

    /** Add a tombstone for [id] in [table]. */
    fun addTombstone(table: String, id: Id)

    /** Remove the tombstone for [id] in [table], if any. */
    fun removeTombstone(table: String, id: Id)

    /** Set a row's status to `synced` and clear `_changed`. */
    fun markSynced(table: String, id: Id)
}
