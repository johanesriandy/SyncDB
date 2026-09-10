package com.syncdb.core

/**
 * Finalizes a successful push. Faithful port of `markLocalChangesAsSynced`
 * in WatermelonDB `src/sync/impl/markAsSynced`. Must run inside a transaction.
 *
 * For each pushed created/updated row:
 *  - if its id is in [rejectedIds] -> leave it dirty (retry next sync),
 *  - else if the row's CURRENT stripped raw still equals what was pushed
 *    (the user did not edit it again during the push) -> mark `synced`, clear `_changed`,
 *  - else -> leave it dirty so the new edit re-syncs next time.
 *
 * For each pushed deleted id not in [rejectedIds] -> drop its tombstone
 * (the deletion is now complete on the remote).
 */
fun markLocalChangesAsSynced(
    store: LocalStore,
    local: LocalChanges,
    rejectedIds: Map<String, List<Id>> = emptyMap(),
    logger: SyncLogger = SyncLogger.NONE,
) {
    for ((table, tableChanges) in local.changes) {
        val rejected = rejectedIds[table]?.toSet() ?: emptySet()

        // created + updated
        for (pushedRow in tableChanges.created + tableChanges.updated) {
            val id = pushedRow.id()
            if (id in rejected) continue

            val current = store.find(table, id) ?: continue
            val pushed = local.affectedRecords[LocalChanges.affectedKey(table, id)] ?: continue

            // Compare what is in the DB now (stripped) against what we actually pushed.
            if (rawEquals(current.stripInternal(), pushed)) {
                store.markSynced(table, id)
            }
            // else: edited during push -> stays dirty, re-syncs next time.
        }

        // deleted
        for (id in tableChanges.deleted) {
            if (id in rejected) continue
            store.removeTombstone(table, id)
        }
    }
    logger.onPhase(SyncPhase.MARKING_SYNCED)
}
