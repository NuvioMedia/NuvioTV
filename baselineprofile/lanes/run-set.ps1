param([string]$Runs = "homeScrollWithBaselineProfile")
$dir = "baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected"
$results = "baselineprofile/lanes/logs/results-step2.txt"
foreach ($r in $Runs.Split(",")) {
    $parts = $r.Split("=", 2)
    $tag = $parts[0]
    $method = $parts[1]
    Write-Output "=== $tag ($method)"
    & powershell -NoProfile -ExecutionPolicy Bypass -File baselineprofile/lanes/run-bench.ps1 -Tag $tag -Methods $method 2>&1 | Select-Object -Last 2
    $f = Get-ChildItem "$dir/*/additionaltestoutput.benchmark.message_*.txt" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -Last 1
    if ($f) {
        $c = (Get-Content $f.FullName -Raw) -replace "`e\[[0-9;]*m", ""
        $frames = if ($c -match "frameCount[\s\S]{0,200}?median\s+([\d.]+)") { $matches[1] } else { "?" }
        $cpuP50 = if ($c -match "frameDurationCpuMs\s+P50\s+([\d.]+)") { $matches[1] } else { "?" }
        $cpuP95 = if ($c -match "frameDurationCpuMs\s+P50\s+[\d.]+,\s+P90\s+[\d.]+,\s+P95\s+([\d.]+)") { $matches[1] } else { "?" }
        $ovrP50 = if ($c -match "frameOverrunMs\s+P50\s+([\d.]+)") { $matches[1] } else { "?" }
        $ovrP95 = if ($c -match "frameOverrunMs\s+P50\s+[\d.]+,\s+P90\s+[\d.]+,\s+P95\s+([\d.]+)") { $matches[1] } else { "?" }
        $line = "$tag | $method | frames=$frames | cpuP50=$cpuP50 | cpuP95=$cpuP95 | overrunP50=$ovrP50 | overrunP95=$ovrP95"
        Write-Output $line
        Add-Content -Path $results -Value $line
    }
}
