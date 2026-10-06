param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleUserHome = $(if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }),
    [string]$KotlinVersion = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
    $java = if ($JavaHome) { Join-Path $JavaHome "bin/$javaName" } else { (Get-Command java).Source }
    $separator = [IO.Path]::PathSeparator
    $cache = Join-Path $GradleUserHome 'caches/modules-2/files-2.1'
    $jars = @(Get-ChildItem $cache -Filter '*.jar' -Recurse | ForEach-Object { $_.FullName.Replace('\', '/') })
    function Dependency([string]$group, [string]$artifact, [string]$version) {
        $matches = @($jars | Where-Object { $_.Contains("/$group/$artifact/$version/") })
        if ($matches.Count -ne 1) { throw "Expected one cached $artifact $version jar; found $($matches.Count)." }
        return $matches[0]
    }
    function LatestDependencyVersion([string]$group, [string]$artifact) {
        $prefix = "$cache/$group/$artifact/".Replace('\', '/')
        $versions = @($jars | Where-Object { $_.StartsWith($prefix) } | ForEach-Object {
            $_.Substring($prefix.Length).Split('/')[0]
        } | Sort-Object -Unique)
        if (!$versions) { throw "No cached $artifact jar found." }
        return ($versions | Sort-Object { [version]($_ -replace '-.*$', '') } -Descending | Select-Object -First 1)
    }
    if (!$KotlinVersion) { $KotlinVersion = LatestDependencyVersion 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' }
    $compilerPom = Get-ChildItem "$cache/org.jetbrains.kotlin/kotlin-compiler-embeddable/$KotlinVersion" -Filter '*.pom' -Recurse | Select-Object -First 1
    $compilerDependencies = if ($compilerPom) { ([xml](Get-Content $compilerPom.FullName -Raw)).project.dependencies.dependency } else { @() }
    function CompilerDependency([string]$group, [string]$artifact) {
        $dependency = $compilerDependencies | Where-Object { $_.artifactId -eq $artifact } | Select-Object -First 1
        $version = if ($dependency) { $dependency.version } else { LatestDependencyVersion $group $artifact }
        return Dependency $group $artifact $version
    }
    $stdlib = Dependency 'org.jetbrains.kotlin' 'kotlin-stdlib' $KotlinVersion
    # Use the compiler's dependencies, including its pinned older reflect when present.
    $compiler = @(
        (Dependency 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' $KotlinVersion),
        $stdlib,
        (Dependency 'org.jetbrains.kotlin' 'kotlin-script-runtime' $KotlinVersion),
        (CompilerDependency 'org.jetbrains.kotlin' 'kotlin-reflect'),
        (CompilerDependency 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm'),
        (CompilerDependency 'org.jetbrains' 'annotations')
    ) -join $separator
    $runtime = @($stdlib, (Dependency 'junit' 'junit' '4.13.2'), (Dependency 'org.hamcrest' 'hamcrest-core' '1.3')) -join $separator
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
        ($hook + 'UsbDirectNativeLibraryLoader.kt'),
        ($hook + 'UsbDirectPlaybackPower.kt'),
        ($hook + 'UsbDirectPlaybackKeepAlive.kt'),
        'app/src/main/java/dev/amenhancer/module/UsbDirectIpc.kt',
        'app/src/main/java/dev/amenhancer/module/UsbBitPerfectStatusProtocol.kt',
        'app/src/main/java/dev/amenhancer/module/usb/UsbAudioDescriptorParser.kt',
        ($testHook + 'RefactorComponentSource.kt'),
        ($testHook + 'UsbDirectNativeLibraryLoaderTest.kt'),
        ($testHook + 'UsbDirectTrackHandoffPolicyTest.kt'), ($testHook + 'UsbDirectVolumePolicyTest.kt'),
        ($testHook + 'UsbDirectWriteFailurePolicyTest.kt'), ($testHook + 'UsbDirectUacStructuralRegressionTest.kt'),
        'app/src/test/java/dev/amenhancer/module/usb/UsbAudioDescriptorParserTest.kt'
    ) | ForEach-Object { $_ }
    & $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -jvm-target 17 -classpath $runtime -d "$output/classes" @sources *> "$output/compile.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$output/compile.log"; throw 'USB lifecycle test compilation failed.' }
    & $java -cp "$output/classes$separator$runtime" org.junit.runner.JUnitCore `
        dev.amenhancer.module.hook.UsbDirectLifecycleTest `
        dev.amenhancer.module.hook.UsbDirectNativeLibraryLoaderTest `
        dev.amenhancer.module.hook.UsbDirectTrackHandoffPolicyTest `
        dev.amenhancer.module.hook.UsbDirectVolumePolicyTest `
        dev.amenhancer.module.hook.UsbDirectWriteFailurePolicyTest `
        dev.amenhancer.module.hook.UsbDirectUacStructuralRegressionTest `
        dev.amenhancer.module.usb.UsbAudioDescriptorParserTest
    if ($LASTEXITCODE -ne 0) { throw 'USB lifecycle regression tests failed.' }
} finally {
    Pop-Location
}
