[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$pcRoot = Join-Path $repositoryRoot 'pc'
$buildRoot = Join-Path $pcRoot 'build'
$cmake = 'C:\Program Files\CMake\bin\cmake.exe'
$ctest = 'C:\Program Files\CMake\bin\ctest.exe'
$innoCompiler = Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'

foreach ($tool in @($cmake, $ctest, $innoCompiler)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required Windows build tool was not found: $tool"
    }
}

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

& $innoCompiler (Join-Path $pcRoot 'installer\LocalCameraReceiver.iss')
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
