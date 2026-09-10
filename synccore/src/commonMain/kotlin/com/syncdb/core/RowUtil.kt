package com.syncdb.core

/** Returns a copy of [raw] without the internal `_status` / `_changed` columns. */
fun Raw.stripInternal(): Raw = filterKeys { it !in SyncColumns.INTERNAL }

/** The comma-separated `_changed` column list, as a clean list of names. */
fun Raw.changedColumns(): List<String> {
    val raw = this[SyncColumns.CHANGED] as? String ?: return emptyList()
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

/** The `_status` value of a row, or [SyncStatus.SYNCED] if absent. */
fun Raw.status(): String = this[SyncColumns.STATUS] as? String ?: SyncStatus.SYNCED

/** The `id` of a row. Throws if missing. */
fun Raw.id(): Id = this[SyncColumns.ID] as? Id
    ?: error("Row is missing its 'id': $this")

/** Merges [_changed] column names, de-duplicated, order-preserving. */
fun mergeChanged(existing: String, added: Collection<String>): String {
    val set = LinkedHashSet<String>()
    existing.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { set.add(it) }
    added.forEach { if (it.isNotEmpty()) set.add(it) }
    return set.joinToString(",")
}
