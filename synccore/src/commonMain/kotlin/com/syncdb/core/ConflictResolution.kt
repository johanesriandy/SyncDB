package com.syncdb.core

/**
 * Pluggable conflict-resolution override. Given the local row, the incoming
 * remote row, and the engine's default [resolvedRaw], return the row to write.
 * Return [resolvedRaw] unchanged to keep the default behavior.
 */
fun interface ConflictResolver {
    fun resolve(table: String, local: Raw, remote: Raw, resolved: Raw): Raw
}

/**
 * The default WatermelonDB conflict rule — resolved PER COLUMN:
 * the server wins for every column EXCEPT columns the user changed locally
 * (those listed in the local row's `_changed`), which are preserved.
 *
 * Faithful port of `resolveConflict` in WatermelonDB `src/sync/impl/helpers`.
 *
 * - If the local row is (transiently) marked deleted, it is returned as-is:
 *   the local deletion wins and will be pushed later.
 * - Otherwise the remote values are merged over the local row, then `id`,
 *   `_status` and `_changed` are forced back to the local values (a remote
 *   update never changes local sync bookkeeping), and finally every
 *   locally-changed column is restored from the local row.
 */
fun resolveConflict(local: Raw, remote: Raw): Raw {
    if (local.status() == SyncStatus.DELETED) {
        // Local deletion pending — keep it; it will be pushed on the next push phase.
        return local
    }

    // remote merged over local (remote values take precedence)...
    val resolved: MutableRaw = local.toMutableMap()
    for ((column, value) in remote) {
        resolved[column] = value
    }

    // ...but sync bookkeeping always stays local.
    resolved[SyncColumns.ID] = local[SyncColumns.ID]
    resolved[SyncColumns.STATUS] = local[SyncColumns.STATUS]
    resolved[SyncColumns.CHANGED] = local[SyncColumns.CHANGED]

    // ...except columns the user changed locally, which are preserved.
    for (column in local.changedColumns()) {
        if (local.containsKey(column)) {
            resolved[column] = local[column]
        }
    }
    return resolved
}
