package com.syncdb.core

/**
 * Collects the local changes to push. Faithful port of `fetchLocalChanges`
 * in WatermelonDB `src/sync/impl/fetchLocal`.
 *
 * Per table:
 *  - created = rows where `_status = 'created'`
 *  - updated = rows where `_status = 'updated'`
 *  - deleted = ids from `_sync_deleted` for the table
 *
 * Created/updated rows are stripped of `_status`/`_changed` before being placed
 * in the change set (the remote must never see them). The exact stripped
 * snapshots are also kept in [LocalChanges.affectedRecords] so
 * [markLocalChangesAsSynced] can later tell whether the row was edited again
 * while the push was in flight.
 */
fun fetchLocalChanges(
    store: LocalStore,
    logger: SyncLogger = SyncLogger.NONE,
): LocalChanges {
    val changes = LinkedHashMap<String, TableChangeSet>()
    val affected = LinkedHashMap<String, Raw>()
    var createdCount = 0
    var updatedCount = 0
    var deletedCount = 0

    for (table in store.schema.tableNames) {
        val createdRows = store.findByStatus(table, SyncStatus.CREATED)
        val updatedRows = store.findByStatus(table, SyncStatus.UPDATED)
        val deletedIds = store.deletedIds(table)

        if (createdRows.isEmpty() && updatedRows.isEmpty() && deletedIds.isEmpty()) {
            continue
        }

        val createdStripped = createdRows.map { it.stripInternal() }
        val updatedStripped = updatedRows.map { it.stripInternal() }

        createdStripped.forEach { affected[LocalChanges.affectedKey(table, it.id())] = it }
        updatedStripped.forEach { affected[LocalChanges.affectedKey(table, it.id())] = it }

        changes[table] = TableChangeSet(
            created = createdStripped,
            updated = updatedStripped,
            deleted = deletedIds,
        )

        createdCount += createdRows.size
        updatedCount += updatedRows.size
        deletedCount += deletedIds.size
    }

    logger.onLocalChangesCollected(createdCount, updatedCount, deletedCount)
    return LocalChanges(changes, affected)
}
