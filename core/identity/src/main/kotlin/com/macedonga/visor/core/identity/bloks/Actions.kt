package com.macedonga.visor.core.identity.bloks

/** One action call: who called it (screen, context), with what, and helpers to read its arguments and call back. */
internal class ActionCall(val session: BloksSession, val screen: BloksSession.Screen?, val ctx: Ctx, val name: String, val args: List<Any?>) {
    fun at(i: Int): Any? = args.getOrNull(i)

    fun str(i: Int): String? = at(i) as? String

    fun node(i: Int): Node? = at(i) as? Node

    fun num(i: Int): Double = (at(i) as? Number)?.toDouble() ?: 0.0

    /** Calls a script value (a lambda), if there is one. */
    fun call(fn: Any?, vararg a: Any?): Any? = if (fn is Lambda) session.interpreter.call(fn, a.toList()) else null

    val interpreter get() = session.interpreter
}

internal typealias Handler = ActionCall.() -> Any?

/** Every Bloks action this runtime answers, by name. A name that is not here is logged, traced and skipped. */
internal object Actions {
    val table: Map<String, Handler> = Registry().apply {
        core()
        platform()
        tree()
    }.handlers
}

/** Where each group of actions registers its handlers. */
internal class Registry {
    val handlers = HashMap<String, Handler>()

    fun on(vararg names: String, handler: Handler) = names.forEach { handlers[it] = handler }
}

