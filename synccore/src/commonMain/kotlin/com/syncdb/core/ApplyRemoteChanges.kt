package com.syncdb.core

/**
 * Applies remote [changes] to the local store. Faithful port of
 * `applyRemoteChanges` in WatermelonDB `src/sync/impl/applyRemote`.
 *
 * Must be called inside a write transaction. Per table:
 *
 * CREATED rows:
 *  - local row with same id exists  -> treat as an update (warn),
 *  - else id is tombstoned          -> drop the tombstone, then insert fresh,
 *  - else                           -> insert (`_status='synced'`, `_changed=''`).
 *
 * UPDATED rows:
 *  - local row exists               -> [resolveConflict] (or [conflictResolver]), then write,
 *  - else id is tombstoned          -> skip (local deletion will be pushed later),
 *  - else                           -> insert (`_status='synced'`, `_changed=''`).
 *
 * DELETED ids:
 *  - hard-delete the local row and clear any tombstone for it.
 *
 * requiresUpdate optimization: if the local row is already `synced` and equal to
 * what we would write, the write is skipped.
 *
 * Guards: any remote row lacking an `id`, or carrying `_status`/`_changed`, is rejected.
 */
fun applyRemoteChanges(
    store: LocalStore,
    changes: DatabaseChangeSet,
    conflictResolver: ConflictResolver? = null,
    logger: SyncLogger = SyncLogger.NONE,
): ApplyCounts {
    var created = 0
    var updated = 0
    var deleted = 0
    var skipped = 0

    for ((table, tableChanges) in changes) {
        require(store.schema.contains(table)) {
            "Remote returned changes for unregistered table '$table'"
        }

        // --- created ---
        for (remoteRow in tableChanges.created) {
            guardRemoteRow(table, remoteRow)
            val id = remoteRow.id()
            val existing = store.find(table, id)
            when {
                existing != null -> {
                    logger.onWarning(
                        "Remote created row '$table.$id' already exists locally; treating as update."
                    )
                    if (applyRemoteUpdate(store, table, existing, remoteRow, conflictResolver, logger)) {
                        updated++
                    } else {
                        skipped++
                    }
                }
                store.isTombstoned(table, id) -> {
                    store.removeTombstone(table, id)
                    store.insert(table, syncedInsert(remoteRow))
                    created++
                }
                else -> {
                    store.insert(table, syncedInsert(remoteRow))
                    created++
                }
            }
        }

        // --- updated ---
        for (remoteRow in tableChanges.updated) {
            guardRemoteRow(table, remoteRow)
            val id = remoteRow.id()
            val existing = store.find(table, id)
            when {
                existing != null -> {
                    if (applyRemoteUpdate(store, table, existing, remoteRow, conflictResolver, logger)) {
                        updated++
                    } else {
                        skipped++
                    }
                }
                store.isTombstoned(table, id) -> {
                    // Local deletion pending; do not resurrect. It will be pushed later.
                    skipped++
                }
                else -> {
                    store.insert(table, syncedInsert(remoteRow))
                    created++
                }
            }
        }

        // --- deleted ---
        for (id in tableChanges.deleted) {
            store.destroy(table, id)
            store.removeTombstone(table, id)
            deleted++
        }
    }

    val counts = ApplyCounts(created, updated, deleted, skipped)
    logger.onRemoteApplied(counts)
    return counts
}

/**
 * Resolves and writes a remote update onto an existing local row.
 * @return true if a write happened, false if skipped by the requiresUpdate optimization.
 */
private fun applyRemoteUpdate(
    store: LocalStore,
    table: String,
    existing: Raw,
    remoteRow: Raw,
    conflictResolver: ConflictResolver?,
    logger: SyncLogger,
): Boolean {
    val defaultResolved = resolveConflict(existing, remoteRow)
    val resolved = conflictResolver?.resolve(table, existing, remoteRow, defaultResolved)
        ?: defaultResolved

    // requiresUpdate: a clean, already-synced row that would not change -> skip the write.
    if (existing.status() == SyncStatus.SYNCED && rawEquals(existing, resolved)) {
        return false
    }

    logger.onConflict(ConflictLog(table, existing.id(), existing, remoteRow, resolved))
    store.updateRow(table, resolved)
    return true
}

/** A remote row prepared for insert: server wins fully, marked synced/clean. */
private fun syncedInsert(remoteRow: Raw): Raw {
    val row: MutableRaw = remoteRow.toMutableMap()
    row[SyncColumns.STATUS] = SyncStatus.SYNCED
    row[SyncColumns.CHANGED] = ""
    return row
}

private fun guardRemoteRow(table: String, row: Raw) {
    require(row[SyncColumns.ID] is String && (row[SyncColumns.ID] as String).isNotEmpty()) {
        "Remote row for table '$table' is missing a valid 'id': $row"
    }
    require(SyncColumns.STATUS !in row && SyncColumns.CHANGED !in row) {
        "Remote row for '$table.${row[SyncColumns.ID]}' must not contain _status/_changed"
    }
}

/** Structural equality over the columns both rows share plus id/status/changed. */
internal fun rawEquals(a: Raw, b: Raw): Boolean {
    if (a.keys != b.keys) return false
    for (k in a.keys) {
        if (a[k] != b[k]) return false
    }
    return true
}
