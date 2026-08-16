[CmdletBinding()]
param(
    [string]$ObsLogDirectory = (Join-Path $env:APPDATA 'obs-studio\logs'),
    [string]$PluginDirectory = (Join-Path $env:ProgramData 'obs-studio\plugins\local-camera-receiver')
)

$ErrorActionPreference = 'Stop'

$pluginPath = Join-Path $PluginDirectory 'bin\64bit\local-camera-receiver.dll'
if (-not (Test-Path -LiteralPath $pluginPath -PathType Leaf)) {
    throw "Installed plugin was not found at the expected path: $pluginPath"
}

$latestLog = Get-ChildItem -LiteralPath $ObsLogDirectory -Filter '*.txt' -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $latestLog) {
    throw "No OBS startup log was found in: $ObsLogDirectory"
}

$sourceLogLines = Select-String -LiteralPath $latestLog.FullName `
    -Pattern 'local_camera_receiver_source' `
    -SimpleMatch
$registrationFailure = $sourceLogLines | Where-Object {
    $_.Line -match '(?i)(failed|required value|cannot be)'
} | Select-Object -First 1
$moduleSeen = Select-String -LiteralPath $latestLog.FullName `
    -Pattern 'local-camera-receiver.dll' `
    -SimpleMatch -Quiet

if ($registrationFailure) {
    Write-Error "FAIL: OBS found the plugin DLL but rejected the Local Camera Receiver source registration: $($registrationFailure.Line.Trim())"
    exit 1
}

if (-not $moduleSeen) {
    Write-Error 'FAIL: The latest OBS startup did not discover local-camera-receiver.dll.'
    exit 1
}

Write-Host 'PASS: OBS discovered the plugin and did not reject its source registration.'
