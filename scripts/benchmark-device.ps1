param(
  [string]$AppApk = "app/build/outputs/apk/benchmark/app-benchmark.apk",
  [string]$TestApk = "macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk"
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$devices = (& $adb devices | Select-String -Pattern "\tdevice$").Count
if ($devices -ne 1) { throw "Connect exactly one Android 14+ arm64 device (found $devices)." }

if (-not (Test-Path $AppApk)) { throw "Missing $AppApk; run :app:assembleBenchmark first." }
if (-not (Test-Path $TestApk)) { throw "Missing $TestApk; run :macrobenchmark:assembleBenchmark first." }

& $adb install -r $AppApk
& $adb install -r $TestApk

# Stable frame timing and an awake screen while the harness runs.
& $adb shell settings put global window_animation_scale 0
& $adb shell settings put global transition_animation_scale 0
& $adb shell settings put global animator_duration_scale 0
& $adb shell svc power stayon true
& $adb shell pm grant com.past9.phoneaos android.permission.RECORD_AUDIO
& $adb shell pm grant com.past9.phoneaos android.permission.POST_NOTIFICATIONS
& $adb shell cmd notification allow_listener com.past9.phoneaos/com.past9.phoneaos.system.NotificationReader
& $adb shell input keyevent KEYCODE_WAKEUP
& $adb shell wm dismiss-keyguard

Write-Host "Benchmark build installed. Run:"
Write-Host "  java -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :macrobenchmark:connectedBenchmarkAndroidTest"
