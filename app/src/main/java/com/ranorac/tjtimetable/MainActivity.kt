package com.ranorac.tjtimetable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ranorac.tjtimetable.data.prefs.AppSettings
import com.ranorac.tjtimetable.domain.ClassOccurrence
import com.ranorac.tjtimetable.scrape.TongjiLoginScreen
import com.ranorac.tjtimetable.ui.calendar.AdjustmentScreen
import com.ranorac.tjtimetable.ui.calendar.AdjustmentViewModel
import com.ranorac.tjtimetable.ui.components.AppBottomBar
import com.ranorac.tjtimetable.ui.navigation.NavLauncher
import com.ranorac.tjtimetable.ui.navigation.NavigationScreen
import com.ranorac.tjtimetable.ui.settings.SettingsScreen
import com.ranorac.tjtimetable.ui.settings.SettingsViewModel
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors
import com.ranorac.tjtimetable.ui.theme.TJTimetableTheme
import com.ranorac.tjtimetable.ui.theme.ThemeMode
import com.ranorac.tjtimetable.ui.timetable.CourseDetailSheet
import com.ranorac.tjtimetable.ui.timetable.TimetableScreen
import com.ranorac.tjtimetable.ui.timetable.TimetableViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fully transparent system bars, with no contrast scrim.
        //
        // The plain `enableEdgeToEdge()` applies `SystemBarStyle.auto` with its default
        // scrims — a translucent #1b1b1b overlay in dark mode — which draws a dark band
        // across the status-bar strip and reads as a black title bar that will not go away.
        // Passing TRANSPARENT for both scrims lets the app's own background show through, so
        // the strip is simply the canvas colour.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        val container = (application as TjApplication).container

        setContent {
            val settings by container.settingsStore.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            TJTimetableTheme(themeMode = settings.themeMode.toThemeMode()) {
                AppRoot(container)
            }
        }
    }
}

/**
 * Three destinations, no navigation library.
 *
 * A timetable app has few enough screens that a bottom bar plus a saved int is less
 * machinery than a NavHost, and it survives rotation just the same.
 */
