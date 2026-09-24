package com.ranorac.tjtimetable.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** WeChat. */
const val WECHAT_PACKAGE: String = "com.tencent.mm"

/** WeCom / 企业微信. */
const val WECOM_PACKAGE: String = "com.tencent.wework"

/**
 * One concrete way to hand a link to another app. Tried in order; the first one that
 * actually starts is the one that gets reported back.
 */
sealed interface NavAttempt {
    /** Ask a *specific* package to open the URL. It is free to decline. */
    data class ViewUrlIn(val packageName: String) : NavAttempt

    /** Hand the URL to whatever handles `ACTION_VIEW` — in practice the system browser. */
    data object ViewUrlAnywhere : NavAttempt

    /** Just start the app, without a URL. */
    data class LaunchApp(val packageName: String) : NavAttempt
}

/**
 * The ordered attempts for one (link, mode) pair.
 *
 * The honest shape of this table is the whole point. Android has no "open in *this*
 * browser" API, and neither WeChat nor WeCom registers an `http`/`https` browsable
 * filter — a third-party app cannot make WeChat render a page. So 微信/企业微信 are
 * modelled as *try, then degrade to the browser*, and [NavResult.usedFallback] tells the
 * user which of the two actually happened instead of silently pretending.
 *
 * 外部应用 deliberately has **no** browser fallback: answering "打开学习通" by quietly
 * opening a web page would be worse than saying the app is missing.
 */
fun attemptsFor(link: NavLink, mode: NavOpenMode): List<NavAttempt> = when (mode) {
    NavOpenMode.BROWSER -> listOf(NavAttempt.ViewUrlAnywhere)
    NavOpenMode.WECHAT -> listOf(
        NavAttempt.ViewUrlIn(WECHAT_PACKAGE),
        NavAttempt.ViewUrlAnywhere,
    )
    NavOpenMode.WECOM -> listOf(
        NavAttempt.ViewUrlIn(WECOM_PACKAGE),
        NavAttempt.ViewUrlAnywhere,
    )
    NavOpenMode.EXTERNAL_APP -> link.externalAppPackages.map { NavAttempt.LaunchApp(it) }
}

enum class NavStatus { OPENED, UNAVAILABLE, FAILED }

/**
 * @param usedFallback true when the requested mode could not take the URL and a later
 *   attempt (the browser) did the work instead.
 */
data class NavResult(
    val status: NavStatus,
    val usedFallback: Boolean = false,
)

/** Short human name for a mode, used for the "默认" marker and error text. */
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
    NavStatus.UNAVAILABLE ->
        "未检测到「${link.externalAppLabel ?: "该应用"}」，请安装后再试"
    NavStatus.FAILED -> "打开失败，请稍后重试"
}

/**
 * Starts the first [NavAttempt] that succeeds.
 *
 * Resolution is left to `startActivity` (caught) rather than `resolveActivity`: on
 * Android 11+ the latter returns null for packages the app has not declared in
 * `<queries>`, which would turn "WeChat is installed but declines the URL" into a bogus
 * "not installed" verdict. The manifest declares the packages we launch directly.
 */
object NavLauncher {

    fun open(context: Context, link: NavLink, mode: NavOpenMode): NavResult {
        val attempts = attemptsFor(link, mode)
        attempts.forEachIndexed { index, attempt ->
            val intent = intentFor(context, attempt, link.url) ?: return@forEachIndexed
            try {
                context.startActivity(intent)
                return NavResult(NavStatus.OPENED, usedFallback = index > 0)
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
        }
        // The call always originates from the Compose host, never from the target app.
        return intent?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
