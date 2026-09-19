package com.ranorac.tjtimetable.scrape

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ranorac.tjtimetable.ui.components.GitHubPrimaryButton
import com.ranorac.tjtimetable.ui.components.GitHubSecondaryButton
import com.ranorac.tjtimetable.ui.components.MutedText
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Where the student logs in. The real 教务 page, so SSO/验证码 all work normally. */
const val TONGJI_HOME = "https://1.tongji.edu.cn"

/**
 * The personal timetable page.
 *
 * The 教务 首页's sidebar cannot be made to render in a WebView — the site serves a
 * navigation that a WebView will not lay out — so the app jumps straight to the page that
 * actually matters rather than making the student hunt for it.
 */
const val TONGJI_TIMETABLE_PAGE = "https://1.tongji.edu.cn/GraduateStudentTimeTable"

/**
 * A timetable request the page made, remembered so the app can repeat it later.
 *
 * The page's own request is the only source of truth for two things the app cannot
 * construct: `studentCode` (a front-end encrypted uid) and the `x-token` header.
 */
internal data class RecordedTimetableRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
)

/** Progress, reported verbatim so a failure is diagnosable without a debugger. */
sealed interface CaptureStatus {
    data object Browsing : CaptureStatus

    /** The page issued a timetable request; the student can now press 读取课表. */
    data class Ready(val endpoint: String, val method: String) : CaptureStatus

    data object Reading : CaptureStatus

    data class Captured(val endpoint: String, val bytes: Int) : CaptureStatus

    data class Failed(val message: String) : CaptureStatus
}

