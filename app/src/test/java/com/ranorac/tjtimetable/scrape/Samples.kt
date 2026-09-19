package com.ranorac.tjtimetable.scrape

/**
 * Synthetic payloads for the adapter tests.
 *
 * ### Why these are synthesised, not captured
 * A real 教务 response is a specific student's timetable — their courses, rooms, teachers and 学号.
 * This project will be published, so no captured payload is committed, copied into
 * `src/test/resources`, or read from a scratch tree: every value below is **invented**
 * (课程名 测试课程A…, 教师 测试教师甲…, 学号/工号 10001…, 教室 测试楼201…, 校区 测试校区).
 *
 * ### What is *not* invented: the field names
 * The shapes below carry the field names a real response uses, including fields the adapter
 * deliberately never reads, so the tests prove the adapter tolerates the real shape rather than
 * only the subset it consumes:
 *
 * - root: `code`, `msg`, `data`
 * - report `data[]` items: `teachingClassId`, `classCode`, `className`, `campus`, `courseCode`,
 *   `courseName`, `assessmentMode`, `credits`, `teacherName`, `classTime`, `classRoom`,
 *   `classRoomName`, `remark`, `compulsory`, `classType`, `courseTakeType`, `teachingWay`,
 *   `newCourseCode`, `newClassCode`, `campusI18n`, `assessmentModeI18n`, `classRoomI18n`,
 *   `teachingWayI18n`, `teachModeI18n`, `timeTableList[]`
 * - `timeTableList[]` entries: `dayOfWeek`, `timeStart`, `timeEnd`, `roomId`, `roomIdI18n`,
 *   `teacherCode`, `teacherName`, `weekNum`, `weekstr`, `weeks[]`, `timeAndRoom`, `timeTab`,
 *   `className`, `classCode`, `courseName`, `courseCode`, `teachingClassId`, `campus`, `campusI18n`,
 *   `popover`, `newPopover`, `roomCategory`, `roomLable`, `newCourseCode`, `newClassCode`
 * - personal `data.selectedCourses[].course.times[]` entries: `arrangeTimeId`, `dayOfWeek`,
 *   `timeStart`, `timeEnd`, `weeks[]`, `roomId`, `roomIdI18n`, `teachClassId`, `teachClassCode`,
 *   `teacherCode`, `teacherCodeI18n`, `value`, `popover`, `newPopover`
 * - 校历 `data[]` terms: `id`, `fullName`, `year`, `term`, `beginDay`, `endDay`, `weekNum`,
 *   `teachingWeekStart`, `teachingWeekEnd`, `currentTermFlag`, `nextTermFlag`,
 *   `noWeekendWorkTimes[]` (`classNode`, `beginTime`, `endTime`), `weekendWorkTimes[]`
 *
 * The live report endpoint is
 * `GET /api/electionservice/reportManagement/findStudentTimetab?calendarId=<n>&studentCode=<encrypted>&_t=<ms>`,
 * so [REPORT] is the shape a student will actually hit; `calendarId` lives on that URL and never in
 * the body, which is why every report test passes it explicitly.
 */
internal object Samples {

    /** The reference's built-in semester table knows 122 (2026-2027 学年第 1 学期, 2026-09-14). */
    const val KNOWN_CALENDAR_ID = "122"

    /** An id the built-in table does **not** know, used for the degradation path. */
    const val UNKNOWN_CALENDAR_ID = "9122"

    /** All 16 teaching weeks, as an explicit array like a real `weeks[]`. */
    val WEEKS_1_TO_16 = (1..16).toList()

    val ODD_WEEKS = listOf(1, 3, 5, 7, 9, 11, 13, 15)
    val EVEN_WEEKS = listOf(2, 4, 6, 8, 10, 12, 14, 16)

    /** `[1,3,5,...]`, i.e. the `weeks` encoding the payload ultimately uses: parity as data. */
    private fun json(ints: List<Int>) = ints.joinToString(",", "[", "]")

    // ------------------------------------------------------------- 选课服务 (getDataBk)

