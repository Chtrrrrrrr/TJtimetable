package com.ranorac.tjtimetable

import android.app.Application

/**
 * Application entry point.
 *
 * Intentionally dependency-injection-framework-free: the app is small enough
 * that a hand-written [AppContainer] is clearer than Hilt, and it keeps the
 * method count and build time down.
 */
class TjApplication : Application() {

    /** Lazily-built service locator, initialised on first use. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Create the channel up front so the settings screen can link to it, and
        // arm the next reminder — a process restart must not silently drop it.
        com.ranorac.tjtimetable.notify.ReminderScheduler.ensureChannel(this)
        container.refreshReminders()
    }

    companion object {
        lateinit var instance: TjApplication
            private set
    }
}