/**
 * An ordinary in-app browser, plus one extra: a 读取课表 button.
 *
 * The student logs in on the **real** 教务 page — 统一身份认证, 验证码 and SSO are all handled
 * by the school's own page, so the app never sees the password — then opens 我的课表, and
 * when they press 读取课表 the app repeats the request the page already made, carrying the
 * page's own cookies and headers.
 *
 * Requests are **recorded, never intercepted**: [WebViewClient.shouldInterceptRequest]
 * always returns `null`, so the page behaves exactly as it would in any browser. An earlier
 * version rewrote the request so it could read the body, which is precisely what made the
 * page fail to load.
 *
 * Every setting is left at what a modern browser would use — no user-agent override, a real
 * [WebChromeClient] so `alert`/`confirm`/`window.open` work, popups enabled, mixed content
 * allowed — because an in-app browser that quietly differs is how a login flow "just doesn't
 * work" in an app while working everywhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TongjiLoginScreen(
    /** Called with the response body once the student asks to read the timetable. */
    onCaptured: (url: String, body: String) -> Unit,
    onCancel: () -> Unit,
    onUsePasteInstead: () -> Unit,
    /** Overridable for tests; defaults to the ported reference matcher. */
    isTimetableRequest: (url: String) -> Boolean = TongjiWebCapture::isEndpoint,
) {
    val gh = LocalGitHubColors.current
    val scope = rememberCoroutineScope()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(TONGJI_HOME) }
    var atTimetablePage by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<CaptureStatus>(CaptureStatus.Browsing) }
    var recorded by remember { mutableStateOf<RecordedTimetableRequest?>(null) }

    Scaffold(
        containerColor = gh.canvasDefault,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = gh.canvasDefault,
                    titleContentColor = gh.fgDefault,
                ),
                title = {
                    Column {
                        Text("登录教务系统", style = MaterialTheme.typography.titleLarge)
                        MutedText("登录由学校页面完成，应用不接触你的口令")
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("取消", color = gh.accentFg) }
                },
                actions = {
                    TextButton(onClick = {
                        status = CaptureStatus.Browsing
                        recorded = null
                        atTimetablePage = false
                        webView?.loadUrl(TONGJI_HOME)
                    }) {
                        Text("回首页", color = gh.accentFg)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            StatusBar(status = status, currentUrl = currentUrl)

            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // ---- a plain, modern browser ----
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(true)
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.loadsImagesAutomatically = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            // userAgentString is deliberately NOT overridden: the device's own
                            // WebView UA is structurally identical to what Chrome on this phone
                            // sends, and overriding it is what makes a site serve a page the
                            // student did not expect.

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    url?.let {
                                        currentUrl = it
                                        atTimetablePage = it.contains("TimeTable", ignoreCase = true)
                                    }
                                    if (status !is CaptureStatus.Captured) {
                                        status = CaptureStatus.Browsing
                                    }
                                }

                                /**
                                 * Never interferes. Returning null means "handle it normally",
                                 * which is what keeps the page working — the only thing that
                                 * happens here is remembering the request.
                                 */
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): android.webkit.WebResourceResponse? {
                                    val url = request.url.toString()
                                    if (isTimetableRequest(url)) {
                                        recorded = RecordedTimetableRequest(
                                            url = url,
                                            method = request.method,
                                            headers = request.requestHeaders
                                                .filterKeys { it.isNotBlank() },
                                        )
                                        status = CaptureStatus.Ready(
                                            endpoint = TongjiWebCapture.endpointLabel(url),
                                            method = request.method,
                                        )
                                    }
                                    return null
                                }
                            }

                            // Without a WebChromeClient, alert()/confirm() are dropped — which
                            // can leave a login waiting forever on a confirmation the student
                            // never sees — and window.open() fails silently.
                            webChromeClient = object : WebChromeClient() {
                                override fun onJsAlert(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    result: JsResult?,
                                ): Boolean {
                                    showJsDialog(ctx, url, message, result, confirm = false)
                                    return true
                                }

                                override fun onJsConfirm(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    result: JsResult?,
                                ): Boolean {
                                    showJsDialog(ctx, url, message, result, confirm = true)
                                    return true
                                }

                                override fun onJsPrompt(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    defaultValue: String?,
                                    result: JsPromptResult?,
                                ): Boolean {
                                    showJsPrompt(ctx, url, message, defaultValue, result)
                                    return true
                                }

                                /**
                                 * Reuses the main WebView for a popup's target, so a popup works
                                 * without a second view in the hierarchy and — the real reason —
                                 * the request-recording logic stays in one place.
                                 */
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?,
                                ): Boolean {
                                    val main = view ?: return false
                                    val popup = WebView(main.context)
                                    popup.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            v: WebView?,
                                            request: WebResourceRequest?,
                                        ): Boolean {
                                            request?.url?.let { main.loadUrl(it.toString()) }
                                            return true
                                        }
                                    }
                                    (resultMsg?.obj as? WebView.WebViewTransport)?.webView = popup
                                    resultMsg?.sendToTarget()
                                    return true
                                }
                            }

                            webView = this
                            loadUrl(TONGJI_HOME)
                        }
                    },
                )
            }

            // ------------------------------------------------- bottom controls
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // 跳转 + 读取 放在同一行：两个都是"下一步"，之前各占一行白占了一块屏幕。
                // 「放大」「缩小」按钮已删除：在 useWideViewPort + loadWithOverviewMode 下
                // 页面已按视口宽度缩放，zoomIn/zoomOut 点击后没有任何可见变化。
                // 双指捏合缩放属于 WebView 内建行为，仍然可用。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The 教务 首页's sidebar will not render in a WebView, so go straight to the
                    // page that matters instead of leaving the student to find it.
                    GitHubPrimaryButton(
                        text = if (atTimetablePage) "已在课表页" else "跳转到个人课表",
                        enabled = !atTimetablePage,
                        onClick = {
                            status = CaptureStatus.Browsing
                            recorded = null
                            webView?.loadUrl(TONGJI_TIMETABLE_PAGE)
                        },
                    )
                    GitHubSecondaryButton(
                        text = "读取课表",
                        enabled = recorded != null && status !is CaptureStatus.Reading,
                        onClick = {
                            recorded?.let { target ->
                                status = CaptureStatus.Reading
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { replay(target) }
                                    }
                                    result.fold(
                                        onSuccess = { body ->
                                            status = CaptureStatus.Captured(
                                                endpoint = TongjiWebCapture.endpointLabel(target.url),
                                                bytes = body.length,
                                            )
                                            onCaptured(target.url, body)
                                        },
                                        onFailure = {
                                            status = CaptureStatus.Failed(
                                                "读取失败：${it.message ?: it::class.java.simpleName}",
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                MutedText("登录后按「跳转到个人课表」，等状态栏显示已捕获课表请求，再按「读取课表」。")
            }
        }
    }
}

/**
 * Repeats the page's own request.
 *
 * The cookies come from the live [CookieManager] jar and the headers are the page's real
 * ones — including `x-token`, which the app cannot derive.
 *
 * `x-token` gets one documented fallback: WebView does not always expose every header to
 * [WebViewClient.shouldInterceptRequest], and on this system `x-token` equals the
 * `sessionid` cookie, so that value is substituted when the header is missing.
 */
private fun replay(request: RecordedTimetableRequest): String {
    val cookies = CookieManager.getInstance().getCookie(request.url).orEmpty()
    val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
        requestMethod = request.method.uppercase()
        connectTimeout = 20_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        // host/content-length/accept-encoding are the client's business; copying them over
        // actively conflicts with what the network stack computes.
        for ((key, value) in request.headers) {
            when (key.lowercase()) {
                "host", "content-length", "accept-encoding", "content-type" -> Unit
                else -> runCatching { setRequestProperty(key, value) }
            }
        }
        if (cookies.isNotBlank()) {
            setRequestProperty("Cookie", cookies)
            if (request.headers.keys.none { it.equals("x-token", ignoreCase = true) }) {
                cookieValue(cookies, "sessionid")?.let { setRequestProperty("x-token", it) }
            }
        }
        if (getRequestProperty("accept") == null) {
            setRequestProperty("accept", "application/json, text/plain, */*")
        }
    }

    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    connection.disconnect()

    if (code !in 200..299) {
        // 401/403 here almost always means the session expired: say so, because "HTTP 401"
        // alone tells the student nothing actionable.
        val hint = if (code == 401 || code == 403) "（登录状态已失效，请重新登录后再试）" else ""
        error("HTTP $code$hint")
    }
    if (body.isBlank()) error("服务器返回了空内容")
    return body
}