/** State, payloads, inputs, the server, screens and dialogs, and how the login ends. */
internal fun Registry.core() {
    // ---- State, scripts, payloads, templates ----
    on("bk.action.bloks.GetVariable2", "bk.action.bloks.GetVariableWithScope") { session.variable(screen, at(0) as String) }
    on("bk.action.bloks.WriteLocalState") {
        val id = at(0) as String
        val s = screen
        if (s != null) s.globalKeys[id]?.let { session.globals[it] = at(1) } ?: s.vars.put(id, at(1))
        null
    }
    on("bk.action.bloks.WriteGlobalConsistencyStore") {
        session.globals[at(0) as String] = at(1)
        null
    }
    on("bk.action.bloks.UpdateGlobalConsistencyStore") {
        val key = at(0) as String
        session.globals[key] = call(at(1), session.globals[key])
        null
    }
    on("bk.action.bloks.GetScript") { ctx.script(at(0) as String) }
    on("bk.action.bloks.GetParameter") { screen?.params?.get(at(0)) }
    on("bk.action.bloks.asynccomponents.GetClientParamV2") { null }
    on("bk.action.core.GetTemplateArg") {
        val n = at(0) as String
        val nested = ctx as? BloksSession.Nested
        nested?.templateArg(n) ?: nested?.templateArg(n.removePrefix("_"))
    }
    on("bk.action.bloks.GetPayload") { instance(at(0) as String, at(1) as? Map<*, *>) }
    on("bk.action.bloks.ParseHoistedPayload") { instance(at(0) as String, null) }
    on("bk.action.bloks.ParseEmbedded") {
        val text = at(0) as String
        val json = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        // Either a whole Bloks answer, or {"payload": "<the answer as a string>"}.
        val payload = if (json.containsKey("layout")) Payload.of(json) else Payload.parse((json["payload"] as kotlinx.serialization.json.JsonPrimitive).content)
        val nested = session.Nested(payload, screen, ctx)
        session.declare(payload, nested)
        BloksSession.Instance(payload, nested)
    }
    on("bk.action.template.Make") {
        val parent = (ctx as? BloksSession.Nested)?.payload ?: screen?.payload
        val payload = when (val t = at(0)) {
            is BloksSession.Instance -> t.payload
            is String -> parent?.embedded?.get(t) ?: screen?.payload?.embedded?.get(t)
                ?: (parent?.templates?.get(t) ?: screen?.payload?.templates?.get(t))?.let { (parent ?: screen!!.payload).withTree(it) }
                ?: throw BloksException("no template $t")
            else -> throw BloksException("template ${Interpreter.kind(t)}")
        }
        val templateArgs = at(1) as? Map<*, *> ?: emptyMap<Any, Any>()
        val key = "template:${System.identityHashCode(payload)}:${at(2)}"
        val s = screen
        val nested = s?.instances?.getOrPut(key) { session.Nested(payload, s, ctx, templateArgs) } ?: session.Nested(payload, null, ctx, templateArgs)
        nested.templateArgs = templateArgs
        session.declare(payload, nested)
        BloksSession.Instance(payload, nested)
    }
    on("bk.action.bloks.GetValue") {
        val id = at(0) as String
        val payload = (ctx as? BloksSession.Nested)?.payload ?: screen?.payload
        val script = payload?.values?.get(id) ?: screen?.payload?.values?.get(id) ?: throw BloksException("Value for referenced id $id not found!")
        screen?.values?.getOrPut(id) { interpreter.run(script.expr, ctx) } ?: interpreter.run(script.expr, ctx)
    }
    on("bk.action.const.Get") { ((ctx as? BloksSession.Nested)?.payload ?: screen?.payload)?.constants?.get(at(0)) ?: screen?.payload?.constants?.get(at(0)) }
    on("bk.action.bloks.WithScope") { call(at(0)) }
    on("bk.action.bloks.Reduce") { null }
    on("bk.action.tree.Make") {
        val attrs = HashMap<Int, Any?>()
        var i = 1
        while (i + 1 < args.size) {
            attrs[Interpreter.int(args[i])] = args[i + 1]
            i += 2
        }
        Node(Interpreter.int(at(0)), attrs)
    }
    on("bk.action.ref.Make") { Ref(at(0)) }
    on("bk.action.ref.Read") { (at(0) as? Ref)?.value }
    on("bk.action.ref.Write") {
        (at(0) as? Ref)?.value = at(1)
        null
    }
    on("bk.action.context.Get") { screen }
    // A callback object holds its lambda and the scope it was made in; a lambda already carries its context.
    on("bk.action.callback.Make", "bk.action.callback.MakeWithScopeOnly") { at(0) }
    on("bk.action.callback.Apply") { at(0)?.let { interpreter.call(it, args.drop(1)) } }

    // ---- Text inputs ----
    on("bk.action.textinput.GetText") { node(0)?.takeIf { it[BloksSession.INPUT_SENSITIVE] != true }?.let { session.text(screen, it) } }
    on("bk.action.textinput.GetSensitiveText") { node(0)?.let { session.text(screen, it) } }
    on("bk.action.textinput.SetTextV2") {
        node(0)?.let { screen?.texts?.put(it, at(1)?.toString().orEmpty()) }
        null
    }
    on("bk.action.text_input.ClearText") {
        node(0)?.let { screen?.texts?.put(it, "") }
        null
    }
    on("bk.action.text_input.AppendText", "bk.action.text_input.InsertTextAtCursor") {
        node(0)?.let { screen?.texts?.put(it, session.text(screen, it) + at(1)?.toString().orEmpty()) }
        null
    }
    on("bk.action.text_input.KeyboardEventBackspace") {
        node(0)?.let { n -> screen?.texts?.put(n, session.text(screen, n).dropLast(num(1).toInt().coerceAtLeast(0))) }
        null
    }
    // The cursor of a field Visor draws stays at the end of its text.
    on("bk.action.textinput.GetSelectionStart", "bk.action.textinput.GetSelectionEnd") { node(0)?.let { session.text(screen, it).length } }
    on("bk.action.bloks.DismissKeyboard", "bk.action.bloks.ClearFocus") {
        session.focus = null
        session.dismissCount++
        null
    }
    on("bk.action.bloks.ShowKeyboard", "bk.action.bloks.RequestFocus") {
        val n = node(0)
        if (n != null) {
            session.focus = n
            session.focusCount++
        }
        n != null
    }

    // ---- The server ----
    on("bk.action.bloks.AsyncActionWithDataManifestV2") {
        val appId = at(0) as String
        val callbacks = node(2)
        val props = (callbacks?.get(40) as? Lambda)?.let { interpreter.call(it, emptyList()) as? Map<*, *> }
        val s = screen
        session.request("action $appId", { session.client.action(appId, flat(at(1))) }, { payload ->
            session.runAction(payload, s, props)
            call(callbacks?.get(36), null, s)
        }, {
            call(callbacks?.get(35), s)
        })
        null
    }
    // The first version sends its params as `{k:v,}`, not JSON, and calls back with plain lambdas.
    on("bk.action.bloks.AsyncActionWithDataManifest") {
        val appId = at(0) as String
        val params = (at(1) as? Map<*, *>).orEmpty().entries.joinToString("", "{", "}") { (k, v) -> "$k:$v," }
        val success = at(2)
        val failure = at(3)
        val s = screen
        session.request("action $appId", { session.client.action(appId, params) }, { payload ->
            session.runAction(payload, s, null)
            call(success, null, s)
        }, {
            call(failure)
        })
        null
    }
    on(
        "bk.action.bloks.FetchAsyncComponents", "bk.action.bloks.ClearCachedAsyncComponents", "bk.action.bloks.InvalidateCachedAsyncComponents",
        "bk.action.AsyncComponentCacheWrite", "bk.action.bloks.MarkerAnnotateAsyncComponentResponse", "bk.action.bloks.pando.PublishGraphQLJSON",
    ) { null }
    on("bk.action.bloks.ReadPandoField") { (at(0) as? Map<*, *>)?.get(at(1)) ?: throw BloksException("ReadPandoField on ${Interpreter.kind(at(0))}") }

    // ---- Dialogs and toasts ----
    on("bk.action.dialog.OpenDialog") {
        val d = node(0)!!
        fun button(attr: Int): Pair<String, Lambda?>? = (d[attr] as? Node)?.let { (it[36]?.toString() ?: "OK") to (it[35] as? Lambda) }
        session.dialog = BloksSession.Dialog(d[40] as? String, d[35] as? String, listOfNotNull(button(38), button(44), button(36)))
        null
    }
    on("bk.action.toast.ShowToastV2") {
        val words = (at(0) as? BloksSession.Instance)?.let { words(it) }
        if (!words.isNullOrBlank()) {
            session.toast = words
            session.toastMillis = (node(1)?.get(35) as? Number)?.toLong() ?: 4000L
            session.toastCount++
        }
        null
    }
    on("bk.action.toast.DismissToast") {
        session.toast = null
        null
    }

    // ---- How the login ends ----
    on("bk.mt.action.DeliverOidcAuthCode") {
        session.log("server delivered an OIDC code")
        session.finish(BloksSession.Outcome.Code(at(0) as String, at(2) as String, at(3) as String))
        null
    }
    on("bk.fx.action.CompleteMetaLogin", "bk.action.mwa.CompleteFRLLoginWithCallback") {
        session.log("server completed the login ($name)")
        session.finish(BloksSession.Outcome.Token(at(0) as String, at(1) as? String))
        null
    }
    on("bk.action.mma.CompleteTokenExchangeV2") {
        val auth = BloksJson.decode(node(0)!![41] as String) as Map<*, *>
        session.log("server completed the login (token exchange v2)")
        session.finish(BloksSession.Outcome.Token(auth["access_token"] as String, auth["uid"]?.toString()))
        call(at(1))
        null
    }
}

