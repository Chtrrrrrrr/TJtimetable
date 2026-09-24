package com.ranorac.tjtimetable.ui.navigation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** WeChat. */
const val WECHAT_PACKAGE: String = "com.tencent.mm"

/** WeCom / 企业微信. */
const val WECOM_PACKAGE: String = "com.tencent.wework"

/** Schemes the two apps register themselves. They bring the app up, nothing more. */
const val WECHAT_SCHEME: String = "weixin://"
const val WECOM_SCHEME: String = "wxwork://"

/**
 * One concrete way to hand a link to another app. Tried in order; the first one that
 * actually starts is the one reported back.
 */
sealed interface NavAttempt {
    /** Ask a *specific* package to open the URL. It is free to decline. */
    data class ViewUrlIn(val packageName: String) : NavAttempt

    /** Hand the URL to whatever handles `ACTION_VIEW` — in practice the system browser. */
    data object ViewUrlAnywhere : NavAttempt

    /** Start a specific, known package. */
    data class LaunchApp(val packageName: String) : NavAttempt

    /**
     * Summon an app through a scheme it registered itself. This brings the app to the
     * front but carries no URL, so the caller copies the link to the clipboard instead.
     */
    data class LaunchScheme(val scheme: String) : NavAttempt

    /**
     * Find an installed launcher app whose package name contains one of [tokens], then
     * start it. This is the fallback that makes "知到 is installed but nothing happens"
     * impossible: an unknown package name no longer masquerades as "not installed".
     */
    data class LaunchAppMatching(val tokens: List<String>) : NavAttempt
}

/**
 * The ordered attempts for one (link, mode) pair.
 *
 * The shape of this table is the honest part. Android has no "open in *this* browser" API,
 * and neither WeChat nor WeCom declares an `http`/`https` browsable filter — a third-party
 * app cannot make WeChat render a page. So 微信/企业微信 try three things in order:
 *
 *  1. hand the URL to the app itself (works on some builds);
 *  2. summon it via its own scheme and put the link on the clipboard;
 *  3. fall back to the system browser.
 *
 * [NavResult] reports which of those happened, so the UI never claims more than it did.
 * 外部应用 deliberately has **no** browser fallback — answering "打开知到" with a web page
 * would be worse than saying the app is missing.
 */
fun attemptsFor(link: NavLink, mode: NavOpenMode): List<NavAttempt> = when (mode) {
    NavOpenMode.BROWSER -> listOf(NavAttempt.ViewUrlAnywhere)

    NavOpenMode.WECHAT -> listOf(
        NavAttempt.ViewUrlIn(WECHAT_PACKAGE),
        NavAttempt.LaunchScheme(WECHAT_SCHEME),
        NavAttempt.ViewUrlAnywhere,
    )

    NavOpenMode.WECOM -> listOf(
        NavAttempt.ViewUrlIn(WECOM_PACKAGE),
        NavAttempt.LaunchScheme(WECOM_SCHEME),
        NavAttempt.ViewUrlAnywhere,
    )

    NavOpenMode.EXTERNAL_APP -> buildList {
        // 1. the known package names
        link.externalAppPackages.forEach { add(NavAttempt.LaunchApp(it)) }
        // 2. resolve whatever IS installed by package fragment or display name — this is the
        //    step that does not depend on me knowing the package in advance
        if (link.externalAppMatchTokens.isNotEmpty()) {
            add(NavAttempt.LaunchAppMatching(link.externalAppMatchTokens))
        }
        // 3. last resort: a scheme the app registers itself
        link.externalAppSchemes.forEach { add(NavAttempt.LaunchScheme(it)) }
    }
}

enum class NavStatus {
    /** The URL itself reached something. */
    OPENED,

    /** Only the app could be brought up; the link went to the clipboard instead. */
    SUMMONED_APP,

    /** Nothing on the device could handle it. */
    UNAVAILABLE,

    /** Everything was tried and every attempt failed. */
    FAILED,
}

/**
 * @param usedFallback true when the requested mode could not take the URL and a later
 *   attempt (the browser) did the work instead.
 */
data class NavResult(
    val status: NavStatus,
    val usedFallback: Boolean = false,
)

/** Short human name for a mode, used in messages. */
fun NavOpenMode.shortLabel(link: NavLink): String = when (this) {
    NavOpenMode.BROWSER -> "浏览器"
    NavOpenMode.WECHAT -> "微信"
    NavOpenMode.WECOM -> "企业微信"
    NavOpenMode.EXTERNAL_APP -> link.externalAppLabel ?: "外部应用"
}

