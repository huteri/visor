package com.macedonga.visor.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.macedonga.visor.BuildConfig
import com.macedonga.visor.core.identity.token.META_REDIRECT_URI
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(val login: MetaLogin) : ViewModel()

/**
 * Meta's login page, in a WebView the app owns, and nothing else. Everything the page does goes to the event
 * log, so one log from a phone that loops at the login says where it looped: every request to Meta, every
 * screen the page shows, every error, and what this WebView is.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MetaLoginPage(onDone: () -> Unit, model: LoginViewModel = hiltViewModel()) {
    val request = model.login.pending
    if (request == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val prefix = "$META_REDIRECT_URI?"
    val note: (String) -> Unit = { model.login.note(withoutQueries(it)) }
    AndroidView(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        factory = { context ->
            WebView(context).apply {
                // AndroidView leaves a View's layout params at wrap content, and a WebView that
                // wraps its height sizes the page's viewport to the page's own content.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.lookLikeChrome(note)
                describe(context, note)
                addJavascriptInterface(PageBridge(note), "VisorLog")
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(this, ANSWERS, setOf(ACCOUNTS))
                } else {
                    note("Bloks answers not visible on this WebView")
                }
                val agent = settings.userAgentString
                var caught = false
                // Set once the page itself goes to /oidc/ after a login step: a bounce back to /login/ after that is the loop.
                val loggedIn = AtomicBoolean(false)
                val askedDirectly = AtomicBoolean(false)
                fun take(url: String): Boolean {
                    if (!url.startsWith(prefix)) return false
                    if (!caught) {
                        caught = true
                        model.login.finish(url)
                        onDone()
                    }
                    return true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (take(url)) return true
                        val path = request.url.path.orEmpty()
                        note(
                            "navigating to ${request.url.place()}" + (if (request.isRedirect) " (redirect)" else "") + (if (request.hasGesture()) " (tap)" else "") +
                                ", params ${request.url.paramNames()}, cookies ${cookieNames(url)}",
                        )
                        if (path.startsWith("/oidc") && !request.isRedirect) loggedIn.set(true)
                        if (loggedIn.get() && request.isRedirect && path.startsWith("/login")) {
                            note("Meta answered /oidc/ with its login page: it did not take this as a logged-in session")
                        }
                        return false
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (!request.isForMainFrame) {
                            request.url.described()?.let { note("${request.method} $it") }
                            return null
                        }
                        // After the login, Visor asks /oidc/ itself before the page does, with the session the page holds now.
                        val url = request.url.toString()
                        if (!loggedIn.get() || !request.url.path.orEmpty().startsWith("/oidc") || !askedDirectly.compareAndSet(false, true)) return null
                        val headers = request.requestHeaders.filterKeys { !it.equals("X-Requested-With", ignoreCase = true) }
                        val answer = askOidc(url, agent, headers, note) ?: return null
                        val code = answer.code
                        val location = answer.location
                        // The page gets Meta's answer to Visor's request and never sends the same eaac a second time.
                        return when {
                            code != null -> {
                                view.post { take(code) }
                                empty()
                            }
                            location != null -> {
                                note("the page follows Meta's answer to ${Uri.parse(location).place()}")
                                view.post { view.loadUrl(location) }
                                empty()
                            }
                            else -> {
                                note("the page shows Meta's answer, HTTP ${answer.status}")
                                answer.page()
                            }
                        }
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (take(url)) view.stopLoading() else note("loading ${Uri.parse(url).place()}, cookies ${cookieNames(url)}")
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        note("loaded ${Uri.parse(url).place()}, cookies ${cookieNames(url)}")
                        view.evaluateJavascript(WATCH, null)
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.url.path.orEmpty().startsWith("/favicon")) return
                        note("HTTP ${response.statusCode} for ${request.url.described() ?: request.url.place()}")
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        note("${error.description} (${error.errorCode}) for ${request.url.described() ?: request.url.place()}")
                    }

                    // Without this a renderer the system reclaims takes the whole app down with it.
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        note(if (detail.didCrash()) "the page crashed" else "the system reclaimed the page")
                        if (!caught) {
                            caught = true
                            onDone()
                        }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        val text = message.message()
                        if (message.messageLevel() != ConsoleMessage.MessageLevel.LOG && "Permissions-Policy" !in text) {
                            note("console ${message.messageLevel().name.lowercase()}: ${text.take(200)}")
                        }
                        return true
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        // A page without a title gets its URL as one, query and all.
                        if (title.isNullOrBlank() || '/' in title || '?' in title) return
                        note("title: $title")
                    }
                }
                note("opening ${Uri.parse(request.url).place()}")
                loadUrl(request.url)
            }
        },
        onRelease = {
            model.login.note("page closed")
            it.destroy()
        },
    )
}

/** The page reports its own screens and its Bloks answers through this. */
private class PageBridge(private val sink: (String) -> Unit) {
    @JavascriptInterface
    fun note(text: String) {
        sink(text.take(200))
    }

