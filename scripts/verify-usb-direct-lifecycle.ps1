param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleUserHome = $(if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }),
    [string]$KotlinVersion = '2.3.10'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { (Get-Command java).Source }
    $cache = Join-Path $GradleUserHome 'caches/modules-2/files-2.1'
    $jars = @(Get-ChildItem $cache -Filter '*.jar' -Recurse | ForEach-Object { $_.FullName.Replace('\', '/') })
    function Dependency([string]$group, [string]$artifact, [string]$version) {
        $matches = @($jars | Where-Object { $_.Contains("/$group/$artifact/$version/") })
        if ($matches.Count -ne 1) { throw "Expected one cached $artifact $version jar; found $($matches.Count)." }
        return $matches[0]
    }
    $stdlib = Dependency 'org.jetbrains.kotlin' 'kotlin-stdlib' $KotlinVersion
    # Compiler 2.3.10 uses reflect 2.2.10; a newer reflect can require newer stdlib classes.
    $compiler = @(
        (Dependency 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' $KotlinVersion),
        $stdlib,
        (Dependency 'org.jetbrains.kotlin' 'kotlin-script-runtime' $KotlinVersion),
        (Dependency 'org.jetbrains.kotlin' 'kotlin-reflect' '2.2.10'),
        (Dependency 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.8.0'),
        (Dependency 'org.jetbrains' 'annotations' '13.0')
    ) -join ';'
    $runtime = @($stdlib, (Dependency 'junit' 'junit' '4.13.2'), (Dependency 'org.hamcrest' 'hamcrest-core' '1.3')) -join ';'
    $output = Join-Path $projectRoot 'build/usb-direct-lifecycle'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $hook = 'app/src/main/java/dev/amenhancer/module/hook/'
    $testHook = 'app/src/test/java/dev/amenhancer/module/hook/'
    $sources = @(
        (Get-ChildItem 'scripts/tests/usb-direct/fixtures' -Filter '*.kt' | ForEach-Object FullName),
        'scripts/tests/usb-direct/UsbDirectLifecycleTest.kt',
        ($hook + 'UsbDirectUacController.kt'), ($hook + 'UsbDirectDeviceClient.kt'),
        ($hook + 'UsbDirectVolumePolicy.kt'), ($hook + 'UsbDirectWriteFailurePolicy.kt'),
        ($hook + 'UsbDirectTrackHandoffPolicy.kt'),
        'app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt',
        'app/src/main/java/dev/amenhancer/module/UsbBitPerfectStatusProtocol.kt',
        'app/src/main/java/dev/amenhancer/module/usb/UsbAudioDescriptorParser.kt',
        ($testHook + 'RefactorComponentSource.kt'),
        ($testHook + 'UsbDirectTrackHandoffPolicyTest.kt'), ($testHook + 'UsbDirectVolumePolicyTest.kt'),
        ($testHook + 'UsbDirectWriteFailurePolicyTest.kt'), ($testHook + 'UsbDirectUacStructuralRegressionTest.kt'),
        'app/src/test/java/dev/amenhancer/module/usb/UsbAudioDescriptorParserTest.kt'
    ) | ForEach-Object { $_ }
    & $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -jvm-target 17 -classpath $runtime -d "$output/classes" @sources *> "$output/compile.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$output/compile.log"; throw 'USB lifecycle test compilation failed.' }
    & $java -cp "$output/classes;$runtime" org.junit.runner.JUnitCore `
        dev.amenhancer.module.hook.UsbDirectLifecycleTest `
        dev.amenhancer.module.hook.UsbDirectTrackHandoffPolicyTest `
        dev.amenhancer.module.hook.UsbDirectVolumePolicyTest `
        dev.amenhancer.module.hook.UsbDirectWriteFailurePolicyTest `
        dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest `
        dev.amenhancer.module.usb.UsbAudioDescriptorParserTest
    if ($LASTEXITCODE -ne 0) { throw 'USB lifecycle regression tests failed.' }
} finally {
    Pop-Location
}
