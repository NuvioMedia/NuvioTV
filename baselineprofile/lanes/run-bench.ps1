param(
  [string]$Tag = "run",
  [string]$Methods = "homeScrollWithBaselineProfile"
)
$env:CI_USE_DEBUG_SIGNING = "true"
$logDir = "baselineprofile/lanes/logs"
$out = Join-Path $logDir "bench-$Tag.log"
$err = Join-Path $logDir "bench-$Tag.err.log"
$class = "com.nuvio.tv.baselineprofile.NuvioFrameMetricsBenchmark"
$spec = if ($Methods -ne "") { "$class#$Methods" } else { $class }
& .\gradlew.bat ":baselineprofile:connectedBenchmarkReleaseAndroidTest" `
  "-Pandroid.testInstrumentationRunnerArguments.class=$spec" `
  "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR" `
  "--console=plain" "--stacktrace" *>&1 | Tee-Object -FilePath $out
Write-Output "EXITCODE=$LASTEXITCODE"
