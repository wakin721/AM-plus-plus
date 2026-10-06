param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Compiler = 'cl'
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { throw 'Supply -JavaHome pointing to a JDK with JNI headers.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $output = Join-Path $projectRoot 'build/usb-direct-native'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    # MSVC requires a Visual Studio Developer PowerShell/command-prompt environment.
    & $Compiler /nologo /std:c++17 /EHsc /D_CRT_SECURE_NO_WARNINGS `
        "/I$JavaHome/include" "/I$JavaHome/include/win32" `
        '/Iscripts/tests/usb-direct/native/fixtures' `
        'scripts/tests/usb-direct/native/UsbDirectRingTest.cpp' `
        "/Fo$output/ring.obj" "/Fe$output/ring.exe"
    if ($LASTEXITCODE -ne 0) { throw 'Native USB ring test compilation failed.' }
    & "$output/ring.exe"
    if ($LASTEXITCODE -ne 0) { throw 'Native USB ring regression test failed.' }
} finally {
    Pop-Location
}
