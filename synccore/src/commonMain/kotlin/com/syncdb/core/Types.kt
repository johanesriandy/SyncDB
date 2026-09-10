package com.syncdb.core

/**
 * A single database row as a column-name -> value map.
 *
 * Value types are constrained to what SQLite (and the sync protocol) can carry:
 * [String], [Long], [Double], [Boolean], or `null`. The [SyncableTable] descriptor
 * says which type each column uses, so binding/reading stays type-correct.
 */
typealias Raw = Map<String, Any?>

/** Convenience mutable form used while building a row. */
typealias MutableRaw = MutableMap<String, Any?>

/** A record identifier (the text primary key `id`). */
typealias Id = String

/** Internal bookkeeping column names. These are never sent to the remote. */
object SyncColumns {
    const val ID = "id"
    const val STATUS = "_status"
    const val CHANGED = "_changed"

    /** All columns the remote must never receive. */
    val INTERNAL = setOf(STATUS, CHANGED)
}

/**
 * Local sync status of a row.
 * - [SYNCED]: in sync with the remote, no local edits pending.
 * - [CREATED]: created locally, never pushed.
 * - [UPDATED]: previously synced, edited locally since.
 * - [DELETED]: only used transiently by the conflict resolver's short-circuit
 *   (our storage model keeps deletions as tombstones, not in-place status).
 */
object SyncStatus {
    const val SYNCED = "synced"
    const val CREATED = "created"
    const val UPDATED = "updated"
    const val DELETED = "deleted"
}
