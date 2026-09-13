param(
    [string]$NdkHome = $env:ANDROID_NDK_HOME,
    [string]$GoCommand = "go"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    throw "Set ANDROID_NDK_HOME or pass -NdkHome."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $repoRoot "app/src/main/jniLibs"
$toolchain = Join-Path $NdkHome "toolchains/llvm/prebuilt/windows-x86_64/bin"

$targets = @(
    @{ Abi = "arm64-v8a"; GoArch = "arm64"; Cc = "aarch64-linux-android24-clang.cmd" },
    @{ Abi = "armeabi-v7a"; GoArch = "arm"; GoArm = "7"; Cc = "armv7a-linux-androideabi24-clang.cmd" },
    @{ Abi = "x86"; GoArch = "386"; Cc = "i686-linux-android24-clang.cmd" },
    @{ Abi = "x86_64"; GoArch = "amd64"; Cc = "x86_64-linux-android24-clang.cmd" }
)

Push-Location $PSScriptRoot
try {
    foreach ($target in $targets) {
        $env:GOOS = "android"
        $env:GOARCH = $target.GoArch
        $env:CGO_ENABLED = "1"
        $env:CC = Join-Path $toolchain $target.Cc
        if ($target.GoArm) { $env:GOARM = $target.GoArm } else { Remove-Item Env:GOARM -ErrorAction SilentlyContinue }

        $outputDir = Join-Path $outputRoot $target.Abi
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        $outputFile = Join-Path $outputDir "libnuvionntp.so"
        & $GoCommand build -buildmode=pie -trimpath -ldflags="-s -w -buildid=" -o $outputFile ./cmd/nuvio-nntp
        if ($LASTEXITCODE -ne 0) { throw "Go build failed for $($target.Abi)." }
    }
} finally {
    Pop-Location
}
