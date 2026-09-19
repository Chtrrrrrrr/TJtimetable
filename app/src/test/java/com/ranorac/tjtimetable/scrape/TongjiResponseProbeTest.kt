package com.ranorac.tjtimetable.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the reference's `dotnet/TjtCore.Tests/TongjiResponseProbeTests.cs` — 逐条对齐
 * `looksLikeTimetable` 的分支，因为这段文案会**原样显示给用户**，分支走错就等于给错提示
 * （「粘错请求」是这条链路最常见的失败）。
 */
class TongjiResponseProbeTest {

    @Test
    fun selectedCoursesIsAccepted() {
        val verdict = TongjiResponseProbe.inspect("""{"code":200,"data":{"selectedCourses":[{},{}]}}""")
        assertTrue(verdict.ok)
        assertEquals("selectedCourses 2 门", verdict.note)
    }

    @Test
    fun aDataArrayIsAccepted() {
        val verdict = TongjiResponseProbe.inspect("""{"code":200,"data":[{"a":1},{"a":2},{"a":3}]}""")
        assertTrue(verdict.ok)
        assertEquals("data 数组 3 条", verdict.note)
    }

    @Test
    fun aMessageWithoutDataQuotesTheServer() {
        val verdict = TongjiResponseProbe.inspect("""{"code":500,"message":"登录状态已失效"}""")
        assertFalse(verdict.ok)
        assertTrue(verdict.note, verdict.note.contains("登录状态已失效"))
    }

    @Test
    fun dataWithoutSelectedCoursesListsTheFields() {
        val verdict = TongjiResponseProbe.inspect("""{"data":{"studentId":"x","termId":1}}""")
        assertFalse(verdict.ok)
        assertTrue(verdict.note, verdict.note.contains("studentId"))
        assertTrue(verdict.note, verdict.note.contains("termId"))
    }

    @Test
    fun aPayloadWithoutDataListsTheTopLevelFields() {
        val verdict = TongjiResponseProbe.inspect("""{"code":200,"foo":1,"bar":2}""")
        assertFalse(verdict.ok)
        assertTrue(verdict.note, verdict.note.contains("code"))
        assertTrue(verdict.note, verdict.note.contains("foo"))
    }

    @Test
    fun atMostEightFieldNamesAreListed() {
        val verdict = TongjiResponseProbe.inspect(
            """{"a":1,"b":2,"c":3,"d":4,"e":5,"f":6,"g":7,"h":8,"i":9,"j":10}""",
        )
        assertFalse(verdict.ok)
        assertFalse(verdict.note, verdict.note.contains("i,"))
        assertFalse(verdict.note, verdict.note.contains("j"))
        assertTrue(verdict.note, verdict.note.endsWith("h"))
    }

    @Test
    fun nonJsonAndBlankAreRejected() {
        assertFalse(TongjiResponseProbe.inspect("<html><body>登录</body></html>").ok)
        assertFalse(TongjiResponseProbe.inspect("<!doctype html><html></html>").ok)
        assertFalse(TongjiResponseProbe.inspect("").ok)
        assertFalse(TongjiResponseProbe.inspect("   ").ok)
        assertFalse(TongjiResponseProbe.inspect(null).ok)
        // 顶层是数组（不是对象）也不是我们要的形状
        assertFalse(TongjiResponseProbe.inspect("[1,2,3]").ok)
        assertFalse(TongjiResponseProbe.inspect("42").ok)
    }

    @Test
    fun anOverlongServerMessageIsTruncated() {
        val message = "长".repeat(200)
        val verdict = TongjiResponseProbe.inspect("""{"message":"$message"}""")
        assertFalse(verdict.ok)
        // "服务端返回：" 前缀 + 最多 80 个字符
        assertEquals(6 + 80, verdict.note.length)
    }

    @Test
    fun aFullReportPayloadIsAccepted() {
        // The 报表接口 shape (the endpoint a student actually hits), synthetic values.
        val verdict = TongjiResponseProbe.inspect(Samples.REPORT)
        assertTrue(verdict.note, verdict.ok)
        assertEquals("data 数组 10 条", verdict.note)
    }

    @Test
    fun aFullPersonalPayloadIsAccepted() {
        val verdict = TongjiResponseProbe.inspect(Samples.PERSONAL)
        assertTrue(verdict.note, verdict.ok)
        assertEquals("selectedCourses 6 门", verdict.note)
    }

    @Test
    fun aTimetableBodyThatFailsToParseIsStillRejectedByTheProbe() {
        // The probe only judges "does this look like timetable data"; whether it parses is the
        // adapter's job (and is covered by the adapter tests).
        val verdict = TongjiResponseProbe.inspect(Samples.REPORT.substring(0, 40))
        assertFalse(verdict.ok)
        assertTrue(verdict.note, verdict.note.contains("不是合法 JSON"))
    }
}
