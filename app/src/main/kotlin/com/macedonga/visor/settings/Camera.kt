package com.macedonga.visor.settings

import com.macedonga.visor.core.assistant.AssistantConfigStore
import com.macedonga.visor.core.settings.GlassesSettings
import com.macedonga.visor.core.settings.same
import com.macedonga.visor.log.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The glasses' own camera switches for Meta AI. With either off, they refuse every question about what the wearer sees. */
val GLASSES_CAMERA_KEYS = setOf("cloud_ai_understanding_enabled", "camera_use_enabled")

/** Visor's "Use the camera" is the one switch: whenever it changes, and after every read, the glasses' two follow it. */
fun keepCameraInLine(scope: CoroutineScope, settings: GlassesSettings, store: AssistantConfigStore, log: EventLog) {
    scope.launch {
        combine(
            store.config.map { it.vision }.distinctUntilChanged(),
            settings.state.map { it.readAt }.distinctUntilChanged(),
        ) { vision, readAt -> vision to readAt }.collect { (vision, readAt) ->
            if (readAt == null) return@collect
            val state = settings.state.value
            val want = vision.toString()
            // Not one already on its way, nor one the glasses have refused: that would write it after every read.
            val sent = GLASSES_CAMERA_KEYS
                .filter { it !in state.pending && it !in state.kept && !same(settings.catalogue[it]?.kind, settings.value(it), want) }
                .filter { settings.write(it, want) }
            if (sent.isNotEmpty()) log.add("Camera: the glasses' own camera use follows Visor's switch, ${if (vision) "on" else "off"}")
        }
    }
}
