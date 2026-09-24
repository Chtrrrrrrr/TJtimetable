package com.ranorac.tjtimetable.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 导航 page is fixed data plus a small amount of routing policy, so all of it is
 * testable without a device — which matters because the routing policy is the part that
 * can quietly lie to the user.
 */
class NavigationLinksTest {

    @Test
    fun `every site supports its default mode, and lists it first`() {
        NavLinks.ALL.forEach { link ->
            assertTrue(
                "${link.id}: default ${link.defaultMode} must also be an offered button",
                link.supportedModes.contains(link.defaultMode),
            )
            assertEquals(
                "${link.id}: the default action has to be rendered first",
                link.defaultMode,
                link.supportedModes.first(),
            )
        }
    }

    @Test
    fun `the six configured sites match the requested list`() {
        assertEquals(6, NavLinks.ALL.size)
        assertEquals(
            listOf("1系统", "canvas系统", "好课平台", "超星学习通/慕课", "智慧树/知到", "课堂派"),
            NavLinks.ALL.map { it.title },
        )
        assertEquals(
            listOf(
                "https://1.tongji.edu.cn",
                "https://canvas.tongji.edu.cn",
                "https://tongji.aihaoke.net/student/course",
                "https://mooc1-1.chaoxing.com",
                "https://www.zhihuishu.com/",
                "https://w.ketangpai.com/ktpCourse",
            ),
            NavLinks.ALL.map { it.url },
        )
        assertEquals(
            listOf(
                NavOpenMode.WECOM,
                NavOpenMode.WECOM,
                NavOpenMode.BROWSER,
                NavOpenMode.BROWSER,
                NavOpenMode.EXTERNAL_APP,
                NavOpenMode.WECHAT,
            ),
            NavLinks.ALL.map { it.defaultMode },
        )
    }

    @Test
    fun `each card gets one explicit button per supported mode, exactly one of them default`() {
        NavLinks.ALL.forEach { link ->
            val actions = link.actions()
            assertEquals("${link.id}: one button per mode", link.supportedModes.size, actions.size)
            assertEquals("${link.id}: exactly one default", 1, actions.count { it.isDefault })
            assertEquals(link.defaultMode, actions.first { it.isDefault }.mode)
            actions.forEach {
                assertTrue("${link.id}: '${it.label}' must not be blank", it.label.isNotBlank())
            }
        }
    }

    @Test
    fun `browser mode is a single unrestricted view intent`() {
        val link = NavLinks.ALL.first()
        assertEquals(
            listOf<NavAttempt>(NavAttempt.ViewUrlAnywhere),
            attemptsFor(link, NavOpenMode.BROWSER),
        )
    }

    @Test
    fun `wechat and wecom try the app, then summon it, then fall back to the browser`() {
        val link = NavLinks.ALL.first()
        assertEquals(
            listOf(
                NavAttempt.ViewUrlIn(WECHAT_PACKAGE),
                NavAttempt.LaunchScheme(WECHAT_SCHEME),
                NavAttempt.ViewUrlAnywhere,
            ),
            attemptsFor(link, NavOpenMode.WECHAT),
        )
        assertEquals(
            listOf(
                NavAttempt.ViewUrlIn(WECOM_PACKAGE),
                NavAttempt.LaunchScheme(WECOM_SCHEME),
                NavAttempt.ViewUrlAnywhere,
            ),
            attemptsFor(link, NavOpenMode.WECOM),
        )
    }

    @Test
    fun `external app mode never silently opens a web page instead`() {
        val chaoxing = NavLinks.ALL.first { it.id == "chaoxing" }
        val chaoxingAttempts = attemptsFor(chaoxing, NavOpenMode.EXTERNAL_APP)
        assertTrue(
            "no browser fallback may hide a missing app",
            chaoxingAttempts.none { it is NavAttempt.ViewUrlAnywhere },
        )
        chaoxing.externalAppPackages.forEach {
            assertTrue("$it should be tried", chaoxingAttempts.contains(NavAttempt.LaunchApp(it)))
        }
        // 知到 has shipped under several package names, so the chain ends with a
        // name-fragment search rather than trusting one hard-coded guess — that guess being
        // wrong is exactly why an installed 知到 reported itself as missing.
        val zhihuishu = NavLinks.ALL.first { it.id == "zhihuishu" }
        val znAttempts = attemptsFor(zhihuishu, NavOpenMode.EXTERNAL_APP)
        assertTrue(znAttempts.none { it is NavAttempt.ViewUrlAnywhere })
        assertEquals(
            NavAttempt.LaunchAppMatching(zhihuishu.externalAppMatchTokens),
            znAttempts.last(),
        )
        assertTrue("the fragment search needs tokens", zhihuishu.externalAppMatchTokens.isNotEmpty())
    }

    @Test
    fun `zhidao is launched by its real package, not one derived from the brand name`() {
        val zhidao = NavLinks.ALL.first { it.id == "zhihuishu" }
        // com.able.wisdomtree shares NO substring with "zhihuishu". Deriving the package
        // from the brand is exactly the mistake that made an installed 知到 report itself
        // as missing, so the real package is pinned here.
        assertEquals("com.able.wisdomtree", zhidao.externalAppPackages.first())
        // And the fragment fallback must be able to match that real name.
        assertTrue(
            zhidao.externalAppMatchTokens.any { token ->
                "com.able.wisdomtree".contains(token, ignoreCase = true)
            },
        )
    }

    @Test
    fun `summoning the app tells the student the link went to the clipboard`() {
        val link = NavLinks.ALL.first { it.id == "tj-1system" }
        val message = NavResult(NavStatus.SUMMONED_APP)
            .messageFor(link, NavOpenMode.WECOM)!!
        assertTrue(message.contains("剪贴板"))
        assertTrue(message.contains("企业微信"))
    }

    @Test
    fun `a clean open says nothing, a degraded one explains itself`() {
        val oneSystem = NavLinks.ALL.first { it.id == "tj-1system" }
        assertNull(NavResult(NavStatus.OPENED).messageFor(oneSystem, NavOpenMode.WECOM))

        val degraded = NavResult(NavStatus.OPENED, usedFallback = true)
            .messageFor(oneSystem, NavOpenMode.WECOM)
        assertTrue(degraded!!.contains("企业微信"))
        assertTrue(degraded.contains("浏览器"))

        val missing = NavResult(NavStatus.UNAVAILABLE)
            .messageFor(NavLinks.ALL.first { it.id == "zhihuishu" }, NavOpenMode.EXTERNAL_APP)
        assertTrue(missing!!.contains("知到"))

        assertFalse(
            NavResult(NavStatus.FAILED).messageFor(oneSystem, NavOpenMode.BROWSER)!!.isBlank(),
        )
    }
}
