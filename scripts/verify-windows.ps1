<#
.SYNOPSIS
Runs JVM tests, APK assembly and lint in separate bounded Gradle processes.
.EXAMPLE
./scripts/verify-windows.ps1 -JavaHome 'G:/tools/jdk/jdk-17.0.20.1+1' -AndroidSdk 'G:/tools/android-sdk'
#>
[CmdletBinding()]
param(
    [ValidateSet('All', 'Test', 'Build', 'Lint', 'ReleaseTest', 'ReleaseBuild', 'AndroidTest')]
    [string]$Phase = 'All',
    [ValidateRange(2, 32)]
    [int]$HeapGiB = 12,
    [ValidateRange(1, 8)]
    [int]$Workers = 2,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
# Collect native nonzero exits explicitly, including when a caller enabled the
# PowerShell 7 native-command error preference in their profile.
$PSNativeCommandUseErrorActionPreference = $false
$projectRoot = Split-Path -Parent $PSScriptRoot
$tasks = [ordered]@{
    Test = ':app:testFullDebugUnitTest'
    Build = ':app:assembleFullDebug'
    Lint = ':app:lintFullDebug'
    ReleaseTest = ':app:testFullReleaseUnitTest'
    ReleaseBuild = ':app:assembleFullRelease'
    AndroidTest = ':app:assembleFullDebugAndroidTest'
}
$selectedPhases = if ($Phase -eq 'All') { @('Test', 'Build', 'Lint') } else { @($Phase) }
$gradleArguments = @(
    '--no-daemon', '--no-parallel', '--configure-on-demand', '--console=plain', '--stacktrace',
    "--max-workers=$Workers",
    "-Dorg.gradle.jvmargs=-Xmx${HeapGiB}g -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8",
    '-Pkotlin.compiler.execution.strategy=in-process'
)

if ($DryRun) {
    foreach ($selectedPhase in $selectedPhases) {
        Write-Output ("{0}: gradlew.bat {1} {2}" -f $selectedPhase, $tasks[$selectedPhase], ($gradleArguments -join ' '))
    }
    return
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17 directory.'
}
if ($AndroidSdk -and -not (Test-Path -LiteralPath $AndroidSdk)) {
    throw 'The Android SDK directory does not exist.'
}

$previousJavaHome = $env:JAVA_HOME
$previousAndroidHome = $env:ANDROID_HOME
$runId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$logDirectory = Join-Path $projectRoot "output/verification/$runId"
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$results = [System.Collections.Generic.List[object]]::new()
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JavaHome
    if ($AndroidSdk) { $env:ANDROID_HOME = $AndroidSdk }
    foreach ($selectedPhase in $selectedPhases) {
        $started = Get-Date
        $logFile = Join-Path $logDirectory "$($selectedPhase.ToLowerInvariant()).log"
        Write-Host "Running $selectedPhase separately ($HeapGiB GiB maximum heap; $Workers workers)."
        # A fresh single-use daemon exits after each invocation. In-process Kotlin
        # compilation avoids adding a second large compiler daemon to that heap.
        & (Join-Path $projectRoot 'gradlew.bat') $tasks[$selectedPhase] @gradleArguments 2>&1 |
            Tee-Object -FilePath $logFile | Out-Host
        $phaseExitCode = $LASTEXITCODE
        $results.Add([pscustomobject]@{
            phase = $selectedPhase
            task = $tasks[$selectedPhase]
            exitCode = $phaseExitCode
            elapsedSeconds = [Math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            log = [IO.Path]::GetFileName($logFile)
        })
        $results | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $logDirectory 'results.json') -Encoding UTF8
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:ANDROID_HOME = $previousAndroidHome
    Pop-Location
}
Write-Host "Verification results: $logDirectory"
if (@($results | Where-Object { $_.exitCode -ne 0 }).Count -gt 0) {
    throw 'Verification failed. Consult results.json and the individual logs; lint findings remain blocking.'
}
