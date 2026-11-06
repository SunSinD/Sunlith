param(
    [string]$Serial = 'emulator-5554',
    [string]$OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) '.toolchain\screenshots'),
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$sunlithRoot = Split-Path -Parent $PSScriptRoot
$sunlithEnv = Join-Path $sunlithRoot '.toolchain\env.ps1'
if (Test-Path -LiteralPath $sunlithEnv) { . $sunlithEnv }
$sunlithAdb = (Get-Command adb -ErrorAction Stop).Source

function Invoke-SunlithAdb {
    param([string[]]$Arguments)
    $sunlithPreviousError = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = & $sunlithAdb -s $Serial @Arguments 2>&1
        $sunlithExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $sunlithPreviousError }
    if ($sunlithExitCode -ne 0) { throw ($result -join "`n") }
    return $result | ForEach-Object { $_.ToString() }
}

function Invoke-SunlithTest {
    param([string]$ClassName)
    $result = Invoke-SunlithAdb @('shell', 'am', 'instrument', '-w', '-e', 'class', $ClassName, 'io.github.sunsind.sunlith.test/androidx.test.runner.AndroidJUnitRunner')
    $result | Write-Output
    if (($result -join "`n") -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_CODE: 0') {
        throw "Instrumentation failed: $ClassName"
    }
}

if ((Invoke-SunlithAdb @('shell', 'getprop', 'ro.kernel.qemu') | Out-String).Trim() -ne '1') {
    throw 'Demo capture is restricted to an emulator. It must not add sample events to a personal phone.'
}

if (-not $SkipInstall) {
    Push-Location $sunlithRoot
    try {
        & .\gradlew.bat assembleDebug assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'The debug build failed.' }
    } finally { Pop-Location }
    Invoke-SunlithAdb @('install', '-r', (Join-Path $sunlithRoot 'app\build\outputs\apk\debug\app-debug.apk'))
    Invoke-SunlithAdb @('install', '-r', (Join-Path $sunlithRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'))
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
Invoke-SunlithTest 'io.github.sunsind.sunlith.DemoFixtureTest'
Invoke-SunlithAdb @('shell', 'am', 'start', '-W', '-n', 'io.github.sunsind.sunlith/.MainActivity')
$sunlithCalendarVisible = $false
for ($sunlithAttempt = 0; $sunlithAttempt -lt 6; $sunlithAttempt++) {
    Invoke-SunlithAdb @('shell', 'uiautomator', 'dump', '/sdcard/sunlith-capture-ui.xml') | Out-Null
    $sunlithHierarchy = Invoke-SunlithAdb @('shell', 'cat', '/sdcard/sunlith-capture-ui.xml') | Out-String
    if ($sunlithHierarchy.Contains('text="Driving class"')) {
        $sunlithCalendarVisible = $true
        break
    }
}
if (-not $sunlithCalendarVisible) { throw 'The running calendar did not show its sample event before capture.' }
Invoke-SunlithAdb @('shell', 'screencap', '-p', '/sdcard/sunlith-calendar.png')
Invoke-SunlithAdb @('pull', '/sdcard/sunlith-calendar.png', (Join-Path $OutputDirectory 'month-agenda.png'))
Invoke-SunlithTest 'io.github.sunsind.sunlith.WidgetSmokeTest'
Invoke-SunlithAdb @('pull', '/sdcard/Android/data/io.github.sunsind.sunlith/files/sunlith-widget.png', (Join-Path $OutputDirectory 'widget.png'))
Write-Output "Captured real emulator UI in $OutputDirectory. Widget image uses an instrumentation-only Android widget host."
