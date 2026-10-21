package com.macedonga.visor.core.assistant

import android.util.Base64
import com.macedonga.visor.core.assistant.mcp.PHOTO_BASE64
import com.macedonga.visor.core.assistant.mcp.PHOTO_DATA_URL
import com.macedonga.visor.core.assistant.mcp.withPhoto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockStatic

class McpPhotoTest {
    @Test fun replacesNestedPhotoValuesWithoutChangingLoggedArguments() {
        val photo = byteArrayOf(1, 2, 3)
        val arguments = JSONObject().put("image", PHOTO_BASE64).put("nested", JSONArray()
            .put(JSONObject().put("url", PHOTO_DATA_URL)).put("ordinary").put(42).put(JSONObject.NULL))
        val original = arguments.toString()
        mockStatic(Base64::class.java).use { encoding ->
            encoding.`when`<String> { Base64.encodeToString(photo, Base64.NO_WRAP) }.thenReturn("AQID")
            val result = withPhoto(arguments, photo)
            assertEquals("AQID", result.getString("image"))
            assertEquals("data:image/jpeg;base64,AQID", result.getJSONArray("nested").getJSONObject(0).getString("url"))
            assertEquals("ordinary", result.getJSONArray("nested").getString(1))
            assertEquals(42, result.getJSONArray("nested").getInt(2))
            assertTrue(result.getJSONArray("nested").isNull(3))
            assertEquals(original, arguments.toString())
        }
    }

    @Test fun noPhotoLeavesArgumentsIntact() {
        val arguments = JSONObject().put("image", PHOTO_BASE64)
        assertSame(arguments, withPhoto(arguments, null))
        assertEquals(PHOTO_BASE64, arguments.getString("image"))
    }
}
