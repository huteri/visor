package com.macedonga.visor.core.identity.bloks

/** A screen ready to draw: every binding evaluated, every handler an [Event] the session runs. */
sealed interface Ui {
    val style: Style
}

class Edges(val top: Float = 0f, val bottom: Float = 0f, val start: Float = 0f, val end: Float = 0f) {
    val isZero get() = top == 0f && bottom == 0f && start == 0f && end == 0f
}

/** What a node asks of its parent and of its own box. Sizes stay strings: `"100%"`, `"48dp"`, `"auto"` or null. */
class Style(
    val width: String? = null,
    val height: String? = null,
    val grow: Float = 0f,
    val alignSelf: String? = null,
    val margin: Edges = Edges(),
    val padding: Edges = Edges(),
    val background: Long? = null,
    val radius: Float = 0f,
    val borderWidth: Float = 0f,
    val borderColor: Long? = null,
    val alpha: Float = 1f,
    val invisible: Boolean = false,
)

class Event(val handler: Lambda, val args: List<Any?>)

/** A size in dp, or null for "auto", percentages and nothing. */
fun bloksDp(value: String?): Float? = if (value == null || value.endsWith("%") || value == "auto") null else UiBuilder.dim(value)

class Box(
    override val style: Style,
    val column: Boolean,
    val justify: String?,
    val align: String?,
    val children: List<Ui>,
    val onClick: Event?,
    val enabled: Boolean,
    val scroll: Boolean,
) : Ui

class Span(
    val text: String,
    val sizeSp: Float?,
    val weight: String?,
    val color: Long?,
    val underline: Boolean,
    val strike: Boolean,
    val onClick: Event?,
)

class Text(override val style: Style, val spans: List<Span>, val maxLines: Int, val align: String?, val onClick: Event?) : Ui

class Input(
    override val style: Style,
    val node: Node,
    val text: String,
    val hint: String,
    /** What the field is, from its accessibility extension ("Mobile number or email"). */
    val label: String,
    val keyboard: String?,
    val secret: Boolean,
    val maxLength: Int,
    val ime: String?,
    val enabled: Boolean,
    val sizeSp: Float?,
    val color: Long?,
    val onChange: Event?,
    val onSubmit: Event?,
    val onFocus: Event?,
    val onBlur: Event?,
) : Ui

class Image(override val style: Style, val url: String?) : Ui

class Spinner(override val style: Style, val sizeDp: Float?, val color: Long?) : Ui

/**
 * Walks a screen's component tree into [Ui]. Only what a login needs: boxes, rich text, inputs, images, spinners.
 * Component and attribute ids are those of the Meta AI 294 runtime (`X/IIV`).
 */
