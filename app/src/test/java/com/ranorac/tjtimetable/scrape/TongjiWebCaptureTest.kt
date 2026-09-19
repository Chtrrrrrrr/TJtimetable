package com.ranorac.tjtimetable.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the reference's `dotnet/TjtCore.Tests/TongjiWebCaptureTests.cs`, case for case.
 *
 * 这几条都直接决定「登录窗口能不能抓到课表」，而且都**不碰窗口**，所以钉在这里。
 */
class TongjiWebCaptureTest {

    private val reportUrl =
        "https://1.tongji.edu.cn/api/electionservice/reportManagement/findStudentTimetab?calendarId=122&studentCode=abc"

    /** 认得出三条课表接口 — the reference's 4 InlineData URLs. */
    @Test
    fun recognisesAllThreeTimetableEndpoints() {
        val urls = listOf(
            reportUrl,
            "https://1.tongji.edu.cn/api/electionservice/reportManagement/findSchoolTimetab2?id=1",
            "https://1.tongji.edu.cn/api/electionservice/student/123/getDataBk",
            "https://1.tongji.edu.cn/api/x/FINDSTUDENTTIMETAB?calendarId=1",
        )
        for (url in urls) assertTrue(url, TongjiWebCapture.isEndpoint(url))
    }

    @Test
    fun otherRequestsAreNotEndpoints() {
        val urls = listOf(
            "https://1.tongji.edu.cn/api/baseresservice/schoolCalendar/detail?calendarId=122",
            "https://1.tongji.edu.cn/",
            "https://1.tongji.edu.cn/static/js/app.1a2b3c.js",
            "",
            null,
        )
        for (url in urls) assertFalse(url.orEmpty(), TongjiWebCapture.isEndpoint(url))
    }

    @Test
    fun endpointLabelNamesTheEndpoint() {
        assertEquals("报表接口 findStudentTimetab", TongjiWebCapture.endpointLabel(reportUrl))
        assertEquals(
            "研究生报表接口 findSchoolTimetab2",
            TongjiWebCapture.endpointLabel("https://x/findSchoolTimetab2"),
        )
        assertEquals("选课服务 getDataBk", TongjiWebCapture.endpointLabel("https://x/getDataBk"))
        assertEquals("未知接口", TongjiWebCapture.endpointLabel("https://x/whatever"))
        assertEquals("未知接口", TongjiWebCapture.endpointLabel(null))
    }

    @Test
    fun recognisesTongjiHosts() {
        assertTrue(TongjiWebCapture.isTongjiHost("1.tongji.edu.cn"))
        assertTrue(TongjiWebCapture.isTongjiHost("tongji.edu.cn"))
        assertTrue(TongjiWebCapture.isTongjiHost("MyPortal.Tongji.Edu.Cn"))
        assertFalse(TongjiWebCapture.isTongjiHost("eviltongji.edu.cn"))
        assertFalse(TongjiWebCapture.isTongjiHost("tongji.edu.cn.evil.com"))
        assertFalse(TongjiWebCapture.isTongjiHost(""))
        assertFalse(TongjiWebCapture.isTongjiHost(null))
    }

    @Test
    fun recognisesTongjiUrls() {
        assertTrue(TongjiWebCapture.isTongjiUrl("https://1.tongji.edu.cn/api/x"))
        assertTrue(TongjiWebCapture.isTongjiUrl("http://ids.tongji.edu.cn:8443/nidp/idff/sso"))
        assertFalse(TongjiWebCapture.isTongjiUrl("https://example.com/1.tongji.edu.cn"))
        assertFalse(TongjiWebCapture.isTongjiUrl("file:///C:/tongji.edu.cn"))
        assertFalse(TongjiWebCapture.isTongjiUrl("not a url"))
    }

    @Test
    fun readsTheTermIdOffTheReportUrl() {
        assertEquals("122", TongjiWebCapture.calendarIdOf(reportUrl))
        // Parameter names are matched case-insensitively, like the reference.
        assertEquals("122", TongjiWebCapture.calendarIdOf("https://x/y?foo=1&CALENDARID=122"))
        assertNull(TongjiWebCapture.calendarIdOf("https://x/y?studentCode=abc"))
        assertNull(TongjiWebCapture.calendarIdOf("https://x/y?calendarId="))
        assertNull(TongjiWebCapture.calendarIdOf("https://x/y"))
        assertNull(TongjiWebCapture.calendarIdOf(null))
    }

    @Test
    fun readsTheTermIdOffTheLiveReportUrlShape() {
        // The live endpoint is
        //   GET /api/electionservice/reportManagement/findStudentTimetab?calendarId=<n>&studentCode=<encrypted>&_t=<ms>
        // so calendarId has to be found among unrelated parameters, whatever their order.
        val live = "https://1.tongji.edu.cn/api/electionservice/reportManagement/findStudentTimetab" +
            "?calendarId=122&studentCode=ENCRYPTED0VALUE&_t=1789257600000"

        assertTrue(TongjiWebCapture.isEndpoint(live))
        assertEquals("报表接口 findStudentTimetab", TongjiWebCapture.endpointLabel(live))
        assertEquals("122", TongjiWebCapture.calendarIdOf(live))
        assertEquals("报表接口 findStudentTimetab，1024 字节，calendarId=122", TongjiWebCapture.describe(live, 1024))
        // …and the same parameter placed last is still found.
        assertEquals(
            "122",
            TongjiWebCapture.calendarIdOf(
                "https://1.tongji.edu.cn/api/electionservice/reportManagement/findStudentTimetab" +
                    "?studentCode=ENCRYPTED0VALUE&_t=1789257600000&calendarId=122",
            ),
        )
    }