/**
 * What to tell the student about [this] result, or null when nothing needs saying.
 *
 * Pure, so the wording is unit-tested rather than eyeballed on a device.
 */
fun NavResult.messageFor(link: NavLink, mode: NavOpenMode): String? = when (status) {
    NavStatus.OPENED ->
        if (usedFallback) {
            "无法在${mode.shortLabel(link)}内直接打开网页，已改用浏览器打开"
        } else {
            null
        }
    NavStatus.SUMMONED_APP ->
        "已把链接复制到剪贴板并打开${mode.shortLabel(link)}，粘贴即可访问" +
            "（Android 不允许第三方应用直接在${mode.shortLabel(link)}内打开网页）"
    NavStatus.UNAVAILABLE -> buildString {
        // Say what was actually searched, so a failure is diagnosable from the screen
        // instead of being another guess.
        append("未找到「").append(link.externalAppLabel ?: "该应用").append("」。")
        if (link.externalAppPackages.isNotEmpty()) {
            append("已尝试包名 ").append(link.externalAppPackages.joinToString("、")).append("；")
        }
        append("并按应用名检索了所有已安装应用。")
    }
    NavStatus.FAILED -> "打开失败，请稍后重试"
}

/**
 * Starts the first [NavAttempt] that succeeds.
 *
 * Resolution is left to `startActivity` (caught) rather than `resolveActivity`: on
 * Android 11+ the latter returns null for packages the app has not declared in
 * `<queries>`, which would turn "installed but declines the URL" into a bogus
 * "not installed" verdict.
 */
object NavLauncher {

    fun open(context: Context, link: NavLink, mode: NavOpenMode): NavResult {
        val attempts = attemptsFor(link, mode)
        attempts.forEachIndexed { index, attempt ->
            val intent = intentFor(context, attempt, link.url) ?: return@forEachIndexed
            try {
                context.startActivity(intent)
                return when (attempt) {
                    // The app is up, but it never received the URL — hand the student the
                    // link so the summon still leads somewhere.
                    is NavAttempt.LaunchScheme -> {
                        copyToClipboard(context, link)
                        NavResult(NavStatus.SUMMONED_APP, usedFallback = true)
                    }
                    else -> NavResult(NavStatus.OPENED, usedFallback = index > 0)
                }
            } catch (_: ActivityNotFoundException) {
                // This attempt cannot handle it; fall through to the next one.
            } catch (_: SecurityException) {
                // Launching that package is not permitted on this device; try the next.
            }
        }
        return when (mode) {
            // Nothing could take the URL at all.
            NavOpenMode.EXTERNAL_APP -> NavResult(NavStatus.UNAVAILABLE)
            else -> NavResult(NavStatus.FAILED)
        }
    }

    private fun intentFor(context: Context, attempt: NavAttempt, url: String): Intent? {
        val intent = when (attempt) {
            is NavAttempt.ViewUrlIn ->
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(attempt.packageName)
            NavAttempt.ViewUrlAnywhere ->
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            is NavAttempt.LaunchApp ->
                context.packageManager.getLaunchIntentForPackage(attempt.packageName)
            is NavAttempt.LaunchScheme ->
                Intent(Intent.ACTION_VIEW, Uri.parse(attempt.scheme))
            is NavAttempt.LaunchAppMatching ->
                matchingLaunchIntent(context, attempt.tokens)
        }
        // The call always originates from the Compose host, never from the target app.
        return intent?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /**
     * The first installed launcher app whose **package name or display name** contains one
     * of [tokens].
     *
     * Matching the label is the important half: the package that ships an app
     * (`com.able.wisdomtree`) need not resemble the name the student sees (「知到」), so a
     * package-only search silently reports a perfectly installed app as missing.
     *
     * `queryIntentActivities` only sees packages this app declared in `<queries>`, which is
     * why the manifest asks for the launcher intent.
     */
    private fun matchingLaunchIntent(context: Context, tokens: List<String>): Intent? {
        if (tokens.isEmpty()) return null
        val pm = context.packageManager
        val match = pm
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )
            .asSequence()
            .firstOrNull { resolved ->
                val packageName = resolved.activityInfo?.packageName ?: return@firstOrNull false
                val label = runCatching { resolved.loadLabel(pm).toString() }.getOrDefault("")
                tokens.any { token ->
                    packageName.contains(token, ignoreCase = true) ||
                        label.contains(token, ignoreCase = true)
                }
            } ?: return null
        val packageName = match.activityInfo?.packageName ?: return null
        return pm.getLaunchIntentForPackage(packageName)
    }

    private fun copyToClipboard(context: Context, link: NavLink) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(link.title, link.url))
    }
}
