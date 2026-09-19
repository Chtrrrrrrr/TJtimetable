# TJtimetable dev toolchain installer
#
# Installs a self-contained JDK 17 + Android SDK + Gradle into .toolchain/.
# Uses standard tools (curl.exe / Expand-Archive) and mainland-China mirrors
# because maven.google.com is unreachable and the Adoptium GitHub release is slow.
#
# Requires network access (run with elevated sandbox permissions).
#
# NOTE ON ERROR HANDLING: $ErrorActionPreference stays 'Continue' deliberately.
# curl/java/sdkmanager all write to stderr as normal operation, and under
# 'Stop' every such line becomes a terminating NativeCommandError -- including
# from commands that succeeded. So failures are detected via exit codes and
# explicit `throw` instead, and a partial install must never look like success.

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$PSNativeCommandUseErrorActionPreference = $false

$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$TC   = Join-Path $Root '.toolchain'
$DL   = Join-Path $TC 'downloads'

New-Item -ItemType Directory -Force -Path $DL | Out-Null

function Log($m)  { Write-Host "[$(Get-Date -Format HH:mm:ss)] $m" }
function Warn($m) { Write-Host "[$(Get-Date -Format HH:mm:ss)] WARN $m" -ForegroundColor Yellow }

# Runs a native command, capturing both streams as strings and its exit code.
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    $out = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" }
    return [pscustomobject]@{ Output = @($out); ExitCode = $LASTEXITCODE }
}

function Get-File($url, $dest, $minBytes = 1MB) {
    if ((Test-Path $dest) -and (Get-Item $dest).Length -ge $minBytes) {
        Log "skip  $(Split-Path -Leaf $dest)  ($([math]::Round((Get-Item $dest).Length/1MB,1)) MiB)"
        return $true
    }
    Log "get   $url"
    $tmp = "$dest.part"
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    $r = Invoke-Native 'curl.exe' @(
        '-L', '--fail', '--retry', '5', '--retry-delay', '3', '--retry-all-errors',
        '--connect-timeout', '30', '--max-time', '3600', '-sS', '-o', $tmp, $url
    )
    if ($r.ExitCode -ne 0) {
        Warn "curl exit $($r.ExitCode): $($r.Output -join ' ')"
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        return $false
    }
    if (-not (Test-Path $tmp) -or (Get-Item $tmp).Length -lt $minBytes) {
        Warn "short download: $url"
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        return $false
    }
    Move-Item $tmp $dest -Force
    Log "done  $(Split-Path -Leaf $dest)  ($([math]::Round((Get-Item $dest).Length/1MB,1)) MiB)"
    return $true
}

function Expand-Zip($zip, $dest) {
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Log "unzip $(Split-Path -Leaf $zip)"
    Expand-Archive -Path $zip -DestinationPath $dest -Force
}

function Expand-ZipSingleRoot($zip, $dest, $what) {
    $tmp = "$dest.tmp"
    Expand-Zip $zip $tmp
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $inner) { throw "unexpected $what archive layout (no top-level directory)" }
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Move-Item $inner.FullName $dest
    Remove-Item $tmp -Recurse -Force
}

# ---------------------------------------------------------------- 1. JDK 17
$JdkDir = Join-Path $TC 'jdk'
if (-not (Test-Path (Join-Path $JdkDir 'bin\java.exe'))) {
    Log '--- resolving JDK 17 from Tsinghua Adoptium mirror ---'
    $mirror = 'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/'
    $r = Invoke-Native 'curl.exe' @('-sS', '-L', '--max-time', '60', $mirror)
    $listing = $r.Output -join "`n"
    $names = @(
        [regex]::Matches($listing, 'href="(OpenJDK17U-jdk_x64_windows_hotspot_[^"]+\.zip)"') |
            ForEach-Object { $_.Groups[1].Value } |
            Sort-Object -Unique
    )
    if ($names.Count -eq 0) {
        Warn 'could not parse mirror listing; falling back to a known build'
        $names = @('OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip')
    }
    # @() is load-bearing: a one-element result is a String, and "abc"[-1] is 'c'.
    $zipName = @($names)[-1]
    Log "selected $zipName"
    $zip = Join-Path $DL $zipName
    if (-not (Get-File "$mirror$zipName" $zip 50MB)) { throw 'JDK download failed' }
    Expand-ZipSingleRoot $zip $JdkDir 'JDK'
    Log "JDK ready: $JdkDir"
} else { Log "skip  JDK already installed" }

