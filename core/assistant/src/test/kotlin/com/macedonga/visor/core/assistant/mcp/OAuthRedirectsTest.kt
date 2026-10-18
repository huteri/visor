package com.macedonga.visor.core.assistant.mcp

import org.junit.Assert.*
import org.junit.Test

class OAuthRedirectsTest {
    @Test fun `app callback follows installed package without changing release`() {
        assertEquals(OAuthRedirects.APP, OAuthRedirects.app("com.macedonga.visor"))
        assertEquals("com.macedonga.visor.debug:/oauth", OAuthRedirects.app("com.macedonga.visor.debug"))
        assertEquals("http://127.0.0.1:47219/callback", OAuthRedirects.LOOPBACK)
    }
}