    /**
     * `data.selectedCourses[].course.times[]` — the personal/election shape.
     *
     * 6 courses, 7 `times` entries, one of which has no slots and one of which merges from 9.
     */
    val PERSONAL: String = """
    {
      "code": 200,
      "msg": "success",
      "data": {
        "calendarId": 122,
        "selectedCourses": [
          {"course": {
            "courseName": "测试课程A", "courseCode": "TSTA001", "newCourseCode": "TSTA001N",
            "teachClassId": 9000000000000011, "teachClassCode": "TSTA0A01", "newTeachClassCode": "TSTA0A02",
            "credits": 3.0, "calendarId": 122, "calendarName": "2026-2027学年第1学期",
            "times": [
              {"arrangeTimeId": 1, "dayOfWeek": 4, "timeStart": 5, "timeEnd": 6,
               "weeks": ${json(ODD_WEEKS)}, "roomId": "R201", "roomIdI18n": "测试楼201",
               "teachClassId": 9000000000000011, "teachClassCode": "TSTA0A01",
               "teacherCode": "10001", "teacherCodeI18n": "测试教师甲",
               "value": "测试课程A(TSTA001) 测试教师甲(10001)  [1, 3, 5, 7, 9, 11, 13, 15] 测试楼201",
               "popover": "1-16周", "newPopover": "1-16周"},
              {"arrangeTimeId": 2, "dayOfWeek": 3, "timeStart": 7, "timeEnd": 8,
               "weeks": ${json(WEEKS_1_TO_16)}, "roomId": "R201", "roomIdI18n": "测试楼201",
               "teacherCode": "10001", "teacherCodeI18n": "测试教师甲",
               "value": "测试课程A(TSTA001) 测试教师甲(10001)  [1-16] 测试楼201"}
            ]}},
          {"course": {
            "courseName": "测试课程B", "courseCode": "TSTB001",
            "teachClassId": 9000000000000012, "teachClassCode": "TSTB0B01", "credits": 2.0,
            "times": [
              ${mergedTimesEntry(1, 12, "测试教师乙", "10002")},
              ${mergedTimesEntry(2, 8, "测试教师丙", "10003")},
              ${mergedTimesEntry(3, 7, "测试教师丁", "10004")},
              ${mergedTimesEntry(4, 5, "测试教师戊", "10005")},
              ${mergedTimesEntry(5, 11, "测试教师己", "10006")},
              ${mergedTimesEntry(6, 9, "测试教师庚", "10007")},
              ${mergedTimesEntry(7, 6, "测试教师辛", "10008")},
              ${mergedTimesEntry(8, 10, "测试教师壬", "10009")},
              ${mergedTimesEntry(9, 0, "测试教师癸", "10010")}
            ]}},
          {"course": {
            "courseName": "测试课程C", "courseCode": "TSTC001",
            "teachClassId": 9000000000000013, "teachClassCode": "TSTC0C01",
            "times": [
              {"dayOfWeek": 2, "timeStart": 1, "timeEnd": 2, "weeks": [],
               "roomIdI18n": "测试楼101", "teacherCode": "10011", "teacherCodeI18n": "测试教师子"}
            ]}},
          {"course": {
            "courseName": "测试课程D", "courseCode": "TSTD001",
            "teachClassId": 9000000000000014, "teachClassCode": "TSTD0D01",
            "times": []}},
          {"course": {
            "courseName": "测试课程E", "courseCode": "TSTE001",
            "teachClassId": 9000000000000015, "teachClassCode": "TSTE0E01",
            "times": [
              {"dayOfWeek": 1, "timeStart": 3, "timeEnd": 4, "weeks": [1, 2, 3],
               "roomIdI18n": "", "teacherCode": "10012", "teacherCodeI18n": "测试教师丑"}
            ]}},
          {"course": {
            "courseName": "测试课程F", "courseCode": "TSTF001",
            "teachClassId": 9000000000000016, "teachClassCode": "TSTF0F01",
            "times": [
              {"dayOfWeek": 5, "timeStart": 9, "timeEnd": 10, "weeks": ${json(EVEN_WEEKS)},
               "roomIdI18n": "测试楼401", "teacherCode": "10013", "teacherCodeI18n": "测试教师寅"}
            ]}}
        ]
      }
    }
    """.trimIndent()

    /**
     * One entry of the merge group: 9 `times` in the *same* grid cell (周三 9-10 节, 测试楼301),
     * each taught by a different teacher in a different week — the week-by-week teacher swap the
     * reference documents. The last entry covers weeks 1-4 and 13-16, so the union is the whole term.
     */
    private fun mergedTimesEntry(index: Int, singleWeek: Int, teacher: String, code: String): String {
        val weeks = if (singleWeek == 0) listOf(1, 2, 3, 4, 13, 14, 15, 16) else listOf(singleWeek)
        return """{"arrangeTimeId": ${100 + index}, "dayOfWeek": 3, "timeStart": 9, "timeEnd": 10,
               "weeks": ${json(weeks)}, "roomIdI18n": "测试楼301",
               "teacherCode": "$code", "teacherCodeI18n": "$teacher",
               "value": "测试课程B(TSTB001) $teacher($code)  ${json(weeks)} 测试楼301"}"""
    }

