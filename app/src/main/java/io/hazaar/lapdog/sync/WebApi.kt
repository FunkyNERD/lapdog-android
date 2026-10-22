package io.hazaar.lapdog.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The LapDog website's device API (lapdog-web docs/dev/device-api.md), the same one
 * LapDog LCD dashboards use. Blocking: call it off the main thread.
 */
class WebApi(private val baseUrl: String = BASE_URL, private val version: String) {
    class Response(val status: Int, val body: JsonObject?) {
        val ok get() = status in 200..299

        /** The website's message, written to be shown as-is, else a generic one. */
        fun message(what: String): String =
            body?.get("message")?.jsonPrimitive?.contentOrNull ?: "$what failed (HTTP $status)"

        fun str(vararg path: String): String? = at(*path)?.jsonPrimitive?.contentOrNull
        fun long(vararg path: String): Long? = at(*path)?.jsonPrimitive?.longOrNull

        private fun at(vararg path: String): JsonElement? =
            path.fold(body as JsonElement?) { e, k -> (e as? JsonObject)?.get(k) }

        val counters get() = long("counters", "race")?.let { Counters(it, long("counters", "epoch") ?: 0) }
    }

    fun pair(code: String, name: String): Response =
        request("POST", "/api/v1/pair", null, json.encodeToString(PairBody.serializer(), PairBody(code, name, version, kind = "app")))

    fun device(token: String) = request("GET", "/api/v1/device", token)

    fun race(token: String, localId: Long, race: RaceUpload) =
        request("PUT", "/api/v1/races/$localId", token, json.encodeToString(RaceUpload.serializer(), race))

    fun unpair(token: String) = request("POST", "/api/v1/unpair", token)

    /** @throws IOException when the website can't be reached. */
    private fun request(method: String, path: String, token: String?, body: String? = null): Response {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-Firmware-Version", version)
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = c.responseCode
            val text = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() }
            val obj = text?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            return Response(status, obj)
        } finally {
            c.disconnect()
        }
    }

    @kotlinx.serialization.Serializable
    private data class PairBody(val code: String, val name: String, val firmware: String, val kind: String)

    companion object {
        const val BASE_URL = "https://lapdog.funkynerd.com"
        private val json = Json { encodeDefaults = false; explicitNulls = false }
    }
}
