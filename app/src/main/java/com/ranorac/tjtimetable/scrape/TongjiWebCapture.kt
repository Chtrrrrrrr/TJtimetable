package com.ranorac.tjtimetable.scrape

import java.net.URI
import java.net.URLDecoder

/** 内置登录窗口里读到的一条 cookie（只在内存里拼请求头，**绝不落日志**）。 */
data class WebCookie(
    val name: String,
    val value: String,
    /**
     * Nullable although the reference's `WebCookie` record declares `string`: the
     * reference's matcher tolerates null and treats it as "does not match", and
     * `CookieManager` on Android can hand back cookies without those attributes.
     */
    val domain: String? = null,
    val path: String? = null,
)

/** 一次「从内置登录窗口捕获课表响应」的判定结果（`ok = false` 时 [note] 给用户看）。 */
data class WebCaptureVerdict(val ok: Boolean, val note: String, val termId: String?)

/**
 * 内置登录窗口背后的**纯逻辑**：这条响应是不是课表、学期 id 在不在 URL 上、
 * cookie 该拼成什么请求头。
 *
 * Ported from `gzy31007/TJDesktopTimetable`'s `dotnet/TjtCore/TongjiWebCapture.cs`.
 * It lives outside the window layer for the same reason as the reference: these
 * decisions touch neither a WebView nor the network, so they are plain JVM-tested
 * Kotlin, and the UI shell keeps only "feed it the WebView events".
 *
 * **为什么不自己拼课表请求**：课表页那条接口要 `studentCode`（前端加密过的 uid），
 * 算法在前端 bundle 里、会随发版变。所以这里**只做旁观者** —— 用户在页面里点开
 * 「我的课表」，页面自己发那条请求，我们从响应里把数据接过来（URL 上的
 * `calendarId` 顺手一起接）。
 */
object TongjiWebCapture {

    /** 1 系统主机名（课表接口都在它下面）。 */
    const val TONGJI_HOST = "1.tongji.edu.cn"

    /** 1 系统站点根（WebView 的初始导航目标）。 */
    const val TONGJI_ORIGIN = "https://$TONGJI_HOST"

    /**
     * 课表接口的路径特征（小写比较）。三条都是实测/前端源码里的真路径：
     * 报表接口 `findStudentTimetab`（本科生，`calendarId` 在 URL 上）、
     * 研究生报表接口 `findSchoolTimetab2`、选课服务 `getDataBk`（`calendarId` 在响应体里）。
     */
    private val ENDPOINTS: List<Pair<String, String>> = listOf(
        "findstudenttimetab" to "报表接口 findStudentTimetab",
        "findschooltimetab2" to "研究生报表接口 findSchoolTimetab2",
        "getdatabk" to "选课服务 getDataBk",
    )

    /** 这条 URL 是不是课表接口（大小写不敏感，只看路径特征）。 */
    fun isEndpoint(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return ENDPOINTS.any { (fragment, _) -> lower.contains(fragment) }
    }

    /** 接口的可读名字（日志与探测行用；认不出时给「未知接口」）。 */
    fun endpointLabel(url: String?): String {
        if (url.isNullOrBlank()) return "未知接口"
        val lower = url.lowercase()
        return ENDPOINTS.firstOrNull { (fragment, _) -> lower.contains(fragment) }?.second
            ?: "未知接口"
    }

    /** 这个主机名是不是同济（`1.tongji.edu.cn` 及其它 `*.tongji.edu.cn`）。 */
    fun isTongjiHost(host: String?): Boolean {
        val value = (host ?: "").trim().trimEnd('.')
        if (value.isEmpty()) return false
        return value.equals("tongji.edu.cn", ignoreCase = true) ||
            value.endsWith(".tongji.edu.cn", ignoreCase = true)
    }

    /** 这条 URL 是不是同济站点（http/https 之外的协议一律不算）。 */
    fun isTongjiUrl(url: String?): Boolean {
        val uri = absoluteUri(url) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        return isTongjiHost(uri.host)
    }

    /** 取 `calendarId`（学期 id）：报表接口把它放在 URL 上，响应体里没有。 */
    fun calendarIdOf(url: String?): String? = queryValue(url, "calendarId")

