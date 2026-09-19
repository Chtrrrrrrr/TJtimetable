package com.ranorac.tjtimetable.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ranorac.tjtimetable.ui.components.SnackbarMessageEffect
import com.ranorac.tjtimetable.ui.theme.TJTimetableTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins the order inside [SnackbarMessageEffect]: the message must be marked as handled
 * immediately, not after the snackbar has had its time on screen.
 *
 * The bug this covers: `showSnackbar` suspends until the snackbar goes away, so consuming
 * *after* it meant that switching pages mid-snackbar never consumed the message at all —
 * and every return to that page replayed it. Here the snackbar is never dismissed (there is
 * no host to dismiss it), which is exactly the state the old code was stuck in, so a
 * regression to "consume after showing" fails this test rather than passing it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SnackbarMessageEffectTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the message is consumed even though the snackbar is never dismissed`() {
        val host = SnackbarHostState()
        var consumed = 0
        var message by mutableStateOf<String?>("已更新校历与调休（2 天有特殊安排）")

        compose.setContent {
            TJTimetableTheme {
                SnackbarMessageEffect(
                    message = message,
                    host = host,
                    onConsumed = {
                        consumed++
                        // A real ViewModel clears its message here, which changes this
                        // effect's key — the show must survive that.
                        message = null
                    },
                )
            }
        }

        compose.waitForIdle()
        assertEquals(1, consumed)

        // Re-composing with the (now null) message must not consume or show anything again:
        // this is what "切回来又弹一次" was.
        compose.waitForIdle()
        assertEquals(1, consumed)
    }

    @Test
    fun `a null message is not consumed`() {
        var consumed = 0

        compose.setContent {
            TJTimetableTheme {
                SnackbarMessageEffect(
                    message = null,
                    host = SnackbarHostState(),
                    onConsumed = { consumed++ },
                )
            }
        }

        compose.waitForIdle()
        assertEquals(0, consumed)
    }
}
