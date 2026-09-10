package com.syncdb.transport.ktor

import com.syncdb.core.ColumnType
import com.syncdb.core.SyncSchema
import com.syncdb.core.SyncableColumn
import com.syncdb.core.SyncableTable
import com.syncdb.core.TableChangeSet
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorSyncTransportTest {

    private val schema = SyncSchema(
        listOf(
            SyncableTable(
                "posts",
                listOf(
                    SyncableColumn("title", ColumnType.TEXT),
                    SyncableColumn("is_pinned", ColumnType.BOOLEAN),
                ),
            ),
        ),
    )

    @Test
    fun pullParsesChangesAndCoercesTypes() = runTest {
        val body = """
            {
              "changes": {
                "posts": {
                  "created": [{"id":"p1","title":"hi","is_pinned":true}],
                  "updated": [],
                  "deleted": ["p9"]
                }
              },
              "timestamp": 4242
            }
        """.trimIndent()
        val client = HttpClient(MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val transport = KtorSyncTransport(client, schema)

        val result = transport.pullChanges(lastPulledAt = null, schemaVersion = 1, migration = null)

        assertEquals(4242L, result.timestamp)
        val posts = result.changes["posts"]!!
        val row = posts.created.single()
        assertEquals("p1", row["id"])
        assertEquals("hi", row["title"])
        assertEquals(true, row["is_pinned"]) // coerced to Boolean via descriptor
        assertEquals(listOf("p9"), posts.deleted)
    }

    @Test
    fun pushEncodesRowsWithoutInternalColumnsAndReadsRejected() = runTest {
        var sentBody: String? = null
        val client = HttpClient(MockEngine { request ->
            sentBody = (request.body as TextContent).text
            respond(
                """{"rejectedIds":{"posts":["p1"]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val transport = KtorSyncTransport(client, schema)

        val changes = mapOf(
            "posts" to TableChangeSet(
                created = listOf(mapOf("id" to "p1", "title" to "t", "is_pinned" to false)),
            ),
        )
        val result = transport.pushChanges(changes, lastPulledAt = 99L)

        assertEquals(mapOf("posts" to listOf("p1")), result.rejectedIds)
        val sent = sentBody!!
        assertTrue(sent.contains("\"p1\""))
        assertTrue(sent.contains("\"title\""))
        assertTrue(!sent.contains("_status") && !sent.contains("_changed"))
    }
}
