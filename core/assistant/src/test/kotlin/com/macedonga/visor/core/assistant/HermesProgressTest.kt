package com.macedonga.visor.core.assistant

import org.junit.Assert.*
import org.junit.Test

class HermesProgressTest {
    @Test fun namedProgressPublishesOnlyWhitelistedCountsAndKeepsAnswerParsing() {
        val snapshots = mutableListOf<ActivitySnapshot>()
        val stream = "event: hermes.tool.progress\ndata: {\"tool\":\"web_search\",\"toolCallId\":\"private-id\",\"status\":\"running\",\"label\":\"private arguments\"}\n\n" +
            "event: hermes.tool.progress\ndata: not-json\n\n" +
            "event: hermes.tool.progress\ndata: {\"tool\":\"injected-tool-name\",\"toolCallId\":\"second\",\"status\":\"running\"}\n\n" +
            "event: hermes.tool.progress\ndata: {\"tool\":\"web_search\",\"toolCallId\":\"private-id\",\"status\":\"completed\"}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"final\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        val result = ModelResponse.read(stream.reader(), false, onProgress = { snapshots += it })
        assertEquals("final", result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals(3, snapshots.size)
        assertEquals(listOf(ActivityCount(ActivityCategory.SEARCH, 0, 1), ActivityCount(ActivityCategory.OTHER, 1, 0)), snapshots.last().activity)
        val metadata = snapshots.last().metadata(15_000)
        assertFalse(metadata.contains("private"))
        assertFalse(metadata.contains("injected"))
        assertEquals("elapsed_seconds=15; request=running; search:running=0,completed=1; other:running=1,completed=0", metadata)
    }

    @Test fun observerFailureNeverAbortsTheOriginalAnswer() {
        val stream = "event: hermes.tool.progress\ndata: {\"tool\":\"terminal\",\"toolCallId\":\"id\",\"status\":\"running\"}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"final\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        ModelResponse.read(stream.reader(), false, onProgress = { error("observer failed") })
    }

    @Test fun trackerRejectsUnknownStatusAndDeduplicatesCompletion() {
        val tracker = HermesProgress()
        fun event(status: String) = org.json.JSONObject().put("tool", "terminal").put("toolCallId", "id").put("status", status)
        assertNull(tracker.accept(event("secret status")))
        assertNull(tracker.accept(event("completed")))
        tracker.accept(event("running"))
        tracker.accept(event("completed"))
        assertEquals(listOf(ActivityCount(ActivityCategory.COMPUTING, 0, 1)), tracker.accept(event("running"))!!.activity)
    }

}