/** An embedded payload of the current answer or screen, made ready to draw, its variables declared. */
private fun ActionCall.instance(id: String, vars: Map<*, *>?): BloksSession.Instance {
    val parent = (ctx as? BloksSession.Nested)?.payload ?: screen?.payload
    val embedded = parent?.embedded?.get(id) ?: screen?.payload?.embedded?.get(id) ?: throw BloksException("no embedded payload $id")
    val s = screen
    val nested = s?.instances?.getOrPut("payload:$id") { session.Nested(embedded, s, ctx) } ?: session.Nested(embedded, null, ctx)
    session.declare(embedded, nested)
    vars?.forEach { (k, v) -> if (s != null && !s.vars.containsKey(k.toString())) s.vars[k.toString()] = v }
    return BloksSession.Instance(embedded, nested)
}

/** A Lispy map flattened the way `DUI.A01` does: every key and value through `String.valueOf`. */
internal fun flat(map: Any?): String {
    val m = map as? Map<*, *> ?: return "{}"
    return BloksJson.encode(m.entries.associate { (k, v) -> k.toString() to v.toString() }, boolsAsInts = false)
}

/** The words of an embedded payload (a toast, a tooltip), as they would read on screen. */
internal fun ActionCall.words(instance: BloksSession.Instance): String? {
    val tree = instance.payload.tree ?: return null
    val s = screen ?: return null
    val ui = UiBuilder(interpreter, s, session.darkMode, { session.text(s, it) }, s.started, session.trace).build(tree, instance.ctx)
    return Flat.of(ui).items.mapNotNull { item ->
        when (item) {
            is Heading -> item.text
            is Paragraph -> item.text
            is Action -> item.label
            else -> null
        }
    }.joinToString(" ")
}