$env:JAVA_HOME = $JdkDir
$env:PATH = "$JdkDir\bin;$env:PATH"
$jv = Invoke-Native (Join-Path $JdkDir 'bin\java.exe') @('-version')
Log "java: $(($jv.Output -join ' ').Trim())"
if ($jv.ExitCode -ne 0) { throw 'java is not runnable' }

# ------------------------------------------------- 2. Android command-line tools
$Sdk        = Join-Path $TC 'android-sdk'
$CltRoot    = Join-Path $Sdk 'cmdline-tools'
$CltLatest  = Join-Path $CltRoot 'latest'
$SdkManager = Join-Path $CltLatest 'bin\sdkmanager.bat'

if (-not (Test-Path $SdkManager)) {
    Log '--- Android command-line tools ---'
    $cltZip = Join-Path $DL 'cmdline-tools.zip'
    $ok = $false
    foreach ($u in @(
        'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip',
        'https://dl.google.com/android/repository/commandlinetools-win-10406996_latest.zip'
    )) { if (Get-File $u $cltZip 30MB) { $ok = $true; break } }
    if (-not $ok) { throw 'could not download Android command-line tools' }
    # The archive holds cmdline-tools/{bin,lib,...}; sdkmanager requires it to sit
    # at cmdline-tools/latest/ or it refuses to resolve the SDK root.
    Expand-ZipSingleRoot $cltZip $CltLatest 'command-line tools'
    if (-not (Test-Path $SdkManager)) { throw "sdkmanager missing after extract: $SdkManager" }
    Log "cmdline-tools ready"
} else { Log "skip  cmdline-tools already installed" }

# ------------------------------------------------------------ 3. Gradle
$GradleVersion = '8.11.1'
$GradleDir = Join-Path $TC "gradle-$GradleVersion"
if (-not (Test-Path (Join-Path $GradleDir 'bin\gradle.bat'))) {
    Log '--- Gradle ---'
    $gzZip = Join-Path $DL "gradle-$GradleVersion-bin.zip"
    $ok = $false
    foreach ($u in @(
        "https://mirrors.cloud.tencent.com/gradle/gradle-$GradleVersion-bin.zip",
        "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
    )) { if (Get-File $u $gzZip 30MB) { $ok = $true; break } }
    if (-not $ok) { throw 'could not download Gradle' }
    Expand-ZipSingleRoot $gzZip $GradleDir 'Gradle'
    Log "Gradle ready"
} else { Log "skip  Gradle already installed" }

# ------------------------------------------------- 4. Android SDK packages
# ANDROID_HOME is set instead of passing --sdk_root, because this workspace path
# contains a space and cmd.exe mangles spaced arguments to a .bat shim.
$env:ANDROID_HOME     = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk

Log '--- accepting Android SDK licenses ---'
$yes = 1..80 | ForEach-Object { 'y' }
$lic = $yes | & $SdkManager --licenses 2>&1 | ForEach-Object { "$_" }
$lic | Select-Object -Last 6 | ForEach-Object { Log "  $_" }

$packages = @('platform-tools', 'platforms;android-35', 'build-tools;35.0.0')
foreach ($p in $packages) {
    Log "install $p"
    $r = $yes | & $SdkManager $p 2>&1 | ForEach-Object { "$_" }
    $r | Select-Object -Last 4 | ForEach-Object { Log "  $_" }
    if ($LASTEXITCODE -ne 0) { Warn "sdkmanager reported exit $LASTEXITCODE for $p" }
}

Log '--- installed SDK components ---'
foreach ($d in @('platform-tools', 'platforms', 'build-tools')) {
    $p = Join-Path $Sdk $d
    if (Test-Path $p) {
        Get-ChildItem $p -Directory | ForEach-Object { Log "  $d/$($_.Name)" }
    } else { Warn "missing SDK component: $d" }
}

# --------------------------------------------------- 5. record env for later
$envFile = Join-Path $TC 'env.ps1'
@"
# Generated by tools/dev/toolchain.ps1 - source this before running gradle
`$env:JAVA_HOME = '$JdkDir'
`$env:ANDROID_HOME = '$Sdk'
`$env:ANDROID_SDK_ROOT = '$Sdk'
`$env:GRADLE_USER_HOME = Join-Path '$TC' 'gradle-home'
`$env:PATH = "`$env:JAVA_HOME\bin;$Sdk\platform-tools;$GradleDir\bin;`$env:PATH"
"@ | Set-Content -Path $envFile -Encoding UTF8

Log ''
Log '=== toolchain ready ==='
Log "  JAVA_HOME    = $JdkDir"
Log "  ANDROID_HOME = $Sdk"
Log "  GRADLE       = $GradleDir"
Log "  env script   = $envFile"
