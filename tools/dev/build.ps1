# Runs Gradle with the self-contained toolchain in .toolchain/.
#
#   tools/dev/build.ps1                      # assembleDebug
#   tools/dev/build.ps1 :app:assembleRelease
#   tools/dev/build.ps1 :app:testDebugUnitTest
#
# Requires network access for the first build (Maven dependencies), which comes
# from Google's Maven mirror on Aliyun because maven.google.com is unreachable.

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs = @('assembleDebug')
)

$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$TC   = Join-Path $Root '.toolchain'

if (-not (Test-Path $TC)) {
    throw "toolchain missing at $TC -- run tools/dev/toolchain.ps1 first"
}

$env:JAVA_HOME       = Join-Path $TC 'jdk'
$env:ANDROID_HOME    = Join-Path $TC 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
# Keep every downloaded artifact inside the workspace so the sandbox can write it
# and nothing leaks into the machine-wide ~/.gradle.
$env:GRADLE_USER_HOME = Join-Path $TC 'gradle-home'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$TC\gradle-8.11.1\bin;$env:PATH"

$gradle = Join-Path $TC 'gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path $gradle)) { throw "gradle missing at $gradle" }

Write-Host "[build] gradle $($GradleArgs -join ' ')"
& $gradle @GradleArgs
$code = $LASTEXITCODE
Write-Host "[build] gradle exit $code"
exit $code
