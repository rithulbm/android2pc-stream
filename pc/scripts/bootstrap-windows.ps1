[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$installRoot = Join-Path $repositoryRoot '.install'
$obsSourceRoot = Join-Path $installRoot 'obs-source'
$obsCommit = '0052d024fd6a5ff1aa04c76cbdffd3085a5dfacc'
$obsArchiveName = 'obs-studio-32.2.1-0052d024.zip'
$obsArchive = Join-Path $installRoot $obsArchiveName
$obsExpectedHash = 'DAF0EF9D44B8948E9CC8E2849965E3B27629A924F4F126E38102CE4792BD1668'
$obsTarget = Join-Path $obsSourceRoot "obs-studio-$obsCommit"
$obsHeader = Join-Path $obsTarget 'libobs\obs-module.h'
$obsUrl = "https://github.com/obsproject/obs-studio/archive/$obsCommit.zip"

New-Item -ItemType Directory -Path $installRoot -Force | Out-Null
New-Item -ItemType Directory -Path $obsSourceRoot -Force | Out-Null

if (-not (Test-Path -LiteralPath $obsHeader -PathType Leaf)) {
    if (-not (Test-Path -LiteralPath $obsArchive -PathType Leaf)) {
        $partialArchive = "$obsArchive.partial.$PID"
        if (Test-Path -LiteralPath $partialArchive) {
            throw "Refusing to overwrite existing partial download: $partialArchive"
        }
        Invoke-WebRequest -Uri $obsUrl -OutFile $partialArchive -UseBasicParsing
        $partialHash = (Get-FileHash -LiteralPath $partialArchive -Algorithm SHA256).Hash
        if ($partialHash -ne $obsExpectedHash) {
            throw "OBS archive hash mismatch. Expected $obsExpectedHash, received $partialHash. Partial file: $partialArchive"
        }
        Move-Item -LiteralPath $partialArchive -Destination $obsArchive
    }

    $archiveHash = (Get-FileHash -LiteralPath $obsArchive -Algorithm SHA256).Hash
    if ($archiveHash -ne $obsExpectedHash) {
        throw "Existing OBS archive hash mismatch. Expected $obsExpectedHash, received $archiveHash"
    }
    if (Test-Path -LiteralPath $obsTarget) {
        throw "OBS target exists but is incomplete: $obsTarget"
    }

    $stagingRoot = Join-Path $installRoot "obs-source-stage-$PID"
    if (Test-Path -LiteralPath $stagingRoot) {
        throw "Refusing to overwrite existing extraction stage: $stagingRoot"
    }
    New-Item -ItemType Directory -Path $stagingRoot | Out-Null
    Expand-Archive -LiteralPath $obsArchive -DestinationPath $stagingRoot
    $stagedSource = Join-Path $stagingRoot "obs-studio-$obsCommit"
    if (-not (Test-Path -LiteralPath (Join-Path $stagedSource 'libobs\obs-module.h') -PathType Leaf)) {
        throw "Pinned OBS archive did not contain the expected source tree: $stagedSource"
    }
    Move-Item -LiteralPath $stagedSource -Destination $obsTarget
    Remove-Item -LiteralPath $stagingRoot
}

$nativeRoot = Join-Path $repositoryRoot 'mobile\app\src\main\cpp\third_party'
$requiredNativeFiles = @(
    (Join-Path $nativeRoot 'srt\CMakeLists.txt'),
    (Join-Path $nativeRoot 'SOURCE_LOCK.md'),
    (Join-Path $nativeRoot 'botan\configure.py')
)
foreach ($requiredFile in $requiredNativeFiles) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Pinned native source is missing: $requiredFile"
    }
}

$requiredQrFiles = @(
    (Join-Path $repositoryRoot 'pc\third_party\qrcodegen\qrcodegen.cpp'),
    (Join-Path $repositoryRoot 'pc\third_party\qrcodegen\qrcodegen.hpp'),
    (Join-Path $repositoryRoot 'pc\third_party\qrcodegen\Readme.markdown')
)
foreach ($requiredFile in $requiredQrFiles) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Vendored QR source is missing: $requiredFile"
    }
}

Write-Host "Pinned Windows receiver sources are ready."
Write-Host "OBS headers: $obsTarget"
