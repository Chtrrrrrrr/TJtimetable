package com.ranorac.tjtimetable.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ranorac.tjtimetable.calendar.DeviceCalendar
import com.ranorac.tjtimetable.notify.ReminderScheduler
import com.ranorac.tjtimetable.ui.components.GitHubCard
import com.ranorac.tjtimetable.ui.components.GitHubDivider
import com.ranorac.tjtimetable.ui.components.GitHubPrimaryButton
import com.ranorac.tjtimetable.ui.components.GitHubSecondaryButton
import com.ranorac.tjtimetable.ui.components.GitHubTextField
import com.ranorac.tjtimetable.ui.components.MutedText
import com.ranorac.tjtimetable.ui.components.PageHeader
import com.ranorac.tjtimetable.ui.components.SCREEN_HORIZONTAL_PADDING
import com.ranorac.tjtimetable.ui.components.SectionHeader
import com.ranorac.tjtimetable.ui.components.SegmentedControl
import com.ranorac.tjtimetable.ui.components.SelectableRow
import com.ranorac.tjtimetable.ui.components.SettingRow
import com.ranorac.tjtimetable.ui.components.SnackbarMessageEffect
import com.ranorac.tjtimetable.ui.components.VGap
import com.ranorac.tjtimetable.ui.components.pageContentWidth
import com.ranorac.tjtimetable.ui.theme.LocalGitHubColors

private val THEME_OPTIONS = listOf("system", "light", "dark")
private val LEAD_OPTIONS = listOf(0, 5, 10, 15, 30)