    /**
     * 取查询参数（URL 解码后；参数不存在或值为空时返回 `null`）。
     *
     * 逐字对应参考实现：`.NET` 的 `Uri.Query` 带前导 `?`，这里补回来，于是后面的
     * 「长度 ≤ 1 就不要」「跳过 `?` 再按 `&` 切」与 C# 完全同构。
     */
    fun queryValue(url: String?, name: String?): String? {
        if (url.isNullOrBlank() || name.isNullOrBlank()) return null
        val uri = absoluteUri(url) ?: return null

        val query = uri.rawQuery?.let { "?$it" } ?: ""
        if (query.length <= 1) return null

        for (pair in query.substring(1).split('&')) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val key = if (index < 0) pair else pair.substring(0, index)
            if (!key.equals(name, ignoreCase = true)) continue

            val raw = if (index < 0) "" else pair.substring(index + 1)
            val value = percentDecode(raw.replace('+', ' ')).trim()
            return value.ifEmpty { null }
        }

        return null
    }

    /**
     * 把捕获到的 cookie 拼成 `Cookie` 请求头（RFC 6265 的简化版：域匹配 + 路径匹配 +
     * 同名取路径更长的那个）。**返回值含登录态，只许进请求头，不许进日志。**
     */
    fun cookieHeader(url: String?, cookies: List<WebCookie>?): String {
        if (cookies.isNullOrEmpty()) return ""
        val uri = absoluteUri(url) ?: return ""

        val host = uri.host ?: return ""
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"

        // 同名 cookie 只发一个：先按路径长度降序，先到的就是「更具体」的那个。
        val matched = cookies
            .filter { it.name.isNotEmpty() }
            .filter { domainMatches(host, it.domain) }
            .filter { pathMatches(path, it.path) }
            .sortedByDescending { (it.path ?: "/").length }

        val parts = ArrayList<String>(matched.size)
        val seen = HashSet<String>()
        for (cookie in matched) {
            if (!seen.add(cookie.name)) continue
            parts += "${cookie.name}=${cookie.value}"
        }

        return parts.joinToString("; ")
    }

    /**
     * 判定一条被拦到的响应：URL 得是课表接口，响应体得「像课表数据」。
     *
     * @return `ok = true` 时 [WebCaptureVerdict.termId] 是从 URL 上接到的 `calendarId`；
     *   报表接口把它放在 URL 上，选课服务的响应体里没有，所以后者这里是 `null`。
     */
    fun inspect(url: String?, body: String?): WebCaptureVerdict {
        if (!isEndpoint(url)) return WebCaptureVerdict(false, "不是课表接口（已忽略）", null)

        val verdict = TongjiResponseProbe.inspect(body)
        if (!verdict.ok) return WebCaptureVerdict(false, verdict.note, null)
        return WebCaptureVerdict(true, verdict.note, calendarIdOf(url))
    }

    /** 给日志用的一句话（**不含 cookie、不含响应体**）。 */
    fun describe(url: String?, bodyLength: Int): String {
        val label = endpointLabel(url)
        val calendarId = calendarIdOf(url) ?: "无"
        return "$label，$bodyLength 字节，calendarId=$calendarId"
    }

    /** 绝对 URI，或 null —— 对应 `Uri.TryCreate(url, UriKind.Absolute, out uri)`。 */
    private fun absoluteUri(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return if (uri.isAbsolute) uri else null
    }

    private fun domainMatches(host: String, domain: String?): Boolean {
        val value = (domain ?: "").trim().trimStart('.')
        if (value.isEmpty()) return false
        return host.equals(value, ignoreCase = true) ||
            host.endsWith(".$value", ignoreCase = true)
    }

    private fun pathMatches(requestPath: String, cookiePath: String?): Boolean {
        val value = if (cookiePath.isNullOrEmpty()) "/" else cookiePath
        if (value == "/") return true
        if (!requestPath.startsWith(value)) return false

        // RFC 6265：前缀相同还不算命中，下一个字符得是 '/'（/api 不匹配 /apix）
        return requestPath.length == value.length ||
            value.endsWith('/') ||
            requestPath[value.length] == '/'
    }

    /**
     * `.NET` `Uri.UnescapeDataString` 的等价物：畸形百分号转义**原样保留**，
     * 而不是像 `URLDecoder` 那样抛异常（参考实现不会因为一个坏参数就丢掉整条 URL）。
     */
    private fun percentDecode(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
}