internal class UiBuilder(
    private val interpreter: Interpreter,
    private val screen: BloksSession.Screen,
    private val dark: Boolean,
    private val text: (Node) -> String,
    private val started: MutableSet<Any>,
    private val log: (String) -> Unit,
) {
    /** True when a lifecycle script ran during the walk: state may have changed, so walk again. */
    var ranLifecycle = false
        private set

    fun build(node: Node, ctx: Ctx): Ui? {
        val a = resolve(node, ctx)
        runLifecycle(node, a, ctx)
        if (a[49] == "gone") return null
        val style = style(node, a)
        return when (node.type) {
            SCREEN -> Box(style, column = true, justify = null, align = null, children = listOfNotNull(child(a[40], ctx), child(a[36], ctx)), onClick = null, enabled = true, scroll = true)
            SCREEN_QUERY -> child(a[36], ctx)
            FLEXBOX -> Box(
                style,
                column = (a[41] as? String)?.startsWith("column") == true,
                justify = a[44] as? String,
                align = a[36] as? String,
                children = children(node, a[32], ctx),
                onClick = event(a[45], ctx, node),
                enabled = a[40] != false,
                scroll = false,
            )
            COLLECTION -> Box(style, column = a[42] != "row", justify = null, align = null, children = children(node, a[32], ctx), onClick = null, enabled = true, scroll = true)
            RICH_TEXT -> Text(style, spans(node, a, ctx), maxLines(a[40]), a[42] as? String, event(a[41], ctx, node))
            PLAIN_TEXT -> Text(
                style,
                listOf(Span(a[41]?.toString().orEmpty(), dim(a[45]), a[46] as? String, (a[48] as? Node)?.let(::color) ?: hex(a[43]), false, false, null)),
                maxLines(a[38]),
                a[42] as? String,
                event(a[40], ctx, node),
            )
            INPUT -> Input(
                style,
                node,
                text(node),
                a[36]?.toString().orEmpty(),
                label(a, ctx),
                a[56] as? String,
                secret = a[99] == true || a[56] == "password" || a[56] == "passcode",
                maxLength = (a[40] as? Number)?.toInt() ?: -1,
                ime = a[70] as? String,
                enabled = a[74] != false,
                sizeSp = dim(a[53]),
                color = (a[55] as? Node)?.let(::color),
                onChange = event(a[48], ctx, node),
                onSubmit = event(a[46], ctx, node),
                onFocus = event(a[42], ctx, node),
                onBlur = event(a[45], ctx, node),
            )
            IMAGE -> Image(style, (if (dark) a[36] ?: a[41] else a[41]) as? String)
            SPINNER -> {
                val o = a[35] as? Node
                Spinner(style, dim(o?.get(40)), (o?.get(38) as? Node)?.let(::color))
            }
            SPINNER_V1 -> Spinner(style, dim((a[35] as? Node)?.get(36)), ((a[35] as? Node)?.get(35) as? Node)?.let(::color))
            GLIMMER -> Box(style, column = true, justify = null, align = null, children = emptyList(), onClick = null, enabled = true, scroll = false)
            else -> {
                val kids = children(node, a[32], ctx)
                if (kids.isEmpty()) {
                    unknown(node.type)
                    null
                } else {
                    Box(style, column = true, justify = null, align = null, children = kids, onClick = null, enabled = true, scroll = false)
                }
            }
        }
    }

    private fun child(value: Any?, ctx: Ctx): Ui? = when (value) {
        is Node -> build(value, ctx)
        is BloksSession.Instance -> value.payload.tree?.let { build(it, value.ctx) }
        is List<*> -> value.firstOrNull()?.let { child(it, ctx) }
        else -> null
    }

    private fun children(node: Node, value: Any?, ctx: Ctx): List<Ui> {
        val list = screen.children[node] ?: node[BloksSession.ID]?.toString()?.let { screen.replaced[it] } ?: (value as? List<*>) ?: listOfNotNull(value)
        return list.flatMap { if (it is Node && it.type == FRAGMENT) (it[32] as? List<*>).orEmpty() else listOf(it) }.mapNotNull { child(it, ctx) }
    }

    /** The node's attributes with its bindings (attribute 135: `[key, script, ...]`) evaluated in its context. */
    private fun resolve(node: Node, ctx: Ctx): Map<Int, Any?> {
        val set = screen.attrs[node]
        val bindings = node[135] as? List<*> ?: return if (set == null) node.attrs else node.attrs + set
        val out = HashMap(node.attrs)
        var i = 0
        while (i + 1 < bindings.size) {
            val key = bindings[i] as? String
            val script = bindings[i + 1] as? Script
            if (key != null && key.length == 1 && script != null) {
                try {
                    out[key[0].code] = interpreter.run(script.expr, ctx)
                } catch (error: Exception) {
                    log("binding ${key[0].code} of ${node.type} failed: ${error.message}")
                }
            }
            i += 2
        }
        set?.let { out.putAll(it) }
        return out
    }

    /** Extensions (attribute 133) that set up state: 13914 runs 41 and 42 at the first bind, 16529 runs 35. */
    private fun runLifecycle(node: Node, a: Map<Int, Any?>, ctx: Ctx) {
        val extensions = a[133] as? List<*> ?: return
        for (ext in extensions.filterIsInstance<Node>()) {
            val scripts = when (ext.type) {
                LIFECYCLE -> listOf(ext[41], ext[42])
                RENDER_LIFECYCLE -> listOf(ext[35])
                else -> continue
            }
            if (!started.add(Pair(ext, ctx))) continue
            for (s in scripts) {
                val e = event(s, ctx, node) ?: continue
                try {
                    interpreter.call(e.handler, e.args)
                    ranLifecycle = true
                } catch (error: Exception) {
                    log("lifecycle of ${node.type} failed: ${error.message}")
                }
            }
        }
    }

    /** A field's name: its accessibility description (14001.35, its tooltip 50) or the legacy one (13313.36). */
    private fun label(a: Map<Int, Any?>, ctx: Ctx): String {
        val ext = (a[133] as? List<*>).orEmpty().filterIsInstance<Node>()
        val text = ext.firstOrNull { it.type == ACCESSIBILITY }?.let { resolve(it, ctx) }?.let { it[35] ?: it[50] }
            ?: ext.firstOrNull { it.type == ACCESSIBILITY_LEGACY }?.let { resolve(it, ctx) }?.get(36)
        return text?.toString()?.trim()?.trimEnd(',')?.trim().orEmpty()
    }

    private fun event(value: Any?, ctx: Ctx, node: Node): Event? = when (value) {
        is Script -> Event(Lambda(value.expr, emptyList(), ctx), listOf(node, screen))
        is Lambda -> Event(value, listOf(node, screen))
        else -> null
    }

    private fun spans(node: Node, a: Map<Int, Any?>, ctx: Ctx): List<Span> {
        val out = ArrayList<Span>()
        fun add(item: Any?, inherited: List<Node>) {
            val n = item as? Node ?: return
            val na = resolve(n, ctx)
            when (n.type) {
                SPAN_GROUP -> (na[36] as? List<*>).orEmpty().forEach { add(it, inherited + (na[38] as? List<*>).orEmpty().filterIsInstance<Node>()) }
                SPAN -> {
                    val styles = inherited + (na[36] as? List<*>).orEmpty().filterIsInstance<Node>().map { Node(it.type, resolve(it, ctx)) }
                    val font = styles.lastOrNull { it.type == FONT }
                    out += Span(
                        text = na[38]?.toString().orEmpty(),
                        sizeSp = dim(font?.get(41)),
                        weight = (font?.get(42) as? String) ?: (font?.get(35) as? String)?.let { if ("Bold" in it) "bold" else if ("Medium" in it) "medium" else null },
                        color = (styles.lastOrNull { it.type == COLOR }?.get(35) as? Node)?.let(::color),
                        underline = styles.lastOrNull { it.type == UNDERLINE }?.get(35) == true,
                        strike = styles.lastOrNull { it.type == STRIKE }?.get(35) == true,
                        onClick = styles.lastOrNull { it.type == LINK }?.let { event(it[36], ctx, node) },
                    )
                }
                LEGACY_SPAN -> out += Span(na[38]?.toString().orEmpty(), dim(na[42]), na[43] as? String, (na[44] as? Node)?.let(::color), na[52] == true, na[45] == true, event(na[36], ctx, node))
            }
        }
        val list = if (a[68] == true) a[69] else a[32]
        (list as? List<*>).orEmpty().forEach { add(it, emptyList()) }
        return out.filter { it.text.isNotEmpty() }
    }

    private fun style(node: Node, a: Map<Int, Any?>): Style {
        val c = (a[132] as? Node)?.let { Node(it.type, it.attrs) }
        val deco = a[38] as? Node
        val decorated = node.type == FLEXBOX && deco?.type == DECORATION
        val bg = if (decorated) (deco!![35] as? Node)?.let { drawable -> (drawable[35] as? Node)?.let(::color) ?: hex(drawable[35]) } else null
        val alpha = (a[141] as? Number)?.toFloat()
            ?: (a[133] as? List<*>)?.filterIsInstance<Node>()?.firstOrNull { it.type == TRANSFORMS }?.get(141)?.let { (it as? Number)?.toFloat() }
            ?: 1f
        return Style(
            width = c?.get(68)?.toString(),
            height = c?.get(42)?.toString(),
            grow = (c?.get(41) as? Number)?.toFloat() ?: 0f,
            alignSelf = c?.get(35) as? String,
            margin = Edges(dim(c?.get(50)) ?: 0f, dim(c?.get(44)) ?: 0f, dim(c?.get(49) ?: c?.get(46)) ?: 0f, dim(c?.get(45) ?: c?.get(48)) ?: 0f),
            padding = Edges(
                dim(c?.get(61) ?: a[59]) ?: 0f,
                dim(c?.get(55) ?: a[54]) ?: 0f,
                dim(c?.get(59) ?: c?.get(57) ?: a[58] ?: a[56]) ?: 0f,
                dim(c?.get(56) ?: c?.get(58) ?: a[55] ?: a[57]) ?: 0f,
            ),
            background = bg,
            radius = if (decorated) dim(deco!![46]) ?: 0f else 0f,
            borderWidth = if (decorated) dim(deco!![40]) ?: 0f else 0f,
            borderColor = if (decorated) (deco!![38] as? Node)?.let(::color) ?: hex(deco[36]) else null,
            alpha = alpha,
            invisible = a[49] == "invisible",
        )
    }

    private fun color(node: Node): Long? = hex(if (dark) node[35] ?: node[36] else node[36] ?: node[35])

    private fun hex(value: Any?): Long? {
        val s = (value as? String)?.removePrefix("#") ?: return null
        val v = s.toLongOrNull(16) ?: return null
        return when (s.length) {
            8 -> v
            6 -> 0xFF000000 or v
            else -> null
        }
    }

    private fun maxLines(value: Any?): Int = (value as? Number)?.toLong()?.let { if (it <= 0 || it > 1000) Int.MAX_VALUE else it.toInt() } ?: Int.MAX_VALUE

    private val reported = HashSet<Int>()

    private fun unknown(type: Int) {
        if (reported.add(type)) log("component $type not drawn")
    }

    companion object {
        const val SCREEN = 14093
        const val SCREEN_QUERY = 16675
        const val FLEXBOX = 13320
        const val COLLECTION = 13317
        const val RICH_TEXT = 13329
        const val PLAIN_TEXT = 13335
        const val INPUT = 13336
        const val IMAGE = 13323
        const val SPINNER = 23990
        const val SPINNER_V1 = 15728
        const val GLIMMER = 15778
        const val DECORATION = 13314
        const val SPAN_GROUP = 16887
        const val SPAN = 16880
        const val LEGACY_SPAN = 13339
        const val FONT = 16870
        const val COLOR = 16875
        const val LINK = 16873
        const val STRIKE = 16874
        const val UNDERLINE = 16876
        const val LIFECYCLE = 13914
        const val RENDER_LIFECYCLE = 16529
        const val TRANSFORMS = 13688
        const val ACCESSIBILITY = 14001
        const val ACCESSIBILITY_LEGACY = 13313

        /** A fragment: its children stand in its place. */
        const val FRAGMENT = 13346

        /** `"16dp"`, `"16sp"`, `"16.00sp"`, a bare number: dp. Pixels at the 3x the query declares. Percentages: null. */
        fun dim(value: Any?): Float? = when (value) {
            is Number -> value.toFloat()
            is String -> when {
                value.endsWith("dp") -> value.removeSuffix("dp").toFloatOrNull()
                value.endsWith("sp") -> value.removeSuffix("sp").toFloatOrNull()
                value.endsWith("px") -> value.removeSuffix("px").toFloatOrNull()?.div(3f)
                else -> value.toFloatOrNull()
            }
            else -> null
        }
    }
}
