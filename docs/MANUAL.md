# TJ课表 · 技术手册

> 本文是 **TJ课表（TJtimetable）** 的技术手册，由原 README 的技术章节完整迁移而来。
> 项目概览、快速开始与许可信息见 [`../README.md`](../README.md)。

---

## 一、当前状态

| 模块 | 状态 |
|---|---|
| 周次解析（单双周 / 多段周次 / 各类书写格式） | ✅ 完成，**580 项单元测试全部通过** |
| 调休串休（校历类型 + 推测补课 + 公告文本解析 + **逐日编辑界面**） | ✅ 完成 |
| 学期日历 ↔ 实际日期映射 | ✅ 完成 |
| 同济开放平台 API 客户端（两种 OAuth2 模式） | ✅ 完成 |
| 教务导入（两种课表接口 + 字段映射 + 重导保留自定义） | ✅ 完成 |
| Room 持久化（课程 / 时段 / 学期 / 调休） | ✅ 完成 |
| 课表网格 UI（周切换整页滑动、重叠分列、非本周课程虚化、**行高/栏宽按屏幕比例**） | ✅ 完成 |
| GitHub 风格设计系统 | ✅ 完成 |
| 底栏（44dp 单行：当前页显示名字，其他页显示图标） | ✅ 完成 |
| 四页切换过场动画 + 四页共用页头 | ✅ 完成 |
| 设置界面（导入、日历提醒、外观、.ics、开放平台账号、关于） | ✅ 完成 |
| 调休与校历页（刷新校历、粘贴公告、逐日改正、来源标记、一键恢复自动） | ✅ 完成 |
| 导航页（常用网址；默认方式 + 各支持方式各自入口，微信 / 企业微信 / 外部 App 唤起） | ✅ 完成 |
| 课程详情底栏（改配色 / 记备注 / 隐藏课程） | ✅ 完成 |
| 桌面小组件（Glance，2x2 / 4x2 自适应，圆角背景随系统） | ✅ 完成 |
| 系统日历注册（**选已有日历** + 默认**只写本周**，可一键**从日历移除**） | ✅ 完成 |
| 「从今天开始显示」课表列排序 | ✅ 完成 |
| 上课提醒（应用内通知，WorkManager） | ✅ 完成 |
| 日历文件（.ics）导入导出（**无需凭据**） | ✅ 完成，53 项测试；含单双周与调休往返 |
| 教务抓取（内置浏览器旁观 + 响应体解析，**不接触口令**） | ✅ 完成 |
| 课表数据一致性（界面 / 小组件 / 系统日历 / 提醒 同源） | ✅ 完成，由 `CalendarSyncLogicTest` 双向固定 |

已实测可用（v2.3.6）：

```
.\tools\dev\build.ps1                          → BUILD SUCCESSFUL
.tools\dev\build.ps1 :app:testDebugUnitTest   → 580 tests, 0 failures, 0 errors, 0 skipped
.\tools\dev\build.ps1 :app:lintDebug           → 0 errors, 1 warning（见下）
.\tools\dev\build.ps1 :app:assembleRelease     → BUILD SUCCESSFUL（R8 混淆通过）
```

> lint 剩下的唯一一条告警是 `mipmap-anydpi-v26`「v26 限定符多余，minSdk 已是 26」。
> 试过合并进 `mipmap-anydpi`：AAPT 直接报 `resource mipmap/ic_launcher not found`，
> 因为旧式 `anydpi` 目录不是合法的自适应图标容器。这是工具的建议与打包器的要求相冲突，
> 保留告警比隐藏它更诚实。

产物：

| 变体 | 大小 | 说明 |
|---|---|---|
| debug | 19.49 MiB | 包名 `com.ranorac.tjtimetable.debug`，minSdk 26 / targetSdk 35 |
| release（已签名） | 2.51 MiB | `app-release.apk`，v2 + v3 签名方案，R8 压缩约 87% |
| release（未签名） | 2.42 MiB | `app-release-unsigned.apk`，仅在缺少 `keystore/keystore.properties` 时产出 |

release 构建通过值得注意：`mapping.txt` 中 API DTO 未被重命名，说明
`proguard-rules.pro` 的 kotlinx.serialization 保留规则正确——这类规则写错通常只在
release 崩溃时才暴露。

---

## 二、同济开放平台 API（实测整理）

基址 `https://api.tongji.edu.cn`。响应统一为 `{ code, msg, data }`，`code == "A00000"` 为成功。

### 授权

| 模式 | 用途 | 请求 |
|---|---|---|
| 客户端模式 | 已注册应用 | `POST /v1/token`，`grant_type=client_credentials` + `client_id` + `client_secret` |
| 授权码模式 | 用户本人授权 | 跳转 Keycloak，携带 `kc_idp_hint=tjiam`（**必填**，否则登录页不对） |

- Token 有效期 7200 秒，放在 `Authorization: Bearer <token>`；401 时应重新申请。
- 客户端模式允许同时申请多个 token。