    // ------------------------------------------------------------- 报表接口 (findStudentTimetab)

    /**
     * `data[].timeTableList[]` — the 报表接口 shape, i.e. the endpoint a student actually hits.
     *
     * 10 course items (one with no slots) and 18 `timeTableList` entries, one group of 9 of which
     * merges into a single block. Course-level fields the adapter never reads are present on
     * purpose, so an accidental dependency on them would show up as a failure.
     */
    val REPORT: String = """
    {
      "code": 200,
      "msg": "success",
      "data": [
        ${reportCourse(
        id = 9000000000000011,
        name = "测试课程A",
        courseCode = "TSTA001",
        classCode = "TSTA0A01",
        teacherName = "测试教师甲",
        campusI18n = "测试校区",
        times = listOf(
            reportTime(day = 2, start = 3, end = 4, room = "测试楼201", weeks = WEEKS_1_TO_16,
                teacherName = "测试教师甲(10001)", teacherCode = "10001"),
            reportTime(day = 4, start = 5, end = 6, room = "测试楼201", weeks = EVEN_WEEKS,
                teacherName = "测试教师甲(10001)", teacherCode = "10001"),
        ),
    )}
        ,
        ${reportCourse(
        id = 9000000000000012,
        name = "测试课程B",
        courseCode = "TSTB002",
        classCode = "TSTB0B01",
        teacherName = "测试教师乙",
        campusI18n = "测试校区",
        // roomIdI18n empty → the reference falls back to roomLable (线上课堂 / 操场-style venues).
        times = listOf(
            reportTime(day = 6, start = 9, end = 10, room = "", roomLable = "线上课堂",
                weeks = WEEKS_1_TO_16, teacherName = "测试教师乙(10002)", teacherCode = "10002"),
        ),
    )}
        ,
        ${reportCourse(
        id = 9000000000000013,
        name = "测试课程C",
        courseCode = "TSTC003",
        classCode = "TSTC0C01",
        teacherName = "测试教师丙",
        campusI18n = "测试校区",
        times = listOf(
            reportTime(day = 3, start = 5, end = 6, room = "", roomLable = "测试操场（2号）",
                weeks = WEEKS_1_TO_16, teacherName = "测试教师丙(10003)", teacherCode = "10003"),
        ),
    )}
        ,
        ${reportCourse(
        id = 9000000000000014,
        name = "测试课程D",
        courseCode = "TSTD004",
        classCode = "TSTD0D01",
        teacherName = "测试教师丁",
        campusI18n = "测试校区",
        times = listOf(
            reportTime(day = 3, start = 9, end = 10, room = "测试楼301", weeks = listOf(12),
                teacherName = "测试教师丁(10004)", teacherCode = "10004"),
            reportTime(day = 3, start = 9, end = 10, room = "测试楼301", weeks = listOf(8),
                teacherName = "测试教师戌(10014)", teacherCode = "10014"),
            reportTime(day = 3, start = 9, end = 10, room = "测试楼301", weeks = listOf(7),
                teacherName = "测试教师亥(10015)", teacherCode = "10015"),
            reportTime(day = 3, start = 9, end = 10, room = "测试楼301", weeks = listOf(5),
                teacherName = "测试教师甲(10001)", teacherCode = "10001"),
            reportTime(day = 3, start = 9, end = 10, room = "测试楼301", weeks = listOf(11),
                teacherName = "测试教师乙(10002)", teacherCode = "10002"),
            reportTime(day = 3, start = 9, end = 10, room = "测试楼301", weeks = listOf(9),
                teacherName = "测试教师丙(10003)", teacherCode = "10003"),
            reportTime(day = 3, start = 9, end = 10, room = "测试楼301", weeks = listOf(6),
                teacherName = "测试教师辰(10016)", teacherCode = "10016"),
            reportTime(day = 3, start = 9, end = 10, room = "测试楼301", weeks = listOf(10),
                teacherName = "测试教师戊(10005)", teacherCode = "10005"),
            reportTime(day = 3, start = 9, end = 10, room = "测试楼301",
                weeks = listOf(1, 2, 3, 4, 13, 14, 15, 16),
                teacherName = "测试教师己(10006)", teacherCode = "10006"),
        ),
    )}
        ,
        ${reportCourse(
        id = 9000000000000015,
        name = "测试课程E",
        courseCode = "TSTE005",
        classCode = "TSTE0E01",
        teacherName = "测试教师庚",
        campusI18n = "测试校区",
        times = emptyList(),
    )}
        ,
        ${reportCourse(
        id = 9000000000000016,
        name = "测试课程F",
        courseCode = "TSTF006",
        classCode = "TSTF0F01",
        teacherName = "测试教师辛",
        campusI18n = "测试校区",
        times = listOf(
            reportTime(day = 2, start = 7, end = 8, room = "测试楼301", weeks = listOf(11, 12, 13, 14),
                teacherName = "测试教师辛(10007)", teacherCode = "10007"),
        ),
    )}
        ,
        ${reportCourse(
        id = 9000000000000017,
        name = "测试课程G",
        courseCode = "TSTG007",
        classCode = "TSTG0G01",
        teacherName = "测试教师壬",
        campusI18n = "测试校区",
        times = listOf(
            reportTime(day = 4, start = 7, end = 8, room = "测试楼307", weeks = (1..12).toList(),
                teacherName = "测试教师壬(10008)", teacherCode = "10008"),
        ),
    )}
        ,
        ${reportCourse(
        id = 9000000000000018,
        name = "测试课程H",
        courseCode = "TSTH008",
        classCode = "TSTH0H01",
        teacherName = "测试教师癸",
        campusI18n = "测试校区",
        // 姓名 without a 工号 in it → the row's teacherCode is appended.
        times = listOf(
            reportTime(day = 1, start = 1, end = 2, room = "测试楼101", weeks = WEEKS_1_TO_16,
                teacherName = "测试教师癸", teacherCode = "10009"),
        ),
    )}
        ,
        ${reportCourse(
        id = 9000000000000019,
        name = "测试课程I",
        courseCode = "TSTI009",
        classCode = "TSTI0I01",
        teacherName = "测试教师子",
        campusI18n = "测试校区",
        // 姓名 that already carries a 工号 → it must NOT be appended again ("姓名(工号)(工号)").
        times = listOf(
            reportTime(day = 5, start = 1, end = 2, room = "测试楼109", weeks = WEEKS_1_TO_16,
                teacherName = "测试教师子(10010)", teacherCode = "10010"),
        ),
    )}
        ,
        ${reportCourse(
        id = 9000000000000020,
        name = "测试课程J",
        courseCode = "TSTJ010",
        classCode = "TSTJ0J01",
        // No teacher on the row at all → fall back to the course-level 姓名,姓名 list.
        teacherName = "测试教师丑,测试教师寅",
        campusI18n = "测试校区",
        times = listOf(
            reportTime(day = 5, start = 3, end = 4, room = "测试楼203", weeks = WEEKS_1_TO_16,
                teacherName = "", teacherCode = ""),
        ),
    )}
      ]
    }
    """.trimIndent()