    /** A Bloks call came back: its status, and the cookies the login holds right after it. */
    @JavascriptInterface
    fun answer(appid: String, status: Int) {
        // The code screen polls this every second; only a failure of it is worth a line.
        if (appid.endsWith("notif_status.async") && status == 200) return
        sink("bloks ${appid.take(120)} answered ${if (status < 0) "nothing (failed)" else status}, cookies ${cookieNames("$ACCOUNTS/")}")
    }
}

private const val ACCOUNTS = "https://accounts.meta.com"
private val SESSION = setOf("mt_active_sess", "mt_multi_sess", "sb")

/**
 * /oidc/ asked by Visor, with the page's cookies and without `X-Requested-With`, the one header the WebView cannot
 * drop. Returns Meta's answer for the page: the app's redirect with the code when Meta gives it, otherwise the
 * answer to the first request. A second try leaves `eaac` out, unless Meta has already deleted the session. Null
 * when Visor got no answer at all, and the page asks itself.
 */
private fun askOidc(url: String, agent: String, headers: Map<String, String>, note: (String) -> Unit): OidcAnswer? {
    val cookies = CookieManager.getInstance().getCookie(url)
    if (cookies.isNullOrEmpty()) {
        note("asking /oidc/ directly: no cookies to send")
        return null
    }
    // A browser user agent without these is a 400 at /oidc/; the network stack adds them for the page, not for us.
    val sent = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to Locale.getDefault().toLanguageTag(),
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
    ) + headers + mapOf("User-Agent" to agent, "Cookie" to cookies)
    note("asking /oidc/ directly with ${sent.keys.filter { it != "Cookie" }.sorted().joinToString(" ")}")
    val first = getOidc(url, sent, "as the page would", note) ?: return null
    val uri = Uri.parse(url)
    if (first.code != null || first.deletedSession || "eaac" !in uri.queryParameterNames) return first
    val bare = uri.buildUpon().clearQuery().apply {
        for (name in uri.queryParameterNames) if (name != "eaac") appendQueryParameter(name, uri.getQueryParameter(name))
    }.build().toString()
    return getOidc(bare, sent, "without eaac", note)?.takeIf { it.code != null } ?: first
}

private class OidcAnswer(
    val status: Int,
    val reason: String,
    val location: String?,
    val deletedSession: Boolean,
    val type: String?,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val code: String? get() = location?.takeIf { it.startsWith("$META_REDIRECT_URI?") }

    /** The answer as the page's own; never a redirect, which a WebView does not take from here. */
    fun page(): WebResourceResponse {
        val mime = type?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "text/html"
        val charset = type?.substringAfter("charset=", "")?.trim()?.ifEmpty { null } ?: "utf-8"
        return WebResourceResponse(mime, charset, status, reason, headers, ByteArrayInputStream(body))
    }
}

private fun empty() = WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))

// Not passed on: the body is already decoded, and cookies go to the WebView's jar by hand.
private val DROPPED = setOf("set-cookie", "content-type", "content-length", "content-encoding", "transfer-encoding")