    @Test
    fun queryValuesAreUrlDecoded() {
        assertEquals("a b/c", TongjiWebCapture.queryValue("https://x/y?name=a%20b%2Fc", "name"))
        assertEquals("x y", TongjiWebCapture.queryValue("https://x/y?name=x+y", "name"))
        // DIVERGENCE, deliberate and documented: .NET's Uri.TryCreate accepts a malformed escape
        // ("%zz") and Uri.UnescapeDataString then leaves it alone, while java.net.URI refuses to
        // parse the URL at all — so such a URL yields no parameter here instead of "100%zz".
        assertNull(TongjiWebCapture.queryValue("https://x/y?name=100%zz", "name"))
        // A well-formed escape still round-trips, so the divergence is limited to bad escapes.
        assertEquals("100%", TongjiWebCapture.queryValue("https://x/y?name=100%25", "name"))
    }

    @Test
    fun cookiesAreFilteredByDomainAndPathIntoCookieHeader() {
        val cookies = listOf(
            WebCookie("JSESSIONID", "abc", "1.tongji.edu.cn", "/"),
            WebCookie("tenantCode", "200092", ".tongji.edu.cn", "/"),
            WebCookie("other", "nope", "example.com", "/"),
        )

        val header = TongjiWebCapture.cookieHeader(reportUrl, cookies)
        assertTrue(header, header.contains("JSESSIONID=abc"))
        assertTrue(header, header.contains("tenantCode=200092"))
        assertFalse(header, header.contains("other"))
    }

    @Test
    fun cookiesWithNonMatchingPathsAreNotSent() {
        val cookies = listOf(
            WebCookie("apiOnly", "1", "1.tongji.edu.cn", "/api"),
            WebCookie("elsewhere", "2", "1.tongji.edu.cn", "/other"),
        )

        val header = TongjiWebCapture.cookieHeader(reportUrl, cookies)
        assertTrue(header, header.contains("apiOnly=1"))
        assertFalse(header, header.contains("elsewhere"))

        // /api 不该匹配 /apix（RFC 6265 的边界规则）
        assertEquals("", TongjiWebCapture.cookieHeader("https://1.tongji.edu.cn/apix/y", cookies))
    }

    @Test
    fun duplicateCookieNamesTakeTheLongerPath() {
        val cookies = listOf(
            WebCookie("token", "generic", "1.tongji.edu.cn", "/"),
            WebCookie("token", "specific", "1.tongji.edu.cn", "/api/electionservice"),
        )

        val header = TongjiWebCapture.cookieHeader(reportUrl, cookies)
        assertTrue(header, header.contains("token=specific"))
        assertFalse(header, header.contains("token=generic"))
        assertEquals(1, header.split("; ").size)
    }

    @Test
    fun noCookiesMeansAnEmptyHeader() {
        assertEquals("", TongjiWebCapture.cookieHeader(reportUrl, emptyList()))
        assertEquals("", TongjiWebCapture.cookieHeader(reportUrl, null))
    }

    @Test
    fun reportResponseIsAcceptedAndCarriesTheTermId() {
        val verdict = TongjiWebCapture.inspect(reportUrl, """{"code":200,"data":[{"courseName":"高数"}]}""")

        assertTrue(verdict.note, verdict.ok)
        assertEquals("data 数组 1 条", verdict.note)
        assertEquals("122", verdict.termId)
    }

    @Test
    fun theOldElectionServiceResponseIsAlsoAccepted() {
        val verdict = TongjiWebCapture.inspect(
            "https://1.tongji.edu.cn/api/electionservice/student/1/getDataBk",
            """{"data":{"calendarId":122,"selectedCourses":[{}]}}""",
        )

        assertTrue(verdict.note, verdict.ok)
        assertEquals("selectedCourses 1 门", verdict.note)
        // The election service keeps calendarId in the body, so nothing can be read off the URL.
        assertNull(verdict.termId)
    }

    @Test
    fun aResponseFromANonEndpointIsIgnored() {
        val verdict = TongjiWebCapture.inspect("https://1.tongji.edu.cn/static/js/app.js", "alert(1)")

        assertFalse(verdict.ok)
        assertEquals("不是课表接口（已忽略）", verdict.note)
        assertNull(verdict.termId)
    }

    @Test
    fun aLoginPageIsReportedAsNotJson() {
        val verdict = TongjiWebCapture.inspect(reportUrl, "<!doctype html><html></html>")

        assertFalse(verdict.ok)
        assertTrue(verdict.note, verdict.note.contains("不是合法 JSON"))
    }

    @Test
    fun theLogLineHasNoCookieAndNoBody() {
        assertEquals(
            "报表接口 findStudentTimetab，30261 字节，calendarId=122",
            TongjiWebCapture.describe(reportUrl, 30261),
        )
        // No calendarId on the URL → the log line says so rather than printing nothing.
        assertEquals("未知接口，12 字节，calendarId=无", TongjiWebCapture.describe("https://x/y", 12))
    }
}
