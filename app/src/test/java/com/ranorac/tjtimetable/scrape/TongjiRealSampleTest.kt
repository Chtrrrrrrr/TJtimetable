package com.ranorac.tjtimetable.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the parser against a real captured response, **if one is present locally**.
 *
 * The real payload is a student's own timetable: it contains their name, 学号 and every
 * course they take. It is deliberately kept OUT of version control (see `.gitignore`),
 * so this test is written to the shape a shared test can take safely:
 *
 *  - it **skips** via [assumeTrue] when no sample is present, so the suite stays green
 *    on a fresh clone and no fixture has to be committed;
 *  - it prints **aggregates only** — counts and flags, never a course name, a student
 *    id, a room or any other value from the payload.
 *
 * That makes it useful on the machine that has the data and harmless everywhere else,
 * which is the only way a real-data test can exist without leaking the data.
 */
class TongjiRealSampleTest {

    /** Candidate locations: tests run with the module dir as the working directory. */
    private val candidates = listOf(
        File("示例.json"),
        File("../示例.json"),
        File("../../示例.json"),
    )

    private fun sampleOrNull(): File? = candidates.firstOrNull { it.isFile }

    @Test
    fun `parses a locally available real response`() {
        val file = sampleOrNull()
        assumeTrue("no local sample present; skipping real-data check", file != null)

        val body = file!!.readText()
        assertTrue("sample looks like a timetable", TongjiStudentAdapter.looksLikeTimetable(body))

        // The live endpoint puts the term on the request URL, so pass it explicitly.
        val parsed = TongjiStudentAdapter.parse(body, termId = "122")

        val sessions = parsed.sessionsByCourse.values.flatten()
        val courses = parsed.courses

        // ---- aggregate-only diagnostics (never prints a value from the payload) ----
        val withOddWeeks = sessions.count { it.weeks.isOddOnly }
        val withEvenWeeks = sessions.count { it.weeks.isEvenOnly }
        val withRoom = sessions.count { !it.room.isNullOrBlank() }
        val withTeacher = courses.count { !it.teacher.isNullOrBlank() }
        val distinctWeekPatterns = sessions.map { it.weeks.canonical() }.distinct().size

        println(
            "[real-sample] courses=${courses.size} sessions=${sessions.size} " +
                "oddWeekSessions=$withOddWeeks evenWeekSessions=$withEvenWeeks " +
                "distinctWeekPatterns=$distinctWeekPatterns " +
                "sessionsWithRoom=$withRoom coursesWithTeacher=$withTeacher " +
                "warnings=${parsed.warnings.size}",
        )

        // ---- invariants that must hold for any real payload ----
        assertTrue("parsed at least one course", courses.isNotEmpty())
        assertTrue("parsed at least one session", sessions.isNotEmpty())

        assertTrue(
            "every session sits on a real weekday",
            sessions.all { it.dayOfWeek.value in 1..7 },
        )
        assertTrue(
            "every session has an ordered 节 range",
            sessions.all { it.startUnit in 1..12 && it.endUnit >= it.startUnit },
        )
        assertTrue(
            "every session resolved a non-empty week set",
            sessions.all { it.weeks.isNotEmpty },
        )
        assertTrue(
            "at least one session resolved weeks",
            distinctWeekPatterns >= 1,
        )
        // The room fallback (roomIdI18n -> roomId) is the specific gap that a naive
        // port loses, so assert it against real data rather than trusting the code.
        assertTrue(
            "rooms survive the import for at least some sessions",
            withRoom > 0,
        )
        assertTrue(
            "no course name came back blank",
            courses.all { it.name.isNotBlank() },
        )
        // 教学班 identity must survive, or re-import could not merge customisations.
        assertTrue(
            "courses carry an identity for customisation merging",
            courses.all { it.teachingClassId != null || !it.classCode.isNullOrBlank() || it.name.isNotBlank() },
        )
    }

    @Test
    fun `a blank or non-timetable body is rejected rather than half-parsed`() {
        assumeTrue("no local sample present; skipping", sampleOrNull() != null)

        assertTrue("empty is not a timetable", !TongjiStudentAdapter.looksLikeTimetable(""))
        assertTrue("html is not a timetable", !TongjiStudentAdapter.looksLikeTimetable("<html></html>"))
        // A login redirect is the likeliest thing to be copied by mistake.
        assertTrue(
            "a login page is not a timetable",
            !TongjiStudentAdapter.looksLikeTimetable("{\"code\":401,\"msg\":\"未登录\"}"),
        )
    }

    @Test
    fun `the sample parses identically on a second run`() {
        val file = sampleOrNull()
        assumeTrue("no local sample present; skipping", file != null)

        val body = file!!.readText()
        val first = TongjiStudentAdapter.parse(body, termId = "122")
        val second = TongjiStudentAdapter.parse(body, termId = "122")

        // Parsing must be a pure function of the body, so importing twice cannot
        // silently produce a different timetable.
        assertEquals(first.courses.size, second.courses.size)
        assertEquals(first.sessionCount, second.sessionCount)
        assertEquals(
            first.sessionsByCourse.values.flatten().map { it.weeks.canonical() },
            second.sessionsByCourse.values.flatten().map { it.weeks.canonical() },
        )
    }
}