授权码模式跳转地址：
```
https://api.tongji.edu.cn/keycloak/realms/OpenPlatform/protocol/openid-connect/auth
  ?client_id=...&redirect_uri=...&response_type=code&scope=...&state=...&kc_idp_hint=tjiam
```

换取 token（两种模式都用同一个端点）：
```
POST https://api.tongji.edu.cn/v1/token
  授权码模式：grant_type=authorization_code & client_id & client_secret & code & redirect_uri
```

> ⚠️ **重要限制：平台不向个人开发者开放。**
> 「学生申请指南」原文：
> 「学校数据开放平台服务主要用户范围为全校师生正式使用的业务系统/应用……
> 出于对个人数据的保护和服务有效性的控制，**暂不向个人开发者开放**。
> 学生组织自建应用并希望对接数据开放平台的，请指导教师以**教职工申请**流程进行申请。」
>
> 两点推论，直接决定本项目的能力边界：
> 1. **`client_secret` 在两种模式下都是必填的**（授权码模式也不例外），
>    所以「让学生自己授权」并不能绕开注册。
>    文档里公开的那对 `xxb-example-api` 凭据只能访问 `fake_api` 测试接口，拿不到真实数据。
> 2. 因此**单人学生开发者拿不到可用凭据**。官方 API 这条路径只有在
>    「有指导教师以教职工身份申请」的前提下才真实可用。
>
> 这正是本项目另外提供**日历文件（.ics）导入导出**的原因：那条路径**不需要任何凭据**，
> 详见下节。校历与学期信息接口本身无需授权，所以调休/校历功能在任何情况下都可用。

### 课表文件（.ics）

