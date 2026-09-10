package com.syncdb.transport.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Generic, backend-agnostic password login helper for JSON auth endpoints.
 *
 * Posts `{ <emailField>: email, <passwordField>: password }` to [loginUrl] and
 * reads the token out of the JSON response at [tokenPath] (a dot-separated path,
 * e.g. `accessToken` or `data.token`). This is a convenience only — the sync
 * engine itself has no notion of auth; how you obtain and attach a token is your
 * app's concern.
 *
 * @return the token string.
 */
suspend fun passwordLogin(
    client: HttpClient,
    loginUrl: String,
    email: String,
    password: String,
    emailField: String = "email",
    passwordField: String = "password",
    tokenPath: String = "accessToken",
    json: Json = Json { ignoreUnknownKeys = true },
): String {
    val resp = client.post(loginUrl) {
        contentType(ContentType.Application.Json)
        setBody(
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put(emailField, email)
                    put(passwordField, password)
                },
            ),
        )
    }
    if (resp.status != HttpStatusCode.OK && resp.status != HttpStatusCode.Created) {
        error("Login failed: ${resp.status} ${resp.bodyAsText().take(300)}")
    }
    val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
    val token = root.atPath(tokenPath)?.jsonPrimitive?.content
    return token ?: error("Login response had no token at '$tokenPath' (keys: ${root.keys})")
}

/** Follow a dot-separated path through nested JSON objects. */
private fun JsonObject.atPath(path: String): JsonElement? {
    var current: JsonElement = this
    for (segment in path.split(".")) {
        current = (current as? JsonObject)?.get(segment) ?: return null
    }
    return current
}