/** `"week"` first: it is the default, and the one the button does when untouched. */
private val CALENDAR_SCOPE_OPTIONS = listOf("week", "term")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSaveCredentials: (String, String, String, String) -> Unit,
    onImport: () -> Unit,
    onRegisterCalendar: () -> Unit,
    onThemeChange: (String) -> Unit,
    onShowOddEven: (Boolean) -> Unit,
    onDimInactive: (Boolean) -> Unit,
    onShowWeekend: (Boolean) -> Unit,
    onStartOnToday: (Boolean) -> Unit,
    onRemindersEnabled: (Boolean) -> Unit,
    onReminderLead: (Int) -> Unit,
    onImportIcs: (android.net.Uri) -> Unit,
    onExportIcs: (android.net.Uri) -> Unit,
    onOpenLogin: () -> Unit,
    onImportPasted: (String) -> Unit,
    onSignOut: () -> Unit,
    onOpenCalendarPicker: () -> Unit,
    onDismissCalendarPicker: () -> Unit,
    onChooseCalendar: (DeviceCalendar) -> Unit,
    onCalendarScope: (String) -> Unit,
    onRemoveFromCalendar: () -> Unit,
    onConsumeMessage: () -> Unit,
) {
    val gh = LocalGitHubColors.current
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    SnackbarMessageEffect(
        message = state.message,
        host = snackbar,
        onConsumed = onConsumeMessage,
    )

    // Storage Access Framework: the student picks where the file goes (and where
    // it comes from), so the app never needs a storage permission.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImportIcs(uri) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri -> if (uri != null) onExportIcs(uri) }

    // Reminders are useless without POST_NOTIFICATIONS, so the switch reflects
    // whether the OS will actually deliver rather than just the stored preference.
    var notificationsAllowed by remember {
        mutableStateOf(ReminderScheduler.canNotify(context))
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = granted && ReminderScheduler.canNotify(context)
        onRemindersEnabled(granted)
    }

    // Seeded once from storage, then owned by the text fields: re-seeding on
    // every emission would fight the student mid-typing.
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var pastedResponse by remember { mutableStateOf("") }
    var seeded by remember { mutableStateOf(false) }

    // Wait for a *loaded* value rather than an empty one. `state.credentials` is null
    // until DataStore has produced its first emission, and the screen must not seed from
    // the placeholder: doing so latches empty strings, shows blank fields over credentials
    // that were saved, and lets 保存 overwrite them with "". Waiting also means the fields
    // appear once, already filled, instead of flashing empty and then filling in.
    LaunchedEffect(state.credentials) {
        val loaded = state.credentials ?: return@LaunchedEffect
        if (!seeded) {
            clientId = loaded.clientId
            clientSecret = loaded.clientSecret
            userId = loaded.userId
            userName = loaded.userName.orEmpty()
            seeded = true
        }
    }

    // Calendar read/write is requested at the moment the student presses a calendar
    // button, and the action they pressed runs only after it is granted — asking for
    // WRITE_CALENDAR on app start would be both unexplained and easy to refuse.
    var pendingCalendarAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val action = pendingCalendarAction
        pendingCalendarAction = null
        if (granted.values.all { it }) action?.invoke()
    }
    val withCalendarPermission: (() -> Unit) -> Unit = { action ->
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingCalendarAction = action
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                ),
            )
        }
    }

    Scaffold(
        containerColor = gh.canvasDefault,
        // The bottom bar lives in MainActivity's Scaffold, which already accounts for the
        // navigation-bar inset, and PageHeader applies the status-bar inset itself. Leaving
        // Scaffold's default `systemBars` insets here would therefore add the navigation
        // bar's height a SECOND time, as a dead band between the content and the bar — and
        // only on this page and 调休, so the three pages visibly disagreed.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // The shared header: 设置 / 调休与校历 / 第 N 周 all sit at the same height,
            // baseline and 16dp inset, so paging between them no longer shifts the title.
            // The subtitle is here for the same reason as on the other two pages — a title
            // alone made 设置 the odd one out.
            PageHeader(title = "设置", subtitle = "功能与配置")
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .pageContentWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = SCREEN_HORIZONTAL_PADDING),
            ) {
                VGap()

                // ------------------------------------------------- 从教务系统导入
                //
                // Leads the screen because it is the primary path: almost every student can
                // ONLY use this one. The open platform below issues credentials to
                // institutions rather than individuals.
                SectionHeader("从教务系统导入")
                VGap()
                GitHubCard {
                    MutedText(
                        "内置浏览器会打开真实的教务页面，你在页面里正常登录（统一身份认证、验证码都由学校页面处理），" +
                            "然后进入「我的课表」，按「读取课表」。应用只是重放页面自己发出的那条请求，" +
                            "全程不接触你的口令。",
                    )
                    VGap()
                    GitHubPrimaryButton(
                        text = "打开教务系统登录",
                        enabled = !state.busy,
                        onClick = onOpenLogin,
                    )
                    VGap(2)
                    GitHubDivider()
                    VGap()
                    MutedText(
                        "兜底：若内置浏览器读不到，可在电脑浏览器登录 1.tongji.edu.cn → 课表页 → F12 → Network → " +
                            "找到 findStudentTimetab 那条请求 → Copy response，粘贴到下面。",
                    )
                    VGap()
                    GitHubTextField(
                        label = "粘贴课表接口的响应体（JSON）",
                        value = pastedResponse,
                        onValueChange = { pastedResponse = it },
                        singleLine = false,
                        minLines = 3,
                    )
                    VGap()
                    GitHubSecondaryButton(
                        text = "解析并导入",
                        enabled = pastedResponse.isNotBlank() && !state.busy,
                        onClick = {
                            onImportPasted(pastedResponse)
                            pastedResponse = ""
                        },
                    )
                }

                VGap(2)

                // ------------------------------------------------------ 日历注册
                SectionHeader("日历提醒")
                VGap()
                GitHubCard {
                    MutedText(
                        "把课表写入系统日历，即可用手机自带的日历应用查看与提醒。" +
                            "单双周会以「每两周一次」的重复规则表达。",
                    )
                    VGap()
                    // 目标日历. An app is not allowed to create a calendar (only sync adapters
                    // may write account_name), so the student names one of the device's own.
                    SettingRow(
                        "目标日历",
                        description = state.settings.calendarName
                            ?: "还没有选择。请挑一个已有的日历，课表会写进去",
                    ) {
                        GitHubSecondaryButton(
                            text = "选择日历",
                            enabled = !state.busy,
                            onClick = { withCalendarPermission(onOpenCalendarPicker) },
                        )
                    }
                    VGap()
                    GitHubPrimaryButton(
                        text = "注册到系统日历",
                        enabled = !state.busy,
                        onClick = {
                            withCalendarPermission {
                                if (state.settings.calendarId == null) onOpenCalendarPicker()
                                else onRegisterCalendar()
                            }
                        },
                    )
                    if (state.settings.calendarId == null) {
                        VGap()
                        MutedText("第一次注册需要先选一个目标日历；之后再注册会直接覆盖上一次写入的课表。")
                    }
                    VGap()
                    GitHubDivider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text("写入范围", style = MaterialTheme.typography.bodyLarge, color = gh.fgDefault)
                        VGap()
                        SegmentedControl(
                            options = CALENDAR_SCOPE_OPTIONS,
                            selected = state.settings.calendarScope,
                            onSelect = onCalendarScope,
                            label = { if (it == "term") "整学期" else "仅本周" },
                        )
                        VGap()
                        MutedText(
                            if (state.settings.calendarScope == "term") {
                                "整学期：每门课写成一条每周重复的日程，停课周以排除日期标出。" +
                                    "日程较多，适合想长期用系统日历查看的情况。"
                            } else {
                                "仅本周：只把这一周的课写成独立日程（不带重复规则），" +
                                    "不会让整个学期的课堆进你的日历。下周需要再点一次。"
                            },
                        )
                    }
                    VGap()
                    // The way back out. Re-registering already replaces its own rows, but a
                    // student who simply wants the classes gone had no button for it — only
                    // twenty hand-deletions in the calendar app.
                    GitHubSecondaryButton(
                        text = "从日历中移除课表",
                        enabled = !state.busy,
                        onClick = { withCalendarPermission(onRemoveFromCalendar) },
                    )
                    VGap()
                    MutedText("移除只会删掉本应用写入的日程，你自己在日历里建的事件不会被碰到。")
                    VGap()
                    GitHubDivider()
                    VGap()
                    SettingRow(
                        "上课提醒",
                        description = if (notificationsAllowed) {
                            "在每节课开始前发送通知"
                        } else {
                            "通知权限未开启，提醒不会送达"
                        },
                    ) {
                        Switch(
                            checked = state.settings.remindersEnabled && notificationsAllowed,
                            onCheckedChange = { want ->
                                when {
                                    !want -> onRemindersEnabled(false)
                                    // Ask at the moment the student turns it on, which is
                                    // when the request is self-explanatory.
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) != PackageManager.PERMISSION_GRANTED ->
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else -> {
                                        notificationsAllowed = ReminderScheduler.canNotify(context)
                                        onRemindersEnabled(true)
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = gh.accentFg),
                        )
                    }
                    GitHubDivider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text("提前提醒时间", style = MaterialTheme.typography.bodyLarge, color = gh.fgDefault)
                        VGap()
                        SegmentedControl(
                            options = LEAD_OPTIONS,
                            selected = state.settings.reminderLeadMinutes,
                            onSelect = onReminderLead,
                            label = { if (it == 0) "准点" else "$it 分" },
                        )
                    }
                }

                VGap(2)

                // ---------------------------------------------------------- 外观
                SectionHeader("外观")
                VGap()
                GitHubCard {
                    Column(Modifier.fillMaxWidth()) {
                        Text("主题", style = MaterialTheme.typography.bodyLarge, color = gh.fgDefault)
                        VGap()
                        SegmentedControl(
                            options = THEME_OPTIONS,
                            selected = state.settings.themeMode,
                            onSelect = onThemeChange,
                            label = { when (it) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" } },
                        )
                    }
                    VGap()
                    GitHubDivider()
                    SettingRow("显示单双周标记", description = "在课程卡片上显示「单周」「双周」") {
                        Switch(
                            checked = state.settings.showOddEvenBadge,
                            onCheckedChange = onShowOddEven,
                            colors = SwitchDefaults.colors(checkedTrackColor = gh.accentFg),
                        )
                    }
                    GitHubDivider()
                    SettingRow("虚化非本周课程", description = "单双周课程在不上课的周次以浅色显示") {
                        Switch(
                            checked = state.settings.dimInactiveCourses,
                            onCheckedChange = onDimInactive,
                            colors = SwitchDefaults.colors(checkedTrackColor = gh.accentFg),
                        )
                    }
                    GitHubDivider()
                    SettingRow("显示周末", description = "关闭后只显示周一至周五") {
                        Switch(
                            checked = state.settings.showWeekend,
                            onCheckedChange = onShowWeekend,
                            colors = SwitchDefaults.colors(checkedTrackColor = gh.accentFg),
                        )
                    }
                    GitHubDivider()
                    SettingRow("从今天开始显示", description = "以今天作为课表第一列；今天正好是周一或周末时不生效") {
                        Switch(
                            checked = state.settings.startOnToday,
                            onCheckedChange = onStartOnToday,
                            colors = SwitchDefaults.colors(checkedTrackColor = gh.accentFg),
                        )
                    }
                }

                VGap(2)

                // ---------------------------------------------------- 日历文件
                SectionHeader("课表文件（.ics）")
                VGap()
                GitHubCard {
                    MutedText(
                        "同济开放平台暂不向个人开发者开放，学生个人拿不到 client_id，" +
                            "所以这里提供一条不需要任何凭据的路径：把课表导出为标准日历文件，" +
                            "或从日历文件导入课表。导出的文件也能直接导入手机自带日历。",
                    )
                    VGap()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GitHubPrimaryButton(
                            text = "从 .ics 导入",
                            enabled = !state.busy,
                            onClick = {
                                importLauncher.launch(
                                    arrayOf("text/calendar", "text/plain", "application/octet-stream"),
                                )
                            },
                        )
                        GitHubSecondaryButton(
                            text = "导出为 .ics",
                            enabled = !state.busy,
                            onClick = { exportLauncher.launch("TJ课表.ics") },
                        )
                    }
                    VGap()
                    MutedText(
                        "导入会替换当前课表；原课表仍留在本机数据库中而非被删除，" +
                            "必要时可重新导入恢复。单双周会以「每两周一次」的重复规则写入文件。" +
                            "校历与调休安排会作为排除日期一并导出。",
                    )
                }

                VGap(2)

                // ---------------------------------------------------- 开放平台账号
                //
                // Second to last. The platform's own 学生申请指南 states it is
                // 暂不向个人开发者开放, so for virtually every student this section is
                // informational: it stays so the capability is discoverable, but it must not
                // stand between the student and the import route that actually works.
                SectionHeader("开放平台账号（需机构授权）")
                VGap()
                GitHubCard {
                    MutedText(
                        "同济开放平台需要应用的 client_id。请勿使用他人凭据；密钥只保存在本机，" +
                            "且已排除在云备份之外。",
                    )
                    VGap()
                    GitHubTextField("client_id", clientId, { clientId = it })
                    VGap()
                    GitHubTextField(
                        "client_secret（客户端模式需要）",
                        clientSecret,
                        { clientSecret = it },
                        secret = true,
                    )
                    VGap()
                    GitHubTextField("学号 userId", userId, { userId = it })
                    VGap()
                    GitHubTextField("姓名（可选）", userName, { userName = it })
                    VGap()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GitHubPrimaryButton(
                            text = if (state.busy) "处理中…" else "保存",
                            enabled = !state.busy,
                            onClick = { onSaveCredentials(clientId, clientSecret, userId, userName) },
                        )
                        GitHubSecondaryButton(
                            text = "从教务导入",
                            enabled = !state.busy,
                            onClick = onImport,
                        )
                    }
                    // Nothing to say until the stored credentials have been read — showing
                    // "未登录" for the instant before DataStore answers would be a lie.
                    val credentials = state.credentials
                    if (credentials != null && credentials.isTokenFresh()) {
                        VGap()
                        MutedText("登录状态有效" + (credentials.userName?.let { "（$it）" } ?: ""))
                    }
                }

                VGap(2)

                // ------------------------------------------------------ 关于/退出
                SectionHeader("关于")
                VGap()
                GitHubCard {
                    MutedText("TJ课表 · 作者 RanoraC")
                    VGap()
                    MutedText("数据来源：同济大学开放平台（api.tongji.edu.cn）。本应用与学校官方无关。")
                    VGap()
                    GitHubSecondaryButton(
                        text = "退出登录",
                        // Same reason: while the credentials are still loading, neither the
                        // existence nor the absence of a token is known yet.
                        enabled = state.credentials?.let { it.hasToken || it.hasClient } == true,
                        onClick = onSignOut,
                    )
                    VGap()
                    MutedText("退出后本地课表仍会保留，只是无法再从教务系统拉取更新。")
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (state.pickingCalendar) {
        CalendarPickerDialog(
            calendars = state.calendarOptions,
            selectedId = state.settings.calendarId,
            onDismiss = onDismissCalendarPicker,
            onPick = onChooseCalendar,
        )
    }
}

/**
 * Lists the device's calendars so the student can name the one the timetable goes into.
 *
 * This dialog is the fix for "only sync adapters may write to account_name": instead of the
 * app trying (and failing) to create a calendar of its own, the student picks one that
 * already exists. The list is scrollable because a phone with a work profile can easily
 * carry a dozen calendars.
 */
@Composable
private fun CalendarPickerDialog(
    calendars: List<DeviceCalendar>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onPick: (DeviceCalendar) -> Unit,
) {
    val gh = LocalGitHubColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = gh.canvasDefault,
        titleContentColor = gh.fgDefault,
        textContentColor = gh.fgMuted,
        title = { Text("注册到哪个日历") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MutedText(
                    "课表会写入所选日历。重新注册时只删除本应用写入的日程，" +
                        "你自己建的事件不会被碰到。",
                )
                VGap()
                calendars.forEach { calendar ->
                    SelectableRow(
                        text = calendar.displayName,
                        description = calendar.accountLabel?.let { "账户：$it" } ?: "本地日历",
                        selected = calendar.id == selectedId,
                        onClick = { onPick(calendar) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = gh.accentFg)
            }
        },
    )
}
