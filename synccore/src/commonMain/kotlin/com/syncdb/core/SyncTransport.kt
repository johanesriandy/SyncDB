package com.syncdb.core

/**
 * The pluggable remote. Core depends on this interface only — never on a
 * concrete HTTP client. Implementations translate between the wire format
 * (typically JSON) and the [Raw] maps the engine works with.
 *
 * See `synctransport-ktor` for a Ktor-based implementation.
 */
interface SyncTransport {

    /**
     * Fetch remote changes since [lastPulledAt].
     *
     * @param lastPulledAt the last successful pull timestamp, or `null` on the
     *   very first sync (the remote should then return a full snapshot).
     * @param schemaVersion the local schema version.
     * @param migration optional migration info when migration sync is enabled.
     * @return the remote changes plus the remote timestamp to store as the new
     *   watermark. [PullResult.timestamp] must be positive and non-zero.
     */
    suspend fun pullChanges(
        lastPulledAt: Long?,
        schemaVersion: Int,
        migration: Migration?,
    ): PullResult

    /**
     * Push local [changes] to the remote. Rows never contain `_status`/`_changed`.
     *
     * @param lastPulledAt the timestamp from the pull that preceded this push.
     * @return optionally the ids the remote rejected.
     */
    suspend fun pushChanges(
        changes: DatabaseChangeSet,
        lastPulledAt: Long,
    ): PushResult
}
