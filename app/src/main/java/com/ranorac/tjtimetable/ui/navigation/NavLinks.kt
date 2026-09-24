package com.ranorac.tjtimetable.ui.navigation

/**
 * How a [NavLink] can be handed to another app.
 *
 * These are *intents*, not guarantees: Android lets a third-party app say "open this
 * URL", never "open it in a specific browser". Neither WeChat nor WeCom declares an
 * `http`/`https` browsable filter — doing so would hijack every link on the device — so
 * [WECHAT] and [WECOM] are best-effort routes that fall back to the system browser.
 * See [attemptsFor].
 */
enum class NavOpenMode {
    /** System browser (`ACTION_VIEW`, no package restriction). */
    BROWSER,

    /** Hand the URL to WeChat, else fall back to the browser. */
    WECHAT,

    /** Hand the URL to WeCom (企业微信), else fall back to the browser. */
    WECOM,

    /** Start a specific installed app (学习通 / 知到). */
    EXTERNAL_APP,
}

/**
 * One entry on the 导航 page.
 *
 * @param supportedModes every way this link can be opened, default included, in the order
 *   the buttons should appear.
 * @param externalAppPackages candidate packages for [NavOpenMode.EXTERNAL_APP]; the first
 *   installed one wins. More than one is normal — vendors rename packages between releases.
 * @param externalAppMatchTokens substring match against an installed launcher app's
 *   **package name or its display name**. Matching the display name is the robust half: the
 *   student sees 「知到」 in their launcher, and that label does not have to share anything
 *   with the package (`com.able.wisdomtree`) that ships it.
 * @param externalAppSchemes schemes the app registers itself, tried last.
 */
data class NavLink(
    val id: String,
    val title: String,
    val url: String,
    val defaultMode: NavOpenMode,
    val supportedModes: List<NavOpenMode>,
    val externalAppLabel: String? = null,
    val externalAppPackages: List<String> = emptyList(),
    val externalAppMatchTokens: List<String> = emptyList(),
    val externalAppSchemes: List<String> = emptyList(),
)

/** One explicit open button on a card. Every supported mode gets its own. */
data class NavAction(
    val mode: NavOpenMode,
    val label: String,
    val isDefault: Boolean,
)

/**
 * The buttons a card must render — **both** the default mode and every supported one, as
 * required, so the student always chooses rather than the app choosing silently.
 */
fun NavLink.actions(): List<NavAction> = supportedModes.map { mode ->
    NavAction(mode = mode, label = mode.actionLabel(this), isDefault = mode == defaultMode)
}

/** Button/label text for a mode. */
fun NavOpenMode.actionLabel(link: NavLink): String = when (this) {
    NavOpenMode.BROWSER -> "浏览器打开"
    NavOpenMode.WECHAT -> "微信打开"
    NavOpenMode.WECOM -> "企业微信打开"
    NavOpenMode.EXTERNAL_APP -> "打开${link.externalAppLabel ?: "外部应用"}"
}

/**
 * The 导航 page's fixed content.
 *
 * Kept as plain data so the whole page is unit-testable without a device, and so adding a
 * site is a one-line change rather than a layout edit.
 */
object NavLinks {

    val ALL: List<NavLink> = listOf(
        NavLink(
            id = "tj-1system",
            title = "1系统",
            url = "https://1.tongji.edu.cn",
            defaultMode = NavOpenMode.WECOM,
            supportedModes = listOf(NavOpenMode.WECOM, NavOpenMode.BROWSER),
        ),
        NavLink(
            id = "tj-canvas",
            title = "canvas系统",
            url = "https://canvas.tongji.edu.cn",
            defaultMode = NavOpenMode.WECOM,
            supportedModes = listOf(NavOpenMode.WECOM, NavOpenMode.BROWSER),
        ),
        NavLink(
            id = "tj-aihaoke",
            title = "好课平台",
            url = "https://tongji.aihaoke.net/student/course",
            defaultMode = NavOpenMode.BROWSER,
            supportedModes = listOf(NavOpenMode.BROWSER, NavOpenMode.WECHAT),
        ),
        NavLink(
            id = "chaoxing",
            title = "超星学习通/慕课",
            url = "https://mooc1-1.chaoxing.com",
            defaultMode = NavOpenMode.BROWSER,
            supportedModes = listOf(NavOpenMode.BROWSER, NavOpenMode.EXTERNAL_APP),
            externalAppLabel = "学习通",
            externalAppPackages = listOf("com.chaoxing.mobile", "com.chaoxing.ckandroid"),
            // The display name is what the student actually sees in their launcher, so it is
            // matched as well as the package.
            externalAppMatchTokens = listOf("学习通", "超星", "chaoxing"),
            externalAppSchemes = listOf("chaoxing://"),
        ),
        NavLink(
            id = "zhihuishu",
            title = "智慧树/知到",
            url = "https://www.zhihuishu.com/",
            defaultMode = NavOpenMode.EXTERNAL_APP,
            supportedModes = listOf(NavOpenMode.EXTERNAL_APP, NavOpenMode.BROWSER),
            externalAppLabel = "知到",
            // 知到 ships as com.able.wisdomtree: the package shares no substring with the
            // brand, so it cannot be inferred — it has to be known.
            externalAppPackages = listOf(
                "com.able.wisdomtree",
                "com.zhihuishu.zhihuishu",
                "com.zhihuishu.zhidao",
            ),
            // Label matching is the safety net that does not depend on knowing the package:
            // 「知到」/「智慧树」 is what the app calls itself on screen.
            externalAppMatchTokens = listOf("知到", "智慧树", "wisdomtree", "zhihuishu", "able"),
            externalAppSchemes = listOf("zhihuishu://", "wisdomtree://"),
        ),
        NavLink(
            id = "ketangpai",
            title = "课堂派",
            url = "https://w.ketangpai.com/ktpCourse",
            defaultMode = NavOpenMode.WECHAT,
            supportedModes = listOf(NavOpenMode.WECHAT, NavOpenMode.BROWSER),
        ),
        NavLink(
            id = "exam-homework",
            title = "考试/作业",
            // A campus-LAN address over plain http. Nothing here needs the app's own network
            // stack: the URL is only ever handed to the browser, so the app's cleartext
            // policy is irrelevant and no permission is involved.
            url = "http://192.168.174.220:2080/#/",
            defaultMode = NavOpenMode.BROWSER,
            supportedModes = listOf(NavOpenMode.BROWSER),
        ),
    )
}