@Composable
private fun AppRoot(container: AppContainer) {
    val gh = LocalGitHubColors.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Hoisted to this level because the login browser is a full-screen route that
    // must report its captured response back into the settings ViewModel.
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            container.appContext,
            container.repository,
            container.settingsStore,
            container.reminderCoordinator,
        ),
    )
    var showLogin by rememberSaveable { mutableStateOf(false) }

    if (showLogin) {
        // Back leaves the login browser and returns to the app instead of falling
        // through to the activity and killing the whole process. Registered only on
        // this route, so nothing intercepts back once it is closed.
        BackHandler { showLogin = false }
        TongjiLoginScreen(
            onCaptured = { url, body ->
                settingsViewModel.importCaptured(url, body)
                showLogin = false
            },
            onCancel = { showLogin = false },
            // The paste route lives on the settings screen, so just go back to it.
            onUsePasteInstead = { showLogin = false },
        )
        return
    }

    Scaffold(
        containerColor = gh.canvasDefault,
        // Insets are NOT consumed here, so each screen applies the ones it needs. The default
        // (systemBars) would swallow the status-bar inset and hand it over as padding, and
        // because only the bottom padding was used, the top one was silently discarded — the
        // content then drew underneath the status bar, and each screen's own
        // statusBarsPadding() resolved to zero because the inset was already gone.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { AppBottomBar(selected = tab, onSelect = { tab = it }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // Paging between the three destinations slides the way the bottom bar's order
            // implies: 课表 → 调休 → 设置 moves left, back moves right. Without it the pages
            // simply blinked into place, which made the app feel like three separate
            // applications sharing a bar.
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val forward = targetState > initialState
                    val distance = if (forward) 1 else -1
                    (slideInHorizontally(tween(220)) { w -> distance * w / 4 } + fadeIn(tween(180)))
                        .togetherWith(
                            slideOutHorizontally(tween(220)) { w -> -distance * w / 4 } +
                                fadeOut(tween(140)),
                        )
                },
                label = "tab",
            ) { current ->
                when (current) {
                    0 -> TimetableRoute(container, onOpenLogin = { showLogin = true })
                    1 -> AdjustmentRoute(container)
                    2 -> NavigationRoute()
                    else -> SettingsRoute(
                        container = container,
                        viewModel = settingsViewModel,
                        onOpenLogin = { showLogin = true },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ routes

@Composable
private fun TimetableRoute(container: AppContainer, onOpenLogin: () -> Unit) {
    val viewModel: TimetableViewModel = viewModel(
        factory = TimetableViewModel.Factory(container.repository, container.settingsStore),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The tapped occurrence is held as a snapshot, but the sheet always renders the LIVE course
    // looked up by id — otherwise a colour or note change would immediately be overwritten by
    // stale data in the snapshot.
    var selected by remember { mutableStateOf<ClassOccurrence?>(null) }

    TimetableScreen(
        state = state,
        onStepWeek = viewModel::stepWeek,
        onJumpToToday = viewModel::jumpToToday,
        // Straight into the 教务 login browser. This is the only import route a student can
        // actually use, so the timetable page must not send them to Settings first.
        onImport = onOpenLogin,
        onConsumeMessage = viewModel::consumeMessage,
        onCourseClick = { selected = it },
    )

    val timetable = state.timetable
    val course = selected?.let { occurrence -> timetable?.course(occurrence.course.id) }
    // A re-import can drop the course the sheet is showing. Clear the selection rather
    // than leaving `selected` pointing at something that no longer resolves — otherwise
    // a later import reusing the same id would silently reopen a stale sheet.
    // Both are checked explicitly: `course` is derived through a safe call, so the
    // compiler cannot infer that `timetable` is non-null from it.
    LaunchedEffect(selected, timetable, course) {
        if (selected != null && timetable != null && course == null) selected = null
    }

    // Back closes the sheet, and it is registered HERE rather than inside the sheet on purpose.
    //
    // `CourseDetailSheet` renders into a `ModalBottomSheet`, which is a **dialog window** with
    // its own lifecycle owner. A `BackHandler` installed from the sheet's content therefore
    // registers against that dialog rather than the Activity — and the reported symptom was
    // exactly a sheet that could be swiped away but never backed out of, with the gesture
    // landing on nobody. This composable, by contrast, belongs to the Activity's own
    // composition, so `MainActivity`'s `OnBackPressedDispatcher` definitely owns it.
    //
    // Disabled while no sheet is open, so back falls through to the system (which finishes the
    // activity) exactly as before.
    val sheetOpen = course != null
    BackHandler(enabled = sheetOpen) { selected = null }

    if (timetable != null && course != null) {
        CourseDetailSheet(
            course = course,
            occurrence = selected,
            sessions = timetable.sessionsOf(course.id),
            onDismiss = { selected = null },
            onSetColor = { viewModel.setCourseColor(course.id, it) },
            // The sheet closes itself through its hide animation when a course is
            // hidden; clearing `selected` here would rip it out of the composition
            // mid-animation and orphan its dialog window.
            onSetHidden = { hidden -> viewModel.setCourseHidden(course.id, hidden, course.name) },
            onSetNote = { viewModel.setCourseNote(course.id, it) },
        )
    }
}

@Composable
private fun AdjustmentRoute(container: AppContainer) {
    val viewModel: AdjustmentViewModel = viewModel(
        factory = AdjustmentViewModel.Factory(container.repository),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AdjustmentScreen(
        state = state,
        onSetNormal = viewModel::setNormal,
        onSetNoClasses = viewModel::setNoClasses,
        onSetFollows = viewModel::setFollows,
        onResetToAuto = viewModel::resetToAuto,
        onRefresh = viewModel::refreshFromSchoolCalendar,
        // The 校历与调休 controls live on this screen (they used to be a settings
        // section), so the notice parser is wired here rather than in Settings.
        onApplyNotice = viewModel::applyNotice,
        onConsumeMessage = viewModel::consumeMessage,
    )
}

/**
 * 导航 has no state of its own — the links are a fixed list — so it needs no ViewModel.
 * The launcher is the only part that touches the platform.
 */
@Composable
private fun NavigationRoute() {
    val context = LocalContext.current
    NavigationScreen(onOpen = { link, mode -> NavLauncher.open(context, link, mode) })
}

@Composable
private fun SettingsRoute(
    container: AppContainer,
    viewModel: SettingsViewModel,
    onOpenLogin: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        state = state,
        onSaveCredentials = viewModel::saveCredentials,
        onImport = viewModel::importNow,
        onRegisterCalendar = viewModel::registerToCalendar,
        onThemeChange = viewModel::setTheme,
        onShowOddEven = viewModel::setShowOddEvenBadge,
        onDimInactive = viewModel::setDimInactive,
        onShowWeekend = viewModel::setShowWeekend,
        onStartOnToday = viewModel::setStartOnToday,
        onRemindersEnabled = viewModel::setRemindersEnabled,
        onReminderLead = viewModel::setReminderLead,
        onImportIcs = viewModel::importIcsFrom,
        onExportIcs = viewModel::exportIcsTo,
        onOpenLogin = onOpenLogin,
        onImportPasted = viewModel::importPastedResponse,
        onSignOut = viewModel::signOut,
        onOpenCalendarPicker = viewModel::openCalendarPicker,
        onDismissCalendarPicker = viewModel::dismissCalendarPicker,
        onChooseCalendar = viewModel::chooseCalendar,
        onCalendarScope = viewModel::setCalendarScope,
        onRemoveFromCalendar = viewModel::removeFromCalendar,
        onConsumeMessage = viewModel::consumeMessage,
    )
}

private fun String.toThemeMode(): ThemeMode = when (this) {
    "light" -> ThemeMode.LIGHT
    "dark" -> ThemeMode.DARK
    else -> ThemeMode.SYSTEM
}
