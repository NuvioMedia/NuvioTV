param([string]$Tag = "run")
$dir = "baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected"
$files = Get-ChildItem "$dir/*/additionaltestoutput.benchmark.message_*.txt" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime
foreach ($f in $files) {
    $c = (Get-Content $f.FullName -Raw) -replace "`e\[[0-9;]*m", ""
    $name = $f.Name -replace ".*NuvioFrameMetricsBenchmark\.", "" -replace "\.txt", ""
    $frames = if ($c -match "frameCount[\s\S]{0,200}?median\s+([\d.]+)") { $matches[1] } else { "?" }
    $cpu = if ($c -match "frameDurationCpuMs\s+P50\s+([\d.]+),\s+P90\s+([\d.]+),\s+P95\s+([\d.]+),\s+P99\s+([\d.]+)") { "$($matches[1]) / $($matches[3])" } else { "?" }
    $ovr = if ($c -match "frameOverrunMs\s+P50\s+([\d.]+),\s+P90\s+([\d.]+),\s+P95\s+([\d.]+),\s+P99\s+([\d.]+)") { "$($matches[1]) / $($matches[3])" } else { "?" }
    Write-Output "$name | frames=$frames | cpuP50/P95=$cpu | overrunP50/P95=$ovr | $($f.LastWriteTime.ToString('HH:mm:ss'))"
}