private fun getOidc(url: String, headers: Map<String, String>, label: String, note: (String) -> Unit): OidcAnswer? {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        val status = connection.responseCode
        val location = connection.getHeaderField("Location")?.let { URL(URL(url), it).toString() }
        val set = connection.headerFields.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value.orEmpty()
        // The page would have stored these itself.
        val jar = CookieManager.getInstance()
        set.forEach { jar.setCookie(url, it) }
        jar.flush()
        val body = (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
        val (deleted, kept) = set.partition(::deletesCookie)
        val where = location?.let(Uri::parse)
        val title = TITLE.find(String(body, Charsets.UTF_8))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && '/' !in it && '?' !in it }
        val answer = OidcAnswer(
            status = status,
            reason = connection.responseMessage?.ifBlank { null } ?: if (status < 400) "OK" else "Error",
            location = location?.takeIf { status in 300..399 },
            deletedSession = deleted.any { it.substringBefore('=').trim() in SESSION },
            type = connection.contentType,
            headers = connection.headerFields.mapNotNull { (name, values) ->
                if (name == null || name.lowercase() in DROPPED || name.startsWith("X-Android-", ignoreCase = true)) null
                else name to values.joinToString(", ")
            }.toMap(),
            body = body,
        )
        val target = when {
            where == null -> "nowhere"
            answer.code != null -> "the app's redirect, params ${where.paramNames()}"
            else -> "${where.place()}, params ${where.paramNames()}"
        }
        note(
            "asked /oidc/ directly ($label): HTTP $status, sends to $target, sets ${kept.cookieNames()}, deletes ${deleted.cookieNames()}" +
                (title?.let { ", page title: ${it.take(80)}" } ?: ""),
        )
        answer.takeIf { status !in 300..399 || location != null }
    } catch (error: Exception) {
        note("asked /oidc/ directly ($label): ${error.javaClass.simpleName}: ${error.message}")
        null
    } finally {
        connection.disconnect()
    }
}

/** A Set-Cookie that removes the cookie: an emptied or "deleted" value, a zero Max-Age, or an Expires in 1970. */
private fun deletesCookie(line: String): Boolean {
    val parts = line.split(';').map(String::trim)
    val value = parts.first().substringAfter('=', "")
    return value.isEmpty() || value == "deleted" ||
        parts.any { it.startsWith("Max-Age=", ignoreCase = true) && (it.substringAfter('=').toLongOrNull() ?: 1) <= 0 } ||
        parts.any { it.startsWith("Expires=", ignoreCase = true) && "1970" in it }
}

private fun List<String>.cookieNames(): String = joinToString(" ") { it.substringBefore('=').trim() }.ifEmpty { "nothing" }

/** Host and path only: the query carries the code, the state and the login's own tokens. */
private fun Uri.place(): String = "${host.orEmpty()}${path.orEmpty()}"

/** Any URL the page itself puts in a line (a console message, an error) loses its query the same way. */
private fun withoutQueries(text: String): String = URL_QUERY.replace(text) { it.groupValues[1] + "?…" }

private val URL_QUERY = Regex("""(https?://[^\s?#"'<>]+)[?#][^\s"'<>]*""")

private val TITLE = Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)

// Which app and redirect a request is for: no secret, and the one thing that tells two OIDC hops apart.
private val PLAIN = setOf("app_id", "redirect_uri", "response_type", "scope")

/** The names of the query parameters, with values only for [PLAIN]: never a state, code or token. */
private fun Uri.paramNames(): String =
    queryParameterNames.sorted().joinToString(" ") { if (it in PLAIN) "$it=${getQueryParameter(it)}" else it }.ifEmpty { "none" }

/** A request worth a line: Meta's own endpoints, with the Bloks screen a fetch is for. Static files are not. */
private fun Uri.described(): String? {
    val host = host.orEmpty()
    val path = path.orEmpty()
    if (!(host.endsWith("meta.com") || host.endsWith("facebook.com") || host.endsWith("instagram.com"))) return null
    if (host.startsWith("static.") || path.startsWith("/rsrc.php") || path == "/ajax/bz" || path.startsWith("/favicon")) return null
    if (path.startsWith("/async/wbloks/fetch")) {
        return "bloks ${getQueryParameter("type") ?: "?"} ${getQueryParameter("appid") ?: "?"}"
    }
    return host + path
}

private fun cookieNames(url: String): String =
    CookieManager.getInstance().getCookie(url)?.split(';')?.joinToString(" ") { it.substringBefore('=').trim() }?.ifEmpty { null } ?: "none"

