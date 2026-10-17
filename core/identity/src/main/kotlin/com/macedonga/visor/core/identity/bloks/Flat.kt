package com.macedonga.visor.core.identity.bloks

/**
 * A Meta screen reduced to what it asks of the user, in reading order: Meta's own layout (floating labels, fields of
 * zero height until focused, animated boxes) is left out, so the screen can be drawn in Visor's own look.
 */
sealed interface Item

class Heading(val text: String) : Item

class Paragraph(val spans: List<Span>, val error: Boolean) : Item {
    val text get() = spans.joinToString("") { it.text }
}

class Field(val input: Input, val label: String) : Item

class Action(val label: String, val event: Event, val primary: Boolean, val enabled: Boolean) : Item

class Screen(val back: Event?, val items: List<Item>, val loading: Boolean)

object Flat {
    fun of(ui: Ui?): Screen {
        var primaryTaken = false
        val out = ArrayList<Item>()
        var back: Event? = null
        var spinners = 0

        fun visible(u: Ui) = !u.style.invisible && u.style.alpha > 0.05f

        // A button's label may sit in a box faded out for a spinner: read it whatever its alpha.
        fun texts(u: Ui, into: MutableList<String>) {
            if (u.style.invisible) return
            when (u) {
                is Text -> u.spans.joinToString("") { it.text }.trim().takeIf { it.isNotEmpty() }?.let(into::add)
                is Box -> u.children.forEach { texts(it, into) }
                else -> {}
            }
        }

        fun inputs(u: Ui): List<Input> = when (u) {
            is Input -> listOf(u)
            is Box -> u.children.flatMap(::inputs)
            else -> emptyList()
        }

        fun filled(u: Ui): Boolean = u.style.background != null || (u is Box && u.children.any(::filled))

        fun walk(u: Ui) {
            // Code fields are a transparent input over drawn boxes: an input counts even when it is not seen.
            if (u is Input) {
                if (!u.style.invisible) out += Field(u, u.label)
                return
            }
            if (!visible(u) && inputs(u).isEmpty()) return
            when (u) {
                is Box -> {
                    val click = u.onClick
                    val fields = inputs(u)
                    if (fields.isNotEmpty() && click != null) {
                        val words = ArrayList<String>()
                        texts(u, words)
                        fields.forEach { f -> out += Field(f, f.label.ifEmpty { words.firstOrNull().orEmpty() }) }
                        return
                    }
                    if (click != null) {
                        val words = ArrayList<String>()
                        texts(u, words)
                        val label = words.distinct().joinToString(" ")
                        // A code field's drawn boxes, one character each, only focus the field.
                        if (words.isNotEmpty() && words.all { it.length <= 1 }) return
                        if (label.isEmpty()) {
                            if (back == null && out.none { it is Field || it is Action }) back = click
                        } else {
                            // The first filled button is the screen's main one; later filled ones are secondary.
                            val primary = filled(u) && !primaryTaken
                            if (primary) primaryTaken = true
                            out += Action(label, click, primary, enabled = u.enabled)
                        }
                        return
                    }
                    u.children.forEach(::walk)
                }
                is Text -> {
                    val spans = u.spans
                    if (spans.all { it.text.isBlank() }) return
                    val whole = u.onClick ?: spans.singleOrNull()?.onClick
                    if (whole != null) {
                        out += Action(spans.joinToString("") { it.text }.trim(), whole, primary = false, enabled = true)
                        return
                    }
                    val size = spans.mapNotNull { it.sizeSp }.maxOrNull() ?: 0f
                    val error = spans.any { s -> s.color?.let(::reddish) == true }
                    if (size >= 20f && !error) out += Heading(spans.joinToString("") { it.text }.trim()) else out += Paragraph(spans, error)
                }
                is Input -> out += Field(u, u.label)
                is Spinner -> spinners++
                is Image -> {}
            }
        }

        if (ui != null) walk(ui)
        // Labels the screen repeats around a field (a floating label and its copy) are the field's, not paragraphs.
        val labels = out.filterIsInstance<Field>().map { it.label.trim().trimEnd(',') }.toSet()
        val kept = out.filterNot { it is Paragraph && it.text.trim().trimEnd(',') in labels }
            .distinctBy { if (it is Paragraph) "p:${it.text}" else if (it is Heading) "h:${it.text}" else it }
        // Without a large text, the first line before the first field is the screen's title.
        val items = if (kept.any { it is Heading }) kept else {
            val first = kept.indexOfFirst { it is Paragraph && !it.error }
            val field = kept.indexOfFirst { it is Field }
            if (first >= 0 && (field < 0 || first < field)) kept.toMutableList().apply { set(first, Heading((kept[first] as Paragraph).text)) } else kept
        }
        return Screen(back, items, loading = items.isEmpty() && (spinners > 0 || ui == null))
    }

    private fun reddish(argb: Long): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r > 180 && g < 110 && b < 110
    }
}
