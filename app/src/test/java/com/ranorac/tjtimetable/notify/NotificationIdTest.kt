package com.ranorac.tjtimetable.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The notification id has to be stable — a re-run must replace its own notification rather
 * than stack a duplicate — and distinct per meeting, or one class's reminder silently
 * replaces another's.
 *
 * No Android is needed: [notificationId] is a pure function precisely so this can be pinned
 * without a device, which matters because the failure is invisible in the app (a missing
 * notification, not a crash).
 */
class NotificationIdTest {

    private val monday = LocalDate.of(2026, 3, 2)
    private val tuesday = LocalDate.of(2026, 3, 3)

    @Test
    fun `the same meeting always gets the same id`() {
        assertEquals(
            notificationId(monday, 1, "高等数学"),
            notificationId(monday, 1, "高等数学"),
        )
    }

    @Test
    fun `two courses in the same 节 on the same date do not collide`() {
        // FIXED: the id used to be `(epochDay % 100_000) * 100 + startUnit` — identical for
        // both of these, so the second `notify()` replaced the first and one of the two
        // classes never appeared. Two classes at the same hour is not exotic: a 补课日 clash,
        // or simply two courses a student takes in parallel.
        assertNotEquals(
            notificationId(monday, 1, "高等数学"),
            notificationId(monday, 1, "大学物理"),
        )
    }

    @Test
    fun `the date and the 节 each change the id`() {
        assertNotEquals(
            notificationId(monday, 1, "高等数学"),
            notificationId(tuesday, 1, "高等数学"),
        )
        assertNotEquals(
            notificationId(monday, 1, "高等数学"),
            notificationId(monday, 3, "高等数学"),
        )
    }

    @Test
    fun `a name that differs only at the end still differs`() {
        // The discriminator has to survive the whole name, or two long course names sharing a
        // prefix would collide — the very case the id exists to keep apart.
        assertNotEquals(
            notificationId(monday, 1, "习近平新时代中国特色社会主义思想概论"),
            notificationId(monday, 1, "习近平新时代中国特色社会主义思想概论A"),
        )
    }

    @Test
    fun `the id is a positive int`() {
        // NotificationManager takes an Int; a negative id is legal but makes debugging the
        // shade needlessly confusing, and an overflow would wrap silently.
        val dates = listOf(monday, tuesday, LocalDate.of(1970, 1, 1), LocalDate.of(2099, 12, 31))
        for (date in dates) {
            val id = notificationId(date, 11, "体育")
            assertTrue("id must stay positive on $date but was $id", id >= 0)
        }
    }
}
