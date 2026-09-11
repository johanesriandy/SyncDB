package com.syncdb.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The public sync API. Orchestrates the WatermelonDB synchronize() lifecycle:
 * pull -> apply remote (in one transaction, guarded) -> fetch local -> push ->
 * mark synced. Faithful port of `synchronize` in WatermelonDB `src/sync`.
 *
 * DB calls are synchronous; the transport calls are suspend (network). For a
 * responsive app, invoke [synchronize] from a background dispatcher.
 */
class SyncEngine(
    private val db: SqlLocalDatabase,
    private val transport: SyncTransport,
    private val options: SyncOptions = SyncOptions(),
) {
    private val logger: SyncLogger = options.logger
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)

    /** Observe sync progress and outcomes. */
    fun observeSyncState(): Flow<SyncState> = _state.asStateFlow()

    /** True if there are local changes (created/updated rows or tombstones) awaiting push. */
    suspend fun hasUnsyncedChanges(): Boolean {
        for (table in db.schema.tableNames) {
            if (db.findByStatus(table, SyncStatus.CREATED).isNotEmpty()) return true
            if (db.findByStatus(table, SyncStatus.UPDATED).isNotEmpty()) return true
            if (db.deletedIds(table).isNotEmpty()) return true
        }
        return false
    }

    /** Run one full sync cycle. */
    suspend fun synchronize(): SyncResult {
        emit(SyncState.Running(SyncPhase.STARTING))
        logger.onPhase(SyncPhase.STARTING)
        try {
            // 1. Read the watermark we start from.
            val lastPulledAt = db.lastPulledAt()
            val previousSchemaVersion = db.lastPulledSchemaVersion()

            // 2. Resolve the schema version (from the schema, unless overridden).
            val schemaVersion = options.schemaVersion ?: db.schema.version
            val enabledAt = options.migrationsEnabledAtVersion

            // Migration sync: when the local schema advanced past the version we
            // last pulled at, ask the remote to backfill the new tables/columns.
            var pullCursor = lastPulledAt
            var migration: Migration? = null
            if (enabledAt != null && lastPulledAt != null &&
                previousSchemaVersion != null && previousSchemaVersion < schemaVersion
            ) {
                if (previousSchemaVersion < enabledAt) {
                    // Migration sync wasn't available at that version — full resync.
                    logger.onWarning(
                        "last_pulled_schema_version $previousSchemaVersion is below " +
                            "migrationsEnabledAtVersion $enabledAt; performing a full resync.",
                    )
                    pullCursor = null
                } else {
                    migration = db.migrations?.migrationInfo(previousSchemaVersion, schemaVersion)
                        ?: Migration(from = previousSchemaVersion, to = schemaVersion)
                }
            }

            // 3. Pull.
            emit(SyncState.Running(SyncPhase.PULLING))
            logger.onPhase(SyncPhase.PULLING)
            val pull = transport.pullChanges(pullCursor, schemaVersion, migration)
            if (pull.timestamp <= 0L) {
                throw InvalidPullTimestampException(
                    "pullChanges returned a non-positive timestamp: ${pull.timestamp}"
                )
            }

            // 4. Apply remote changes + advance watermark in ONE guarded transaction.
            emit(SyncState.Running(SyncPhase.APPLYING_REMOTE))
            logger.onPhase(SyncPhase.APPLYING_REMOTE)
            val applied = db.transaction {
                // 4a. Concurrency guard: the watermark must be unchanged since step 1.
                val current = db.lastPulledAt()
                if (current != lastPulledAt) {
                    throw ConcurrentSyncException(
                        "Watermark changed during sync (expected $lastPulledAt, found $current); aborting."
                    )
                }
                // 4b. Apply.
                val counts = applyRemoteChanges(db, pull.changes, options.conflictResolver, logger)
                // 4c. Persist the new watermark (remote clock).
                val schemaToPersist =
                    if (enabledAt != null) schemaVersion else previousSchemaVersion
                db.setWatermark(pull.timestamp, schemaToPersist)
                counts
            }

            // 5. Gather local changes.
            val local = fetchLocalChanges(db, logger)

            // 6. Push (only if there is something to push).
            var pushedCreated = 0
            var pushedUpdated = 0
            var pushedDeleted = 0
            var rejected = 0
            if (!local.isEmpty()) {
                emit(SyncState.Running(SyncPhase.PUSHING))
                logger.onPhase(SyncPhase.PUSHING)
                val toPush = prepareForPush(local.changes)
                val push = transport.pushChanges(toPush, pull.timestamp)

                emit(SyncState.Running(SyncPhase.MARKING_SYNCED))
                db.transaction {
                    markLocalChangesAsSynced(db, local, push.rejectedIds, logger)
                }

                local.changes.forEach { (_, c) ->
                    pushedCreated += c.created.size
                    pushedUpdated += c.updated.size
                    pushedDeleted += c.deleted.size
                }
                rejected = push.rejectedIds.values.sumOf { it.size }
            }

            val result = SyncResult(
                newLastPulledAt = pull.timestamp,
                remoteApplied = applied,
                pushedCreated = pushedCreated,
                pushedUpdated = pushedUpdated,
                pushedDeleted = pushedDeleted,
                rejected = rejected,
            )
            logger.onPhase(SyncPhase.DONE)
            emit(SyncState.Succeeded(result))
            return result
        } catch (t: Throwable) {
            logger.onPhase(SyncPhase.FAILED)
            emit(SyncState.Failed(t))
            throw t
        }
    }

    /** Apply [SyncOptions.sendCreatedAsUpdated] by folding created rows into the updated bucket. */
    private fun prepareForPush(changes: DatabaseChangeSet): DatabaseChangeSet {
        if (!options.sendCreatedAsUpdated) return changes
        return changes.mapValues { (_, c) ->
            TableChangeSet(
                created = emptyList(),
                updated = c.created + c.updated,
                deleted = c.deleted,
            )
        }
    }

    private fun emit(state: SyncState) {
        _state.value = state
    }
}