因为官方 API 对个人开发者关闭，`.ics` 是本项目**唯一无需任何凭据**的课表进出通道，
并且它正好是 同济 社区里既有的互通格式——例如
[KingfuChan/Tongji-CourseTable](https://github.com/KingfuChan/Tongji-CourseTable)
（「获取当前课程表并编写为 iCalendar 文件」）就是把课表转成 `.ics` 的项目。
因此实际可行的用法是：

```
教务网站 ──(任何能生成 ics 的工具)──> .ics ──> TJ课表 ──> 小组件 / 系统日历提醒
```

- **导出**：当前课表 → 标准 iCalendar 文档，可再导入手机自带日历。
- **导入**：任意 `.ics` → 课表，按 `SUMMARY` 分组为课程，`BYDAY` 还原星期，
  起止时间按内置作息表就近匹配回节次，`RRULE` 的 `INTERVAL=2` 还原为单双周，
  `EXDATE` 还原为停课周。
- 单双周在文件中表达为 `FREQ=WEEKLY;INTERVAL=2`（单周）/ 起始于偶数周（双周），
  与「系统日历注册」用的是同一套规则，因此导出的文件在两个方向上都自洽。
- 导入是**替换**语义，但旧学期只是不再「当前」，并未从数据库中删除，误操作可恢复。

### 课表

| 接口 | 说明 |
|---|---|
| `GET /v1/rt/onetongji/student_timetable` | **实时**，入参 `userId`（必填）、`calendarId`（学期编号，空为当前学期） |
| `GET /v2/dc/teaching_info/student_timetable` | **批量**，入参 `userId`、`sinceUpdateTime`、`sinceId`（游标，每页 1000 条） |

实时接口返回每门课一条记录，其 `timeTableList[]` 内每个时段同时给出：

- `weeks: [1,2,…,17]` —— **已解析好的周次数组**
- `weekNum: "[1-17]"` / `"[1-17单]"` —— 原始字符串
- `dayOfWeek`、`timeStart`、`timeEnd`、`roomIdI18n`、`campusI18n`

**这是单双周最可靠的来源**：`v1` 的 `weeks` 数组已把单双周展开成显式周次；
`v2` 的 `week` 则是逗号分隔字符串（如 `"1,2,3,…,17"`），单双周表现为其中的奇/偶子集。

> 注意：`v2` 的 `delInd == "D"` 表示该行已因退课或教学班关闭而作废，导入时必须过滤。

### 校历与调休

| 接口 | 授权 | 说明 |
|---|---|---|
| `GET /v1/rt/teaching_info/semester` | **无需授权** | 学年学期信息（`beginDay`/`endDay`/`semesterName`） |
| `GET /v1/rt/onetongji/calendar` | **无需授权** | 校历，入参 `fromDate`/`endDate`，返回 `{"yyyy-MM-dd": "类型"}` |
| `GET /v1/rt/onetongji/school_calendar_current_term_calendar` | 需授权 | 当前学期编号、`beginDay`(ms)、`weekNum`、当前周 |

校历类型码：

| 码 | 含义 |
|---|---|
| 1 | 节假日 |
| 2 | 工作日（**含调休补课日**） |
| 3 | 周末 |
| 4 | 寒假 |
| 5 | 暑期 |

- 仅支持 2023-01-01 之后；区间无数据时返回 `A06500`（应用视为「暂无信息」而非错误）。
- 两个接口都**无需授权**，因此未登录也能显示校历与调休。

---

## 三、单双周与调休串休的实现

这是本项目与普通课表应用的主要差异点。

### 单双周

`domain/WeekPattern.kt` 把周次表示为 `Long` 位掩码（第 1 周为 bit 0），
因此并集/交集是单条指令，Room 也能用一列 `Long` 存下整个周次集合。

`WeekPattern.parse` 覆盖同济实际出现的全部书写形式：

```
"1,2,3,4,17"        v2 逗号列表
"[1-17]"            实时接口 weekNum
"[1-17单]"          单周
"[2-16双]"          双周
"1-17(单)" "1-17单周" "奇数周1-17"
"1-8,10-16"         多段区间
"1-8单,10-16双"     混合奇偶（按 token 分别判定）
"第1至17周" "1~17" "1—17"  全角/破折号/中文
```

解析**永不抛异常**：无法识别的字段返回空周次，并保留原始字符串到 `rawWeeks`，
保证一条脏数据不会让整次导入失败。

> 关键设计：奇偶标记**按逗号分段分别判定**。早期实现用单个全局标记，
> 导致 `"1-8单,10-16双"` 中的「双」覆盖了前面的「单」，把单周静默变成双周
> ——这是由单元测试发现并修复的真实缺陷。

周次显示：`parityLabel` 给出「单周」/「双周」，且**单周次不会被误标为单周**
（仅一周不构成交替）。课表中非本周课程以 32% 透明度虚化显示，而非直接消失。

### 调休串休

关键在于把问题**反转**来问：不是「周三有哪些课」，而是
**「这一天实际执行星期几的课表」**。

```
1. noClasses          → 全天停课
2. followsWeekday     → 执行该星期的课表（补课日）
3. kind 为节假日/寒暑假 → 全天停课
4. 否则                → 执行当天自身的星期（因此周末课程能正常显示）
```

这一反转同时解决三件事：节假日课自然消失、补课日课自然出现、真实周末课不受影响。

调休数据有三种来源，全部落库（因此**离线也能正确显示**）：

| 来源 | 生命周期 |
|---|---|
| `API` | 来自校历类型码，每次刷新重建 |
| `INFERRED` | 本地推测补课日，标注为「推测」，每次刷新重建 |
| `MANUAL` | 学生手动设置，**永不被覆盖** |

推测补课的局限已在代码中明确标注：官方「补哪天」的口径只存在于教务处通知的文字里，
API 不提供。因此应用**不假装知道**，而是标注为「推测」并提供改正入口。

`parseNotice` 可直接解析教务处通知原文，例如：

```
"5月6日（周六）补5月3日（周三）的课"   → 5/6 执行周三课表
"4月23日（星期日）上4月25日（星期二）的课程"
"5月1日至5月5日放假"                  → 5 天节假日
"5月6日（周六）按周三课表上课"
```

> 关键设计：解析时**公告自带的「（周X）」标记优先于按日期推算的星期**。
> 调用方常传入当前年份而通知可能属于别的年份，此时推算结果是错的；
> 早期实现只信日期，会把补课静默挪到错误的星期（同样由单元测试发现并修复）。

### 调休编辑界面

自动判断不可能总是对，所以「调休与校历」页把整个学期逐日列出，点任意一天即可改成：

| 选项 | 写入的 `DayAdjustment` |
|---|---|
| 正常上课（按当天星期） | `followsWeekday = 当天星期`，`noClasses = false`，`MANUAL` |
| 放假 / 停课 | `noClasses = true`，`MANUAL` |
| 按周X课表 | `followsWeekday = X`，`MANUAL` |
| 恢复为校历自动判断 | 删除手动行，再重新推导校历行 |

每行左侧有状态点（绿=正常、橙=补课、灰=停课），右侧标记来源（`校历` / `推测` / `手动`）。
顶部汇总显示教学周数、停课天数、手动天数与推测天数，推测天数 > 0 时会提醒核对。

校历的两个操作（**刷新校历与调休**、**粘贴通知原文**）也在这个页面上，不再放在设置里：
它们改写的正是下面的日行，学生看到一行不对时不必再切到另一个页签。
粘贴区默认**收起**——展开后约 300dp，占掉大半屏，而这一页的主角是下面的逐日列表；
`刷新校历与调休` 始终可见，因为那是不需要读任何东西就会按的动作。

两处语义陷阱已在实现与测试中同时固定：

1. **「正常上课」必须显式写出星期。** `effectiveWeekday` 在「日类型为非教学日**且** `followsWeekday` 为 null」时返回 null，
   所以在节假日上写「正常上课」若只把 `noClasses` 设为 false，**会静默无效**。
   因此编辑界面把当天星期显式写进 `followsWeekday`，用「指名星期」压过校历。
2. **「恢复自动」必须是两步。** 只删除手动行会让该日**没有任何行**，
   而「没有行」的语义是「无信息」，于是回退到当天自身的星期——节假日反而**变成要上课**。
   因此必须先 `clearAdjustment`、再 `refreshAdjustments` 重新生成校历行。
   `AdjustmentScreen` 的这一行为由 `clearing a manual row needs a refresh to restore the calendar default` 测试固定。

### 作息时间

`PeriodSchedule.TONGJI` 内置同济 11 节作息（已核对教务处公开版本）：

```
1  8:00–8:45     2  8:50–9:35    3 10:00–10:45   4 10:50–11:35
5 13:30–14:15    6 14:20–15:05   7 15:30–16:15   8 16:20–17:05
9 18:30–19:15   10 19:20–20:05  11 20:10–20:55
```

作息可整体替换，日历提醒按所配置的作息触发。

---

## 四、工程结构

```
app/src/main/java/com/ranorac/tjtimetable/
├── domain/                     纯 Kotlin，无 Android 依赖，可单元测试
│   ├── WeekPattern.kt          单双周位掩码 + 全部书写形式解析
│   ├── TermCalendar.kt         教学周 ↔ 实际日期
│   ├── PeriodSchedule.kt       作息时间（节次时钟）
│   ├── ScheduleAdjustment.kt   调休串休：日类型、推测、公告解析
│   ├── Course.kt               Course / CourseSession / ClassOccurrence
│   ├── TimetableResolver.kt    Timetable 聚合 + 具体日期的课程展开
│   └── ...
├── data/
│   ├── remote/                 开放平台 DTO、API 客户端、导入映射
│   ├── db/                     Room 实体 / DAO / 数据库 / 映射
│   ├── prefs/                  设置 DataStore、凭据 DataStore（排除备份）
│   └── repo/                   TimetableRepository（唯一数据源）
├── ui/
│   ├── theme/                  GitHub Primer 色板 / 排版 / 形状
│   ├── components/             GitHub 风格通用组件 + PageHeader + 44dp 底栏
│   ├── timetable/              课表网格（高度自适应）+ ViewModel
│   ├── calendar/               调休与校历：刷新 / 粘贴公告 / 逐日编辑
│   ├── navigation/             导航页：站点数据 + 唤起策略（默认 / 微信 / 企微 / 外部 App）
│   └── settings/               导入 / 日历提醒（选日历）/ 外观 / .ics / 关于 / 开放平台账号
├── calendar/                   系统日历注册（写入用户选定的日历 + RRULE 表达单双周）
├── scrape/                     教务抓取：内置浏览器旁观 + 响应体解析（不接触口令）
├── notify/                     应用内上课提醒（WorkManager + 通知）
├── export/                     .ics 导入导出（纯 Kotlin，无需凭据）
└── widget/                     桌面小组件（Glance，今日课程）
```

### 上课提醒（应用内通知）

与「系统日历注册」互补：日历那条路要求学生真的用日历 App，而这些是 TJ课表 自己发的通知。

调度**策略**放在 `domain/ReminderPlanner.kt`（纯逻辑、可单元测试），`notify/` 里只剩管道。
三个刻意的决定：

- **只排一个待办**。WorkManager 只接受「延迟多久」，不接受 cron，所以应用只武装**下一个**提醒，
  每次运行后再武装下一个。这样无论学期多长，队列里始终只有一项，也不会出现长链因 Doze 延迟而失步。
- **每次运行都从数据库重新计划**，而不是相信预先算好的列表——这样导入或改调休后立即生效，
  不必等之前武装的那一项排干。
- **`due()` 用时间窗而不是精确时刻**。WorkManager 会因为 Doze、批处理而晚跑，
  没有时间窗就会**静默漏掉一节课**，而这是提醒功能唯一不能有的失败方式。
  窗口的长度不是随便取的：它必须比「真实的延后量」长，否则等于没有窗口——
  WorkManager 自身的最小间隔就是 15 分钟，所以 5 分钟的窗口只会在 worker 迟到时把课丢掉，
  而丢掉之后**不可恢复**（worker 接着只武装下一个提醒，`plan()` 又永不返回过去的触发点）。
  现在默认 30 分钟，并且以「这节的上课时刻」为硬上限：
  触发点之上最多晚 `min(窗口, 距离上课还有多久)`，课上到一半再弹就不叫提醒了。
  两个上限取更紧的那个，所以「准点」模式下窗口自动收敛到「上课那一刻」为止。

提醒由 `TimetableResolver` 展开而来，因此节假日、补课日与单双周**自动被尊重**：
某天不上课的课**不可能**产生提醒。通知开关同时反映系统通知权限是否真的开启，
避免出现「开关是开的但通知永远不来」这种误导。

### 界面验证（Robolectric + Compose，在 JVM 上真正渲染）

在加入这一层之前，**所有界面只被编译过、从未被执行过**——首屏崩溃这类问题会等到
学生装上手机才暴露。现在用 Robolectric + `createComposeRule` 在主机 JVM 上执行真实
Compose 渲染：`TimetableScreenRenderTest` 11 项、`OtherScreensRenderTest` 12 项、
`SnackbarMessageEffectTest` 2 项，覆盖：

- 课表网格：学期名、周次、**七个星期列**（少一列即失败）、课程块与教室、**授课老师**、
  **单双周徽章**、非本周课程**虚化而非消失**、隐藏周末后周六周日列消失
- **整屏放得下**：在 411×891dp 的手机上把底栏高度也占掉，最后一节（`11`）必须仍在屏幕内
- **左栏三行**：节次 + 开始时间 + 结束时间（`08:00` 与 `08:45` 都要在屏内）
- **只在本周以外出现「回到本周」**，且该控件是返回箭头而非日历图标
- 空状态与导入中状态
- **底栏**：当前页是文字、其他三页是图标（图标带同样的 contentDescription），且高度确实是 44dp
- 设置页：各分区、各操作按钮、**「功能与配置」副标题**、输入框回填、提醒区
  （含 `ActivityResult` 启动器注册，这是最容易只在运行时炸的地方）、**目标日历行**、
  **写入范围（仅本周 / 整学期）**、**从日历中移除课表**、
  **日历选择对话框列出手机里的日历**，以及**断言「校历与调休」已不在这里**
- 调休页：汇总统计、周行、**推测补课日及其描述**（该描述由 `followsWeekday` 推导而非回显输入）、
  **从设置搬来的刷新与粘贴区**（默认收起，点开后才出现输入框）、无学期时的引导
- 课程详情底栏：课程信息、隐藏/恢复，以及 **`occurrence` 为 null 时不崩**
- **一次性提示的注销顺序**：`SnackbarMessageEffectTest` 让 snackbar 永远不消失（正是切页时的状态），
  断言消息已经在那一刻被注销——旧的「先弹再注销」写法会在这里失败

> 调休页的日行列表是 `LazyColumn`，所以涉及靠后内容的断言会先
> `performScrollToNode`——那与学生真实操作一致；未滚动时那些节点根本没被组合出来。

日历选择器的**过滤与排序**（哪些日历能写、哪个排最前、没有账户名时显示什么）是纯函数，
由 9 项普通 JUnit 测试覆盖；「仅本周」到底覆盖哪七天由 `registrationRange` 决定，
另 5 项测试固定（含周首/周末边界与"今天不在学期内"）。两者都不需要设备。

这一层的价值在于它抓的是"能编译但一渲染就炸"的问题；纯 JVM 单元测试永远看不到这一类。

### 桌面小组件

小组件显示**今日课程**，2x2 与 4x2 是同一份代码的两种密度（`SizeMode.Responsive` +
`LocalSize` 决定渲染 1–6 行，超出显示「还有 N 门课」，永不滚动）。

- 每行 = 课程配色左色条 + 课名 + `08:00–09:35 · 教室`，并带「单周/双周」「补课」标记。
- 按当前时间标出**进行中**与**下一节**，底部显示「还剩 N 分钟 / 下一节 13:30 · 课程」。
- 今天没课 → 「今天没有课 🎉」并说出下一教学日的第一节课。
- 未导入 → 可点按的空状态。
- **调休与单双周不重复实现**：课程全部来自 `TimetableResolver.occurrencesForDate`，
  因此节假日、补课日与单双周的行为与主界面**必然一致**。

两处踩坑值得记录：

1. `currentState<Timetable?>()` **会在运行时 ClassCast**——`Timetable` 既不是 `Parcelable`
   也不是 Preferences 支持的值类型，`currentState<T>()` 取到的是 state definition 的值。
   改为把 `@Serializable` 的 `WidgetPayload` 以 JSON 存进 widget preferences，
   顺带获得「杀进程后仍能画出上次内容」的好处。
2. `fillMaxWidth` 在 Glance 的 `Row` 里是 `match_parent` 语义，不是 `weight=1`。
   因此每个 `Row` 中**只能把 `fillMaxWidth` 放在最后一个子项**，否则后面的兄弟会被压成 0 宽。

仓库层通过 `onTimetableChanged` 回调（`AppContainer` 接到 `TimetableWidget.refresh`）
在导入、改调休、改配色后**立即**刷新桌面，否则最多滞后 30 分钟
（`updatePeriodMillis` 的系统最小值）。

### 几处值得说明的设计

- **`domain` 不依赖 Android**，因此周次与调休逻辑可以用普通 JUnit 测试，无需模拟器。
- **`TimetableRepository` 是唯一数据源**：界面、小组件、日历写入都从它取数，
  三者不会对「什么时候上什么课」产生分歧。
- **手势重导不会丢失自定义**：重新导入时按教学班 id 匹配，保留学生改过的配色、备注与隐藏状态。
- **导入失败不清空本地**：服务器异常时保留已有课表，只提示失败。
- **凭据单独存放并排除云备份**（`backup_rules.xml`），避免 token 随备份外泄。
- **不内置 client_secret**：APK 里的密钥等于公开，所以不做「把密钥打进包里」这种自欺的做法。
  凭据由使用者在设置中填写（前提已具备机构授权，见上文限制说明）。
  也正因如此，`.ics` 导入导出被当作**一等公民**而非附属功能。
- **课程详情底栏以课程 id 为键**，而不是持有 `Course` 快照：否则改完配色或备注后，
  底栏会立刻被快照里的旧值覆盖回去。
- **课表列排序放在 `domain`（`weekdayColumns`）**：「从今天开始显示」只重排列、
  不改变一周包含哪些日期；表头与网格共用同一个列表，因此不可能错位。
  放在 domain 也让它能用普通单元测试覆盖（含「今天是被隐藏的周末」这类退化情形）。
- **课表行高不是常量，而是按剩余高度算出来的**。`.height(58.dp)` 让 11 节固定占 638dp，
  在手机上最后一两节被推到折叠线以下，必须滑动才看得全。现在 `WeekPage` 用
  `BoxWithConstraints` 拿到「去掉页头与星期行之后」的真实高度，除以节数得到行高
  （下限 34dp）。**上限也是相对的**：一行最多长到一个日列的 1.5 倍宽，
  所以平板不会因为列变宽而把格子拉成横条、高屏手机也不会留下大片空白。
  下限仍是绝对 34dp——那是"读得出来"的地板，不是位置。
- **左栏宽度按页面宽度的 13% 算**（夹在 44–76dp 之间），同样是为了比例适配：
  写死 46dp 在手机上正好，在平板上就是挨着宽列的窄缝。
- **左栏是节次 + 开始时间 + 结束时间三行，并在行内垂直居中**。三行共 42dp，
  所以行高不足 48dp 时只显示开始时间、不足 40dp 时只显示节次——
  被裁成「10:0」比不显示更糟。
- **授课老师只在两节及以上的课程块里显示**（单节的格子只有约 45dp 宽，第三行会把课名
  挤出卡片），以及单节但没排教室时。其余情况老师仍在课程详情底栏里。
- **底栏是手写的 44dp 单行，而不是 Material3 `NavigationBar`**。后者的高度是 token 不是参数
  （80dp 容器 + 64×32 指示器 + 图标上、文字下的两行内容），要么塞不下、要么把组件挤过
  它自己的内部布局。单行的做法是：**当前页显示名字，其他三页只显示图标**——名字和图标
  本来就是同一个页签的两种表达，各显示一份正好占一行，字不用缩小。手写还顺带把语义写实：
  `selectable` + `Role.Tab`，TalkBack 仍能读出「当前页签」，未选中页的图标也带
  contentDescription，所以信息一点没少。窗口内边距在高度**之前**应用，否则三键导航栏的
  内边距会吃掉内容、把文字裁掉。44dp 距 Android 的 48dp 最小触摸目标只差 4dp。
- **四页共用同一个 `PageHeader`**。此前 设置/调休 用 Material3 `TopAppBar`（64dp、16dp 缩进、
  titleLarge），课表用自己手写的一行（约 52dp、4dp 缩进、titleMedium），于是切页时标题会
  上下跳 10dp、左右跳 12dp，那正是"三页拼在一起"的感觉来源。现在标题、副标题、内缩、
  基线、动作按钮都在一个组件里，四页都是「大标题 + 小标题」，改一处四页一起变。
  四页的卡片间距也统一为 16dp（此前 24dp / 12dp 并存）。
- **一次性提示的注销顺序是有讲究的**。`showSnackbar` 会挂起到提示消失为止，而切页会销毁组合、
  取消那个 `LaunchedEffect`——所以「先弹再注销」的写法在切页时会**跳过注销**，消息留在
  ViewModel 里，之后每次切回该页都重放一遍。现在 `SnackbarMessageEffect` 先注销、
  再把弹出放到组合作用域（不是那个会被 key 变化取消的 effect 里），四页共用一份。
- **日历注册让用户选日历，而不是自己建一个**。`Calendars` 的 `ACCOUNT_NAME` /
  `OWNER_ACCOUNT` 只有 sync adapter 能写，普通应用插入会直接抛
  `only sync adapters may write to account_name`——所以"本应用自建一个日历"这条路
  在真机上从来没有成功过。现在列出手机里**可写入**的日历（访问级别 ≥ 贡献者），
  由用户选定一个写入 `CALENDAR_ID`。幂等性因此改由 `CUSTOM_APP_URI` 保证：重新注册时
  只删除带本应用标记的行，**且不限日历**，所以换过目标日历也不会留下上一份重复课表，
  而用户自己建的事件一个都不会动。
- **日历写入范围默认「仅本周」**。整学期写法是"每门课一条每周重复规则 + 停课周用
  `EXDATE` 排除"，一次点按就会往私人日历里塞进整个学期的课；默认改成只写当前教学周，
  且**写成不带任何重复规则的独立日程**——注意不能简单地"缩窄日期范围"复用原来的重复规则
  生成器：`buildEventSpecs` 的 RRULE 来自该课程**整学期**的周次集合，只给它一周的数据
  会得到"一条覆盖整学期、再排除其余 17 周"的规则，比整学期还糟。想要整学期时可在设置里
  切换（`写入范围`）。日期范围本身是纯函数 `registrationRange`，由 5 项测试固定
  （含"本周第一天/最后一天仍属于本周""今天不在学期内时回退到第 1 周"）。
- **有一个「从日历中移除课表」的出口**。重新注册本来就会先删掉自己上次写的行，但
  "我就想让这些课从日历里消失"原本没有任何按钮——只能去日历应用里一条条手删
  （一周二十来条，一学期几百条）。现在一次点击即可，且只删带本应用标记的行，
  学生自己建的事件不会被碰到。
- **页面切换有动画**。切页按底栏顺序左右滑动（前进向左、后退向右）而不是直接闪出来；
  周次切换也保留原来的滑动+淡入，但时长压到 180ms——那段时间里整张课表要被组合两次，
  动画多长，中端机掉帧的窗口就有多长。
- **课表网格的绘制与复用做了减法**：11 条节次横线 + 7 条分栏竖线原本是每列各画 12 个
  1dp `Box`（共 84 个布局节点），现在由 `drawBehind` 一次画完；每周的课程按星期分桶只算一次
  （此前是 7 次全量过滤 + 7 个新 List，新 List 还会让每列的 `remember` 失效）；
  课程块用 `key(session.id)` 复用节点，两周都上的课不会被拆掉重建。
  > 更正：早期版本的本节曾写「节次栏移到 `AnimatedContent` 之外」，但代码里
  > `PeriodGutter` 仍在 `WeekPage` 内、也就是仍在 `AnimatedContent` 之内。之所以没有真的
  > 移出去：节次栏与七个日列**共用同一个 `ScrollState`**（那是刻意设计，见上），
  > 把它提到 `AnimatedContent` 外面就必须把滚动状态也一起提上去，
  > 代价大于「切周时少排版 11 行标签」的收益。文档已按实际代码改正。
- **`today` 是随时间走的，不是组合时读一次**。课表页原本三处 `remember { LocalDate.now() }`，
  而 `remember` 永远只算第一次。这个 App 的使用方式是「一直开着」（它就是首屏），
  所以挂着过夜之后：今日高亮停在昨天、「从今天开始显示」的列顺序仍以昨天开头、
  当前教学周也没滚到新的一周。现在 `TimetableViewModel` 里有一个每分钟发射一次的
  `todayFlow`，与课表、设置一起 `combine` 进 UI state，界面只读 `state.today`。
  频率取一分钟是刻意的：它远高于取值变化的频率，为的是让跨天**在正在看屏幕的时候就发生**，
  而不是等下一次数据库或设置发射。
- **小组件的读库结果分三态**。「没有学期」和「读库失败」必须分开：前者应当画出「还没有课表」，
  后者应当**什么都不写**、让 preferences 里上一次的内容继续显示。
  此前两者都返回 `null`，于是读库的瞬时故障会把小组件覆盖成空状态并挂满 30 分钟
  （`updatePeriodMillis` 的最小值）——恰恰是该函数注释里写「读库失败不该让小组件变成错误占位」
  想避免的事。

---

## 五、构建

工具链自包含，装在 `.toolchain/`（已 gitignore），不需要系统预装 JDK/Android SDK。

```powershell
# 1) 安装工具链（JDK 17 + Android SDK + Gradle 8.11.1），约 500 MB，需联网
.\tools\dev\toolchain.ps1

# 2) 构建调试包
.\tools\dev\build.ps1                      # 默认 assembleDebug
.\tools\dev\build.ps1 :app:testDebugUnitTest
.\tools\dev\build.ps1 :app:assembleRelease
```

产物：

| 变体 | 路径 | 签名 |
|---|---|---|
| debug | `app/build/outputs/apk/debug/app-debug.apk` | 调试签名（包名带 `.debug` 后缀，可与正式包共存） |
| release | `app/build/outputs/apk/release/app-release.apk` | 本机 release 密钥（见下）；**没有密钥时**产出 `app-release-unsigned.apk` |

### release 签名

密钥与口令放在 `keystore/`（整个目录已 gitignore：`*.jks` / `keystore.properties`），
`app/build.gradle.kts` 在**文件存在时**读取它并启用 `signingConfigs.release`，
不存在时 release 变体照常构建、只是不签名。这个「可选」是刻意的：
没有私钥的人仍然能跑 `assembleRelease` 来验证 R8 与资源压缩规则（那是只有 release
才会暴露的一类错误，例如 kotlinx.serialization 的保留规则写错），而**只有发布**需要私钥。

```powershell
# 发布前自检（用工具链里的 build-tools，不需要系统装 JDK）
$env:JAVA_HOME = "$PWD\.toolchain\jdk"
$bt = Get-ChildItem .toolchain\android-sdk\build-tools -Directory |
      Sort-Object Name -Descending | Select-Object -First 1
& "$($bt.FullName)\apksigner.bat" verify --verbose --print-certs `
    app\build\outputs\apk\release\app-release.apk
& "$($bt.FullName)\zipalign.exe" -c -v 4 app\build\outputs\apk\release\app-release.apk
```

> ⚠️ **务必备份 `keystore/` 整个目录。** 同一个 `applicationId` 的后续版本必须用同一把私钥签名，
> 否则 Android 会拒绝覆盖安装——只能让用户先卸载再装，而卸载会带走本地课表与调休。
> `apksigner` 的期望输出是 `Verifies` 加上 v2/v3 两行 `true`；
> 证书指纹应与上一版一致（自 v2.1.8 起一直是 SHA-256 `3dec7c9b…34a`，v2.3.6 相同）。

> **网络说明**：`maven.google.com` 与 `dl.google.com` 在部分网络下不可用或很慢，
> 因此 `settings.gradle.kts` 优先使用阿里云镜像（google/public/gradle-plugin），
> JDK 走清华 Adoptium 镜像，Gradle 走腾讯镜像。
>
> **PowerShell 说明**：`tools/dev/*.ps1` 刻意使用 `$ErrorActionPreference = 'Continue'`。
> 在 Windows PowerShell 5.1 下，`2>&1` 会把原生命令的 stderr 变成 `ErrorRecord`，
> 配合 `'Stop'` 会把**成功**的命令（如 `java -version`）当成致命错误终止脚本。

---

## 六、已知限制与后续工作

1. **官方 API 凭据门槛（最实质的限制）**：开放平台「暂不向个人开发者开放」，
   学生需由指导教师以教职工身份申请。因此本项目的官方 API 导入路径
   **只有在拿到机构凭据后才可用**；单独的授权码登录界面并不能绕开这一点——
   因为换取 token 时 `client_secret` 同样是必填。
   作为替代，本项目提供**无需任何凭据的 .ics 日历文件导入导出**（见上）。
2. **推测补课可能不准**：官方口径只在通知文字中，请用**调休页**的「粘贴通知」纠正。
3. **数据库迁移**：已移除 `fallbackToDestructiveMigration`（它会静默清空学生的课表、
   配色、备注与手动调休）。代价是**每次 `version` 升级都必须自带真实 `Migration`**，
   否则开发期会直接抛错——这是刻意的取舍：宁可在开发时炸，也不要在用户手上丢数据。
   当前仍是 version 1，尚无迁移需要（但见第 11 条的复合主键变更）。
4. **真机验证仍需你做**：APK 可构建、580 项测试全绿、lint 0 error，
   **且界面已用 Robolectric + Compose 在 JVM 上真正渲染验证**（见下节）。
   仍需真机确认的是：小组件实际显示（含长按移动时不再露直角）、
   **日历写入你选中的那个日历**、上课提醒的实际送达、
   44dp 底栏、三行左栏与"老师/教室"两行在真实字号下的观感
   （自动断言只能证明"放得下"、证明不了"好看"），
   以及**抓取模块的 WebView 捕获**（涉及 `x-token` 请求头与 Cookie，只能在真机上跑）。
5. **性能没有在真机上量化过**。调试包（`app-debug.apk`，19 MiB、无 R8、带调试工具）本身就比
   发布包慢数倍，所以"滑动和切周卡顿"要用 `app-release-unsigned.apk` 签名后的包来判定：
   若发布包仍然卡，请连上 USB 调试后跑
   `adb shell dumpsys gfxinfo com.ranorac.tjtimetable framestats`，那里才有真实帧时间。
6. **仅内置一份作息**：若某校区作息不同需在设置中调整（界面待做）。
7. **周次无法解析时的兜底**：导入时若周次字段无法解析，会**按整学期**处理并在提示中说明，
   宁可多显示也不静默丢课；但若手工构造出空周次的时段，该时段不会出现在任何有日期的视图中。
   解析顺序是 `weeks` 数组 → `weekNum` → `weekstr`（v2.2.1 起 `weekstr` 才真正参与解析，
   此前它只被当成提示文案里的字段名）。
8. **隐藏而非删除**：课程只能隐藏（`hidden = true`），没有彻底删除的入口。
   这是刻意的——教务导入会把删掉的课重新拉回来，所以「删除」会让人以为操作失败。
9. **抓取采用「内置浏览器旁观」，不采用口令 POST 登录**：
   1 系统的个人课表走 `POST /api/electionservice/student/{id}/getDataBk`，
   其中 `{id}` 是选课批次相关的内部 id，**无法稳定构造**；`studentCode` 还是前端加密的 uid。
   因此正确做法是：在内置浏览器里打开真实教务页面，让学生正常登录（统一身份认证、验证码、
   SSO 全由学校页面处理），应用**只旁观页面自己发出的那条课表响应**，
   **从头到尾不接触口令**。这一判断来自 `gzy31007/TJDesktopTimetable` 的实测结论。
   本模块会独立成包，出问题不影响已验证的课表/调休/小组件/日历/.ics 通路。
10. **开放平台 v2 批量接口仍未被调用**：`TongjiApi.studentTimetableBatch` 与
    `TongjiImport.fromBatch` 都已实现且有测试，但仓库只走 v1 实时接口，
    所以「v1 挂了用 v2 兜底」目前只是代码能力，不是运行时行为。
11. **`day_adjustments` 的主键在 v2.2.1 变成 `(termId, epochDay)`**：数据库仍停在
    version 1 且从未发布过带旧主键的正式包，所以没有 `Migration`；但**如果**你的开发机上
    装过 2.1.x 的调试包并直接用新版覆盖，Room 会因为 schema 不一致而抛错——卸载重装即可，
    这不会影响任何真实用户（他们从未装过旧版本）。
