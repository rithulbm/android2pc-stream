[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$pcRoot = Join-Path $repositoryRoot 'pc'
$buildRoot = Join-Path $pcRoot 'build'

function Resolve-BuildTool {
    param(
        [Parameter(Mandatory)] [string] $Command,
        [Parameter(Mandatory)] [string[]] $Candidates
    )

    $resolved = Get-Command $Command -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $resolved -and (Test-Path -LiteralPath $resolved.Source -PathType Leaf)) {
        return $resolved.Source
    }
    foreach ($candidate in $Candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and
            (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return $candidate
        }
    }
    throw "Required Windows build tool was not found: $Command"
}

function Resolve-VcRedist {
    $roots = @()
    if (${env:ProgramFiles(x86)}) {
        $roots += Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2022'
    }
    if ($env:ProgramFiles) {
        $roots += Join-Path $env:ProgramFiles 'Microsoft Visual Studio\2022'
    }

    $matches = foreach ($root in $roots) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            Get-ChildItem -LiteralPath $root -Recurse -File -Filter 'vc_redist.x64.exe' -ErrorAction SilentlyContinue
        }
    }
    $selected = $matches |
        Where-Object { $_.FullName -match '\\VC\\Redist\\MSVC\\' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($null -eq $selected) {
        throw 'Microsoft Visual C++ x64 redistributable was not found under Visual Studio 2022.'
    }
    return $selected.FullName
}

$cmake = Resolve-BuildTool -Command 'cmake.exe' -Candidates @(
    'C:\Program Files\CMake\bin\cmake.exe'
)
$ctest = Resolve-BuildTool -Command 'ctest.exe' -Candidates @(
    'C:\Program Files\CMake\bin\ctest.exe'
)
$innoCandidates = @()
if ($env:LOCALAPPDATA) {
    $innoCandidates += Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'
}
if (${env:ProgramFiles(x86)}) {
    $innoCandidates += Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'
}
if ($env:ProgramFiles) {
    $innoCandidates += Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe'
}
$innoCompiler = Resolve-BuildTool -Command 'ISCC.exe' -Candidates $innoCandidates
$vcRedist = Resolve-VcRedist

& (Join-Path $PSScriptRoot 'bootstrap-windows.ps1')

function Invoke-CleanProcess {
    param(
        [Parameter(Mandatory)] [string] $FilePath,
        [Parameter(Mandatory)] [string[]] $ArgumentList
    )

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Environment.Clear()

    # Windows environment keys are case-insensitive, but some desktop hosts
    # inject both Path and PATH. MSBuild's .NET Framework process launcher
    # rejects that duplicate before CL.exe can start.
    foreach ($key in [Environment]::GetEnvironmentVariables().Keys) {
        if ([string]$key -ine 'PATH') {
            $startInfo.Environment[[string]$key] = [Environment]::GetEnvironmentVariable([string]$key)
        }
    }
    $startInfo.Environment['PATH'] = $env:PATH
    foreach ($argument in $ArgumentList) {
        $startInfo.ArgumentList.Add($argument)
    }

    $process = [Diagnostics.Process]::Start($startInfo)
    if ($null -eq $process) {
        throw "Failed to start: $FilePath"
    }
    $standardOutput = $process.StandardOutput.ReadToEndAsync()
    $standardError = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    if ($standardOutput.Result) { Write-Host $standardOutput.Result }
    if ($standardError.Result) { Write-Host $standardError.Result }
    if ($process.ExitCode -ne 0) {
        throw "Command failed with exit code $($process.ExitCode): $FilePath"
    }
}

Invoke-CleanProcess -FilePath $cmake -ArgumentList @(
    '-S', $pcRoot,
    '-B', $buildRoot,
    '-G', 'Visual Studio 17 2022',
    '-A', 'x64',
    '-T', 'v143',
    '-DLOCAL_CAMERA_BUILD_TESTS=ON'
)
Invoke-CleanProcess -FilePath $cmake -ArgumentList @(
    '--build', $buildRoot,
    '--config', 'Release',
    '--parallel', '8'
)
Invoke-CleanProcess -FilePath $ctest -ArgumentList @(
    '--test-dir', $buildRoot,
    '-C', 'Release',
    '--output-on-failure'
)

& $innoCompiler "/DVcRedistPath=$vcRedist" (Join-Path $pcRoot 'installer\LocalCameraReceiver.iss')
if ($LASTEXITCODE -ne 0) {
    throw "Inno Setup failed with exit code $LASTEXITCODE"
}

$installer = Join-Path $repositoryRoot 'LocalCameraReceiverSetup.exe'
if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    throw "Installer build did not produce: $installer"
}
$installerHash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash
Write-Host "Windows receiver build complete."
Write-Host "Installer: $installer"
Write-Host "SHA-256: $installerHash"
