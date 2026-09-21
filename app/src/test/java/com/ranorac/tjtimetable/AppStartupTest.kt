package com.ranorac.tjtimetable

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ranorac.tjtimetable.notify.ReminderScheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Boots the app the way the launcher does.
 *
 * Every other render test composes a single screen in isolation, which means the startup path
 * was never executed end to end by any test: `TjApplication.onCreate` (notification channel +
 * re-arming reminders), `AppContainer` construction, `MainActivity.setContent` and the first
 * composition all ran for the first time on a student's phone. "It installs but exits the
 * moment you open it" is exactly the class of failure a single Activity-launch smoke test
 * catches — and one of these two tests is the regression test for that report.
 *
 * Deliberately narrow: it asserts the app starts and reaches a first frame, not what that frame
 * looks like. Anything view-shaped is already covered by the screen render tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppStartupTest {

    private val app: TjApplication
        get() = ApplicationProvider.getApplicationContext<Context>() as TjApplication

    @Test
    fun `the application boots and builds its container`() {
        // onStart of the process. `container` is lazy, so touching each part here is what
        // actually constructs it — including the Room database and both DataStores.
        assertNotNull("container", app.container)
        assertNotNull("database", app.container.database)
        assertNotNull("settings store", app.container.settingsStore)
        assertNotNull("credentials store", app.container.credentialsStore)
        assertNotNull("repository", app.container.repository)

        // The one piece of startup work that touches a system service, done in onCreate.
        val manager = app.getSystemService(NotificationManager::class.java)
        assertNotNull("notification service", manager)
        assertNotNull(
            "the reminder channel must exist after startup",
            manager.getNotificationChannel(ReminderScheduler.CHANNEL_ID),
        )
    }

    @Test
    fun `main activity reaches its first frame`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertFalse("activity must not be finishing", activity.isFinishing)
                assertFalse("activity must not be destroyed", activity.isDestroyed)
            }
        } finally {
            scenario.close()
        }
    }
}
