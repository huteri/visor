package com.macedonga.visor.core.assistant

/** Metadata only: DataX caches message types, so type 5003 does not prove an explicit audio tag. */
internal class MicrophoneInputStats {
    private var chunks = 0L
    private var bytes = 0L
    private var min = Int.MAX_VALUE
    private var max = 0
    private var extensions = 0L
    private var bare = 0L
    private var audioType = 0L
    private var zeroType = 0L
    private var channel = -1
    private var channelChanges = 0L

    @Synchronized fun record(size: Int, type: Int, channelId: Int, hasExtension: Boolean) {
        chunks++
        bytes += size
        min = minOf(min, size)
        max = maxOf(max, size)
        if (hasExtension) extensions++ else bare++
        if (type == 5003) audioType++
        if (type == 0) zeroType++
        if (channel != -1 && channel != channelId) channelChanges++
        channel = channelId
    }

    @Synchronized fun snapshot(): String =
        "chunks=$chunks bytes=$bytes min=${if (chunks == 0L) 0 else min} max=$max " +
            "extensions=$extensions bare=$bare type5003=$audioType type0=$zeroType channel=$channel channelChanges=$channelChanges"
}
