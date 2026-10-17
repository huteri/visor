package com.macedonga.visor.core.assistant

import org.json.JSONObject

/** Closed vocabulary only; never carries backend labels, arguments, names or call identifiers. */
enum class ActivityCategory { SEARCH, READING, COMPUTING, OTHER }
data class ActivityCount(val category: ActivityCategory, val running: Int, val completed: Int)
data class ActivitySnapshot(val activity: List<ActivityCount> = emptyList()) {
    fun metadata(elapsedMs: Long): String = "elapsed_seconds=${(elapsedMs.coerceIn(0, 120_000) / 1000)}; request=running; " +
        if (activity.isEmpty()) "activity=unknown" else activity.take(ActivityCategory.entries.size).joinToString("; ") {
            "${it.category.name.lowercase()}:running=${it.running.coerceIn(0, 128)},completed=${it.completed.coerceIn(0, 128)}"
        }
}

/** IDs exist only here for deduplication, with bounded per-response storage. */
internal class HermesProgress {
    private val calls = linkedMapOf<String, Pair<ActivityCategory, Boolean>>()
    fun accept(item: JSONObject): ActivitySnapshot? {
        val status = item.optString("status")
        if (status != "running" && status != "completed") return null
        val id = item.optString("toolCallId")
        if (id.isBlank() || id.length > 256) return null
        val previous = calls[id]
        if (previous == null && (status != "running" || calls.size >= 128)) return null
        val category = previous?.first ?: when (item.optString("tool")) {
            "web_search", "image_search" -> ActivityCategory.SEARCH
            "web_extract", "read_file" -> ActivityCategory.READING
            "terminal", "execute_code" -> ActivityCategory.COMPUTING
            else -> ActivityCategory.OTHER
        }
        // A duplicate or reordered running event cannot reopen a completed operation.
        calls[id] = category to (previous?.second == true || status == "completed")
        return ActivitySnapshot(ActivityCategory.entries.mapNotNull { kind ->
            val values = calls.values.filter { it.first == kind }
            if (values.isEmpty()) null else ActivityCount(kind, values.count { !it.second }, values.count { it.second })
        })
    }
}
