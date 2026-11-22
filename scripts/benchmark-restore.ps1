# Restore a phone after a Macrobenchmark / device run.
# benchmark-device.ps1 turns animations off and keeps the screen awake for stable frame timing; this puts the
# user's settings back. Run it after every benchmark, even if the run failed.

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

& $adb shell settings put global window_animation_scale 1
& $adb shell settings put global transition_animation_scale 1
& $adb shell settings put global animator_duration_scale 1
& $adb shell svc power stayon false

Write-Host "Restored: animations 1x, stay-awake off."
Write-Host "Current values:"
& $adb shell settings get global window_animation_scale
& $adb shell settings get global transition_animation_scale
& $adb shell settings get global animator_duration_scale
