# TJ课表 · TJtimetable

![version](https://img.shields.io/badge/version-2.2.1-blue)
![license](https://img.shields.io/badge/license-MIT-green)
![platform](https://img.shields.io/badge/platform-Android-3DDC84)
![minSdk](https://img.shields.io/badge/minSdk-26-orange)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![tests](https://img.shields.io/badge/tests-540%20passed-success)
![Vibe%20Coded](https://img.shields.io/badge/vibe--coded-yes-ff69b4.svg)

> 同济大学手机课表应用
适配同济大学教务导入和课程特性的Android课表应用。
> [!NOTE]
> 本项目是 vibecoding 产物，请在安装前自行审阅源码。

---

## 功能特性

- **单双周解析**——位掩码表示周次，覆盖同济实际出现的全部书写形式
  （`[1-17单]`、`1-8单,10-16双`、`第1至17周`、全角破折号……），脏数据永不导致导入失败。
- **调休串休**——以「这一天实际执行星期几的课表」反转建模，节假日课自动消失、补课日自动出现、
  真实周末课不受影响；支持校历刷新、教务处通知原文解析与**逐日手动改正**。
- **课表网格**——周切换整页滑动、重叠课程分列、非本周课程虚化而非消失；
  行高与栏宽按屏幕比例自适应，11 节全在一屏内。
- **桌面小组件**——Glance 实现，多比例自适应，显示今日课程、进行中与下一节。
- **系统日历注册**——写入用户**自己选定**的日历，默认**只写本周**，可一键从日历移除。
- **上课提醒**——基于 WorkManager 的应用内通知，节假日与补课日自动被尊重。
- **日历文件（.ics）导入导出**——**无需任何凭据**的课表进出通道，含单双周与调休往返；
  兼容带 `Z`、带 `+0800`、带 `TZID` 的各种日历文件。
- **教务导入**——内置浏览器旁观教务页面自己发出的课表请求（**全程不接触口令**），
  也可粘贴响应体兜底；支持同济开放平台两种课表接口；重新导入时保留自定义配色、备注与隐藏状态。
- **课程详情底栏**——课程学分、考核方式、改配色、记备注、隐藏课程。
- **数据同源**——界面网格、桌面小组件、系统日历与上课提醒全部由同一份
  `TimetableResolver` 展开，节假日、补课日、单双周不可能在其中一处与另一处不一致。

## 部署项目

**环境要求**：Windows + PowerShell + 网络连接（首次构建需下载依赖）。
工具链自包含，**不需要**系统预装 JDK / Android SDK。

```powershell
# 1) 安装工具链（JDK 17 + Android SDK + Gradle 8.11.1），约 500 MB
.\tools\dev\toolchain.ps1

# 2) 构建调试包
.\tools\dev\build.ps1                       # 默认 assembleDebug

# 3) 跑单元测试 / 静态检查 / 发布包
.\tools\dev\build.ps1 :app:testDebugUnitTest
.\tools\dev\build.ps1 :app:lintDebug
.\tools\dev\build.ps1 :app:assembleRelease
```

产物：

| 变体 | 路径 | 签名 |
|---|---|---|
| debug | `app/build/outputs/apk/debug/app-debug.apk` | 调试签名 |
| release | `app/build/outputs/apk/release/app-release.apk` | release 密钥（`keystore/`，已 gitignore） |

> release 签名是**可选**的：`keystore/keystore.properties` 存在时启用，不存在时仍会构建出
> `app-release-unsigned.apk`——这样没有私钥的人也能验证 R8 与资源压缩（只有 release 才会
> 暴露的那类错误），而只有发布需要私钥。详见[技术手册](docs/MANUAL.md#五构建)。

> `maven.google.com` 在部分网络下不可达，因此 `settings.gradle.kts` 优先使用阿里云镜像，
> JDK 走清华 Adoptium 镜像，Gradle 走腾讯镜像。详见[技术手册](docs/MANUAL.md#五构建)。

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言 / 构建 | Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.11.1 · KSP |
| UI | Jetpack Compose · Material 3 · Navigation Compose |
| 持久化 | Room 2.6.1 · DataStore Preferences |
| 网络 / 序列化 | OkHttp 4.12 · kotlinx.serialization 1.7.3 |
| 后台 / 桌面 | WorkManager 2.10 · Glance 1.1 |
| 测试 | JUnit4 · Robolectric 4.14 · Compose UI Test |
| 兼容性 | minSdk 26（Android 8.0）· targetSdk 35 · compileSdk 35 |

## 项目结构

```
app/src/main/java/com/ranorac/tjtimetable/
├── domain/     纯 Kotlin 领域逻辑（周次、校历、调休、解析），无 Android 依赖
├── data/       remote（开放平台 API/导入）、db（Room）、prefs（DataStore）、repo
├── ui/         theme · components · timetable · calendar · settings
├── calendar/   系统日历注册
├── notify/     上课提醒（WorkManager）
├── export/     .ics 导入导出
└── widget/     桌面小组件（Glance）

docs/           技术手册
tools/dev/      自包含工具链安装与构建脚本
gradle/         依赖版本目录（libs.versions.toml）
```

## 隐私与安全

- 网页抓取采用「内置浏览器旁观」方案，**从头到尾不接触口令**，由学校页面自行完成
  统一身份认证、验证码与 SSO。
- 官方开放平台**暂不向个人开发者开放**，因此 `.ics` 导入导出被作为一等公民提供——
  它不需要任何凭据。校历与学期信息接口本身无需授权，调休功能始终可用。

## 文档

详细的 API 实测记录、单双周与调休的实现取舍、工程结构、构建说明、已知限制，
以及开发过程中实际修正的缺陷清单，见 **[技术手册 docs/MANUAL.md](docs/MANUAL.md)**。

## 许可

本项目基于 [MIT License](LICENSE) 开源。

## 致谢

- [KingfuChan/Tongji-CourseTable](https://github.com/KingfuChan/Tongji-CourseTable)——`.ics` 互通格式的既有实践