/** What this WebView is, on what phone, over what network: the things that differ between two users. */
private fun WebView.describe(context: Context, note: (String) -> Unit) {
    val pkg = WebView.getCurrentWebViewPackage()
    note("WebView ${pkg?.packageName ?: "unknown"} ${pkg?.versionName ?: ""}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, Visor ${BuildConfig.VERSION_NAME}, ${Locale.getDefault()}, network ${networkWords(context)}")
    note("user agent: ${settings.userAgentString}")
    note("cookies accepted ${CookieManager.getInstance().acceptCookie()}, third-party ${CookieManager.getInstance().acceptThirdPartyCookies(this)}")
}

private fun networkWords(context: Context): String {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
    val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "none"
    val transports = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "wifi",
        NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
        NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
        NetworkCapabilities.TRANSPORT_VPN to "vpn",
    ).filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "other" }
    return transports + if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "" else " (not validated)"
}

/**
 * Meta's login treats an in-app page differently from a browser, and the markers are the user agent's `wv` and
 * `Version/4.0`, the `X-Requested-With` header naming the app, and the client hints naming Android WebView and
 * the phone. Present as Chrome on the same engine instead.
 */
private fun WebSettings.lookLikeChrome(note: (String) -> Unit) {
    userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
    val header = WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)
    if (header) WebSettingsCompat.setRequestedWithHeaderOriginAllowList(this, emptySet())
    val hints = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
    if (hints) {
        val major = Regex("Chrome/(\\d+)").find(userAgentString)?.groupValues?.get(1) ?: "0"
        val full = Regex("Chrome/([\\d.]+)").find(userAgentString)?.groupValues?.get(1) ?: "0"
        val theirs = WebSettingsCompat.getUserAgentMetadata(this)
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Not:A-Brand").setMajorVersion("8").setFullVersion("8.0.0.0").build(),
        )
        WebSettingsCompat.setUserAgentMetadata(this, UserAgentMetadata.Builder(theirs).setBrandVersionList(brands).setModel("").build())
    }
    note("X-Requested-With ${if (header) "suppressed" else "not controllable on this WebView"}, client hints ${if (hints) "as Chrome" else "not controllable on this WebView"}")
}

/** Before the page's own scripts: every Bloks fetch reports its status when it comes back. */
private val ANSWERS = """
(function () {
  if (window.__visorAnswers || !window.fetch) return;
  window.__visorAnswers = true;
  var original = window.fetch;
  window.fetch = function (input) {
    var reply = original.apply(window, arguments);
    var m = /\/async\/wbloks\/fetch\/?\?[^#]*appid=([^&#]+)/.exec(String((input && input.url) || input));
    if (m) {
      var id = decodeURIComponent(m[1]);
      reply.then(function (r) { if (window.VisorLog) VisorLog.answer(id, r.status); },
                 function () { if (window.VisorLog) VisorLog.answer(id, -1); });
    }
    return reply;
  };
})();
""".trimIndent()

/** Installed once per page: reports the heading of the screen and of any dialog as the Bloks flow moves on. */
private val WATCH = """
(function () {
  if (window.__visorWatch) return;
  window.__visorWatch = true;
  var last = '';
  function text(sel) {
    var el = document.querySelector(sel);
    return el ? (el.innerText || '').trim().split('\n')[0].slice(0, 80) : '';
  }
  function report() {
    var screen = text('h1, h2, [role="heading"]');
    var dialog = text('[role="dialog"] h1, [role="dialog"] h2, [role="dialog"] [role="heading"]');
    var now = screen + (dialog ? ' / dialog: ' + dialog : '');
    if (now && now !== last) { last = now; VisorLog.note('screen: ' + now); }
  }
  var timer = null;
  new MutationObserver(function () { clearTimeout(timer); timer = setTimeout(report, 300); })
    .observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  window.addEventListener('error', function (e) { VisorLog.note('js error: ' + (e.message || '').slice(0, 160)); });
  window.addEventListener('unhandledrejection', function (e) { VisorLog.note('js rejection: ' + String(e.reason || '').slice(0, 160)); });
  VisorLog.note('page: cookies ' + (navigator.cookieEnabled ? 'on' : 'off') + ', passkeys ' + (window.PublicKeyCredential ? 'yes' : 'no') + ', webotp ' + ('OTPCredential' in window ? 'yes' : 'no') + ', ' + window.innerWidth + 'x' + window.innerHeight);
  report();
})();
""".trimIndent()