/** Reads one cookie's value out of a `Cookie` header string. */
private fun cookieValue(cookieHeader: String, name: String): String? =
    cookieHeader.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')

/**
 * A real, visible JS dialog.
 *
 * The title is the page URL so a dialog can never be mistaken for one of ours — the same
 * signal a normal browser gives.
 */
private fun showJsDialog(
    context: Context,
    pageUrl: String?,
    message: String?,
    result: JsResult?,
    confirm: Boolean,
) {
    val builder = AlertDialog.Builder(context)
        .setTitle(pageUrl ?: "网页消息")
        .setMessage(message.orEmpty())
        .setCancelable(false)
    if (confirm) {
        builder
            .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
    } else {
        builder.setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
    }
    builder.show()
}

/** `prompt()` needs an input field, otherwise the page would block forever. */
private fun showJsPrompt(
    context: Context,
    pageUrl: String?,
    message: String?,
    defaultValue: String?,
    result: JsPromptResult?,
) {
    val input = EditText(context).apply { setText(defaultValue.orEmpty()) }
    AlertDialog.Builder(context)
        .setTitle(pageUrl ?: "网页消息")
        .setMessage(message.orEmpty())
        .setView(input)
        .setCancelable(false)
        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
        .show()
}

@Composable
private fun StatusBar(status: CaptureStatus, currentUrl: String) {
    val gh = LocalGitHubColors.current
    val (label, colour) = when (status) {
        CaptureStatus.Browsing ->
            "请在页面中登录，然后按下面的「跳转到个人课表」" to gh.fgMuted
        is CaptureStatus.Ready ->
            "已捕获 ${status.endpoint}（${status.method}），可以读取了" to gh.successFg
        CaptureStatus.Reading ->
            "正在读取课表…" to gh.accentFg
        is CaptureStatus.Captured ->
            "已读取 ${status.endpoint}（${status.bytes} 字符）" to gh.successFg
        is CaptureStatus.Failed ->
            status.message to gh.dangerFg
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status is CaptureStatus.Reading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = gh.accentFg,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = colour,
            )
        }
        Text(
            currentUrl,
            style = MaterialTheme.typography.labelSmall,
            color = gh.fgSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
