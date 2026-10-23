package com.macedonga.visor.core.assistant

import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.ConcurrentHashMap

/** One process-wide URL factory, shared by deterministic production-transport tests. */
internal object TestHttpConnections {
    val handlers = ConcurrentHashMap<String, (URL) -> URLConnection>()
    init {
        URL.setURLStreamHandlerFactory { protocol ->
            if (!protocol.startsWith("visor-")) null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = handlers.getValue(url.host)(url)
            }
        }
    }
}
