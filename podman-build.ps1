# PowerShell helper for building BobrDobr VPN Mobile inside Podman
[CmdletBinding()]
param(
    [switch]$RebuildImage,
    [string]$Task = "assembleDebug",
    [string]$Command = "",
    [switch]$ForceRebuildNative
)

$ErrorActionPreference = "Stop"

$ImageName = "bobrdobr-android-builder"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host ">>> Checking Podman build image: $ImageName..." -ForegroundColor Cyan

# Determine if image exists
$imageExists = $false
try {
    $inspect = podman image inspect $ImageName 2>$null
    if ($LASTEXITCODE -eq 0 -and $inspect) {
        $imageExists = $true
    }
} catch {
    $imageExists = $false
}

if (-not $imageExists -or $RebuildImage) {
    Write-Host ">>> Building Podman build environment image ($ImageName)..." -ForegroundColor Yellow
    podman build -t $ImageName -f "$ScriptDir/build-container/Containerfile" "$ScriptDir/build-container"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to build Podman image."
        exit 1
    }
}

$execCommand = if ($Command) { $Command } else { "/workspace/build-container/build.sh $Task" }

Write-Host ">>> Running build inside Podman container (task: $Task)..." -ForegroundColor Green

# Use interactive terminal only if attached to console
$ttyFlag = if ([System.Environment]::UserInteractive -and -not [System.Console]::IsInputRedirected) { "-it" } else { "-i" }

$envArgs = @()
if ($ForceRebuildNative -or $env:FORCE_REBUILD_NATIVE -eq "1") {
    $envArgs += @("-e", "FORCE_REBUILD_NATIVE=1")
    Write-Host ">>> Force rebuild native libraries enabled (FORCE_REBUILD_NATIVE=1)" -ForegroundColor Yellow
}

podman run --rm $ttyFlag `
    @envArgs `
    -v "${ScriptDir}:/workspace:z" `
    -w /workspace `
    $ImageName `
    bash -c $execCommand

if ($LASTEXITCODE -eq 0) {
    Write-Host ">>> Build completed successfully in Podman!" -ForegroundColor Green
} else {
    Write-Error "Build failed with exit code $LASTEXITCODE."
}