    private fun reportCourse(
        id: Long,
        name: String,
        courseCode: String,
        classCode: String,
        teacherName: String,
        campusI18n: String,
        times: List<String>,
    ): String = """
        {
          "teachingClassId": $id, "classCode": "$classCode", "className": "测试01班",
          "campus": "1", "campusI18n": "$campusI18n", "courseCode": "$courseCode",
          "courseName": "$name", "assessmentMode": "考试", "assessmentModeI18n": "考试",
          "credits": 3.0, "teacherName": "$teacherName", "classTime": "", "classRoom": "",
          "classRoomName": "", "classRoomI18n": "", "remark": "",
          "compulsory": true, "classType": "必修", "courseTakeType": "主修",
          "teachingWay": "线下授课", "teachingWayI18n": "线下授课", "teachModeI18n": "线下",
          "newCourseCode": "${courseCode}N", "newClassCode": "${classCode}N",
          "timeTableList": [${times.joinToString(",")}]
        }
    """.trimIndent()

    private fun reportTime(
        day: Int,
        start: Int,
        end: Int,
        room: String,
        weeks: List<Int>,
        teacherName: String,
        teacherCode: String,
        roomLable: String = "",
    ): String = """
        {"dayOfWeek": $day, "timeStart": $start, "timeEnd": $end,
         "roomId": "RID", "roomIdI18n": "$room", "roomLable": "$roomLable",
         "classRoomName": "", "roomCategory": "教学楼",
         "teacherCode": "$teacherCode", "teacherName": "$teacherName",
         "weekNum": "[]", "weekstr": "", "weeks": ${json(weeks)},
         "timeAndRoom": "", "timeTab": "", "popover": "", "newPopover": "",
         "className": "测试01班", "classCode": "TEST", "courseName": "测试课程",
         "courseCode": "TEST", "teachingClassId": 1, "campus": "1", "campusI18n": "测试校区",
         "newCourseCode": "TESTN", "newClassCode": "TESTN"}
    """.trimIndent()

