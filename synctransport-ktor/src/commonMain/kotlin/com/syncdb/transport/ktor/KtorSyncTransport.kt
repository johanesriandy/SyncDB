package com.syncdb.transport.ktor

import com.syncdb.core.DatabaseChangeSet
import com.syncdb.core.Migration
import com.syncdb.core.PullResult
import com.syncdb.core.PushResult
import com.syncdb.core.Raw
import com.syncdb.core.SyncSchema
import com.syncdb.core.SyncTransport
import com.syncdb.core.SyncableColumn
import com.syncdb.core.ColumnType
import com.syncdb.core.SyncableTable
import com.syncdb.core.TableChangeSet
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A [SyncTransport] implemented over Ktor. It speaks the WatermelonDB pull/push
 * JSON shape and converts between JSON rows and core [Raw] maps using the
 * [SyncSchema] descriptors (so numeric/boolean columns keep their types). Core
 * has no Ktor dependency; this lives in its own module.
 *
 * Wire shapes:
 * ```
 * GET  {pullPath}?last_pulled_at=..&schema_version=..[&migration=<json>]
 *   -> { "changes": { "<table>": { "created":[..], "updated":[..], "deleted":[..] } }, "timestamp": 123 }
 * POST {pushPath}?last_pulled_at=..   body: { "changes": { ... } }
 *   -> optional { "rejectedIds": { "<table>": ["id", ..] } }
 * ```
 */
class KtorSyncTransport(
    private val client: HttpClient,
    private val schema: SyncSchema,
    private val pullPath: String = "/sync/pull",
    private val pushPath: String = "/sync/push",
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    /** Query-parameter name for the pull cursor (WatermelonDB default `last_pulled_at`). */
    private val lastPulledAtParam: String = "last_pulled_at",
    /** Query-parameter name for the schema version (WatermelonDB default `schema_version`). */
    private val schemaVersionParam: String = "schema_version",
    /** Extra fixed query params sent on every pull AND push (e.g. `version=3`, `scope=shared`). */
    private val extraQueryParams: Map<String, String> = emptyMap(),
) : SyncTransport {

    override suspend fun pullChanges(
        lastPulledAt: Long?,
        schemaVersion: Int,
        migration: Migration?,
    ): PullResult {
        val response = client.get(pullPath) {
            url.parameters.apply {
                if (lastPulledAt != null) append(lastPulledAtParam, lastPulledAt.toString())
                append(schemaVersionParam, schemaVersion.toString())
                if (migration != null) append("migration", encodeMigration(migration))
                extraQueryParams.forEach { (k, v) -> append(k, v) }
            }
        }
        return parsePull(response.bodyAsText())
    }

    override suspend fun pushChanges(
        changes: DatabaseChangeSet,
        lastPulledAt: Long,
    ): PushResult {
        val body = buildJsonObject {
            put("changes", encodeChanges(changes))
        }
        val response = client.post(pushPath) {
            url.parameters.append(lastPulledAtParam, lastPulledAt.toString())
            extraQueryParams.forEach { (k, v) -> url.parameters.append(k, v) }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }
        return parsePush(response.bodyAsText())
    }

    // ------------------------------------------------------------------
    // Parsing (JSON -> core)
    // ------------------------------------------------------------------

    internal fun parsePull(text: String): PullResult {
        val root = json.parseToJsonElement(text).jsonObject
        val timestamp = root["timestamp"]?.jsonPrimitive?.long
            ?: error("pull response missing 'timestamp'")
        val changesObj = root["changes"]?.jsonObject ?: JsonObject(emptyMap())
        val changes = buildMap<String, TableChangeSet> {
            for ((tableName, tableEl) in changesObj) {
                if (!schema.contains(tableName)) continue // ignore unregistered tables
                val table = schema[tableName]
                val obj = tableEl.jsonObject
                put(
                    tableName,
                    TableChangeSet(
                        created = obj.rowArray("created").map { decodeRow(table, it) },
                        updated = obj.rowArray("updated").map { decodeRow(table, it) },
                        deleted = obj.idArray("deleted"),
                    ),
                )
            }
        }
        return PullResult(changes, timestamp)
    }

    internal fun parsePush(text: String): PushResult {
        if (text.isBlank()) return PushResult()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return PushResult()
        val rejectedObj = root["rejectedIds"]?.jsonObject ?: return PushResult()
        val rejected = rejectedObj.mapValues { (_, v) ->
            v.jsonArray.map { it.jsonPrimitive.content }
        }
        return PushResult(rejected)
    }

    private fun decodeRow(table: SyncableTable, obj: JsonObject): Raw {
        val row = LinkedHashMap<String, Any?>()
        row["id"] = obj["id"]?.let { it.asStringOrNull() }
        for (col in table.columns) {
            val el = obj[col.name] ?: continue
            row[col.name] = decodeValue(col, el)
        }
        return row
    }

    private fun decodeValue(col: SyncableColumn, el: JsonElement): Any? {
        if (el is JsonNull) return null
        val prim = el.jsonPrimitive
        return when (col.type) {
            ColumnType.TEXT -> prim.contentIfNotNull()
            ColumnType.INTEGER -> prim.longOrNull
            ColumnType.REAL -> prim.doubleOrNull
            ColumnType.BOOLEAN -> prim.booleanOrNull ?: (prim.longOrNull?.let { it != 0L })
        }
    }

    // ------------------------------------------------------------------
    // Encoding (core -> JSON)
    // ------------------------------------------------------------------

    private fun encodeChanges(changes: DatabaseChangeSet): JsonObject = buildJsonObject {
        for ((tableName, tableChanges) in changes) {
            val table = schema[tableName]
            put(tableName, buildJsonObject {
                put("created", encodeRows(table, tableChanges.created))
                put("updated", encodeRows(table, tableChanges.updated))
                put("deleted", buildJsonArray { tableChanges.deleted.forEach { add(JsonPrimitive(it)) } })
            })
        }
    }

    private fun encodeRows(table: SyncableTable, rows: List<Raw>): JsonArray = buildJsonArray {
        rows.forEach { add(encodeRow(table, it)) }
    }

    private fun encodeRow(table: SyncableTable, raw: Raw): JsonObject = buildJsonObject {
        // id first, then declared domain columns; never internal columns.
        put("id", JsonPrimitive(raw["id"] as? String))
        for (col in table.columns) {
            if (raw.containsKey(col.name)) {
                put(col.name, encodeValue(raw[col.name]))
            }
        }
    }

    private fun encodeValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun encodeMigration(migration: Migration): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("from", migration.from)
            put("to", migration.to)
            put("tables", buildJsonArray { migration.tables.forEach { add(JsonPrimitive(it)) } })
            put("columns", buildJsonArray {
                migration.columns.forEach { mc ->
                    add(buildJsonObject {
                        put("table", mc.table)
                        put("columns", buildJsonArray { mc.columns.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        },
    )

    private fun JsonObject.rowArray(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    private fun JsonObject.idArray(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.contentIfNotNull()

    private fun JsonPrimitive.contentIfNotNull(): String? = if (this is JsonNull) null else content
}
