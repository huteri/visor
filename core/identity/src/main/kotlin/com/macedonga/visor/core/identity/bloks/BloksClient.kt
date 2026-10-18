package com.macedonga.visor.core.identity.bloks

import com.macedonga.visor.core.identity.HttpPost
import com.macedonga.visor.core.identity.UrlConnectionPost
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Meta AI app's native Bloks channel: `FrlBloksAppRootQuery` opens a screen, `FrlBloksAsyncActionQuery` runs a
 * server action. Both are persisted GraphQL queries on meta.graph.meta.com, signed with the app's logged-out token.
 */
class BloksClient(
    private val token: String,
    private val locale: String,
    private val androidRelease: String,
    private val apiLevel: Int,
    private val http: HttpPost = UrlConnectionPost,
) {
    class BloksError(message: String) : Exception(message)

    fun screen(appId: String, params: String): Payload =
        Payload.parse(query(SCREEN_DOC, "FrlBloksAppRootQuery", appId, params, screen = true))

    fun action(appId: String, params: String): Payload =
        Payload.parse(query(ACTION_DOC, "FrlBloksAsyncActionQuery", appId, params, screen = false))

    /** A screen_query app, the way bk.action.screen.Open loads one: its first component is the screen. */
    fun component(appId: String, params: String): Payload {
        val bundle = Json.parseToJsonElement(query(COMPONENT_DOC, "FrlBloksAsyncComponentQuery", appId, params, screen = false, component = true)).jsonObject
        val response = bundle["component_query_responses"]!!.jsonArray.first().jsonObject
        val components = Json.parseToJsonElement(response["payload"]!!.jsonPrimitive.content).jsonObject["components"]!!.jsonArray
        return Payload.of(components.first().jsonObject["payload"]!!.jsonObject)
    }

    private fun query(doc: String, query: String, appId: String, params: String, screen: Boolean, component: Boolean = false): String {
        if (token.isEmpty()) throw BloksError("This build has no Meta app token. Add VISOR_META_FRL_TOKEN to local.properties and build again.")
        val variables = buildJsonObject {
            put("params", buildJsonObject {
                put("app_id", appId)
                put("bloks_versioning_id", VERSIONING_ID)
                put("params", params)
            })
            put("bk_context", buildJsonObject {
                put("is_flipper_enabled", false)
                put("debug_tooling_metadata_token", JsonNull)
                put("android_device_performance_class", JsonNull)
                put("android_os_api_level", apiLevel)
                put("gpu_memory_mb", 4096)
                if (screen) put("pixel_ratio", 3.0)
            })
        }
        val name = "$query-$appId"
        val body = listOf(
            "method" to "post",
            "format" to "json",
            "server_timestamps" to "true",
            "locale" to locale,
            "fb_api_req_friendly_name" to name,
            "client_doc_id" to doc,
            "variables" to variables.toString(),
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val reply = http.post(
            "$ENDPOINT?locale=$locale",
            mapOf(
                "Authorization" to "OAuth $token",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to userAgent,
                "X-FB-Friendly-Name" to name,
            ),
            body.toByteArray(),
        )
        val text = String(reply.body, Charsets.UTF_8)
        if (reply.status != 200) throw BloksError("$query for $appId answered HTTP ${reply.status}: ${text.take(300)}")
        val data = Json.parseToJsonElement(text).jsonObject["data"] as? JsonObject
            ?: throw BloksError("$query for $appId answered without data: ${text.take(300)}")
        val bundle = if (component) {
            data["bloks_component_query"]?.jsonObject?.get("bloks_bundle_component_query")
        } else if (screen) {
            data["bloks_app"]?.jsonObject?.get("bloks_bundle")?.jsonObject?.get("bloks_bundle_tree")
        } else {
            data["bloks_action"]?.jsonObject?.get("action")?.jsonObject?.get("action_bundle")?.jsonObject?.get("bloks_bundle_action")
        }
        return (bundle as? JsonPrimitive)?.content ?: throw BloksError("$query for $appId answered no bundle: ${text.take(300)}")
    }

    companion object {
        private const val ENDPOINT = "https://meta.graph.meta.com/graphql"
        private const val ACTION_DOC = "25388939413601934797287790300"
        private const val SCREEN_DOC = "6165507603411378493763367459"
        private const val COMPONENT_DOC = "26656874278801481548161588323"

        // The Bloks runtime version of Meta AI 294; the server shapes its scripts to it.
        private const val VERSIONING_ID = "563d6e0947a986c84a526eed86f47bc9f456d52f386d4757475b045e577e0541"
    }

    private val userAgent =
        "Dalvik/2.1.0 (Linux; U; Android $androidRelease) [FBAN/Stella;FBAV/294.1.0.25.165;FBSN/Android;FBSV/$androidRelease;FBLC/en_US]"
}