    // ------------------------------------------------------------- 校历

    /** 11 invented 节次 rows, so "the payload's table won" is observable (08:05 ≠ 08:00). */
    private val SLOT_TIMES = listOf(
        "08:05" to "08:50",
        "09:00" to "09:45",
        "10:05" to "10:50",
        "11:00" to "11:45",
        "13:05" to "13:50",
        "14:00" to "14:45",
        "15:05" to "15:50",
        "16:00" to "16:45",
        "18:05" to "18:50",
        "19:00" to "19:45",
        "20:05" to "20:50",
    )

    /**
     * `data[]` terms. `beginDay` is a synthetic millisecond timestamp — UTC midnight of
     * 2026-09-07 (`1788739200000`), which is a Monday, so the derived teaching-week-1 start is
     * checkable by hand. The 节次 table is deliberately *not* the built-in 同济 table (it starts
     * 08:05) so a test can tell whether the payload's own table was used.
     */
    val SCHOOL_CALENDAR: String = """
    {
      "code": 200,
      "msg": "success",
      "data": [
        {
          "id": 9122, "fullName": "测试学年第一学期", "year": 2026, "term": 1,
          "beginDay": 1788739200000, "endDay": 1798329600000,
          "weekNum": 16, "teachingWeekStart": 1, "teachingWeekEnd": 16,
          "currentTermFlag": true, "nextTermFlag": false,
          "noWeekendWorkTimes": [
            ${SLOT_TIMES.mapIndexed { i, (b, e) -> "{\"classNode\": ${i + 1}, \"beginTime\": \"$b\", \"endTime\": \"$e\"}" }.joinToString(",")}
          ],
          "weekendWorkTimes": []
        },
        {
          "id": 9123, "fullName": "测试学年第二学期", "year": 2027, "term": 2,
          "beginDay": 1810224000000, "endDay": 1819814400000,
          "weekNum": 16, "teachingWeekStart": 1, "teachingWeekEnd": 16,
          "currentTermFlag": false, "nextTermFlag": true,
          "noWeekendWorkTimes": null, "weekendWorkTimes": []
        }
      ]
    }
    """.trimIndent()

    // ------------------------------------------------------------- 排课服务 (flat)

    /** Flat rows: one slot per row, weeks as a `weekState` bitmask (bit 0 = week 1). */
    val FLAT: String = """
    {
      "code": 200, "msg": "success",
      "data": [
        {"teachingClassId": 9101, "code": "FLT01", "courseCode": "FLT", "courseName": "测试课程K",
         "dayOfWeek": 2, "timeStart": 3, "timeEnd": 4, "weekState": 65535, "roomName": "测试楼202",
         "facultyI18n": "测试学院", "campusI18n": "测试校区",
         "value": "测试课程K 测试教师卯(10016) 星期二3-4节 [1-16] 测试楼202"},
        {"teachingClassId": 9102, "courseCode": "FLU", "courseName": "测试课程L",
         "dayOfWeek": 1, "timeStart": 1, "timeEnd": 2, "weekState": -2147483648, "roomName": "测试楼203",
         "teacherCodes": ["10017", "10018"]}
      ]
    }
    """.trimIndent()

    // ------------------------------------------------------------- small focused payloads

    /** Wraps a `selectedCourses`/raw payload in the usual `{code,msg,data}` envelope. */
    fun envelope(data: String): String = """{"code":200,"msg":"success","data":$data}"""

    /** A minimal valid personal payload with a single course, for focused assertions. */
    fun personalWith(selectedCourses: String): String =
        """{"data":{"calendarId":122,"selectedCourses":[$selectedCourses]}}"""

    /** A minimal valid report payload with a single course item. */
    fun reportWith(items: String): String = """{"data":[$items]}"""
}
