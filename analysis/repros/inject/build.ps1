# Builds inject.exe and pipe_reader.exe. Run from anywhere.
# Prefers GCC 15 (WinLibs) per analysis/muxer-repro/RESULTS.md; the old
# C:\MinGW g++ 6.3.0 also works for these C++17 sources but is not recommended.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$gpp = $env:WINLIBS_GPP
if (-not $gpp -or -not (Test-Path $gpp)) {
    $candidate = "C:\Users\admin\AppData\Local\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\mingw64\bin\g++.exe"
    if (Test-Path $candidate) { $gpp = $candidate } else { $gpp = "g++" }
}
Write-Host "compiler: $gpp"

$flags = @("-std=c++17", "-O2", "-Wall", "-Wextra",
           "-static", "-static-libgcc", "-static-libstdc++")

& $gpp @flags inject.cpp -o inject.exe -lwinmm
if ($LASTEXITCODE -ne 0) { throw "inject build failed" }

& $gpp @flags pipe_reader.cpp -o pipe_reader.exe
if ($LASTEXITCODE -ne 0) { throw "pipe_reader build failed" }

Write-Host "built: $here\inject.exe"
Write-Host "built: $here\pipe_reader.exe"
