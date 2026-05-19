param(
    [string]$AppVersion = "1.0.0",
    [string]$JPackagePath = "",
    [switch]$WinConsole
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetDir = Join-Path $projectRoot "target"
$jpackageWorkDir = Join-Path $targetDir "jpackage"
$inputDir = Join-Path $jpackageWorkDir "input"
$contentDir = Join-Path $jpackageWorkDir "content"
$distDir = Join-Path $targetDir "dist"
$appImageDir = Join-Path $distDir "MuaSamCong Downloader"
$packagingContentDir = Join-Path $projectRoot "packaging\app-content"

Write-Host "[1/4] Building JAR..."
& (Join-Path $projectRoot "mvnw.cmd") package -DskipTests
if ($LASTEXITCODE -ne 0) {
    throw "Maven package failed. Close running app and retry."
}

if (Test-Path $jpackageWorkDir) {
    Remove-Item $jpackageWorkDir -Recurse -Force
}
New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
New-Item -ItemType Directory -Path $contentDir -Force | Out-Null
New-Item -ItemType Directory -Path $distDir -Force | Out-Null

if (Test-Path $appImageDir) {
    Write-Host "Removing existing app-image directory..."
    Remove-Item $appImageDir -Recurse -Force
}

Write-Host "[2/4] Preparing application payload..."
& (Join-Path $projectRoot "mvnw.cmd") dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$inputDir"
if ($LASTEXITCODE -ne 0) {
    throw "Maven dependency copy failed"
}

$mainJar = Get-ChildItem -Path $targetDir -Filter "pdf-downloader-*.jar" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $mainJar) {
    throw "Cannot find main jar in $targetDir"
}

Copy-Item -Path $mainJar.FullName -Destination $inputDir -Force

if (-not (Test-Path $packagingContentDir)) {
    throw "Missing packaged config directory: $packagingContentDir"
}
Copy-Item -Path (Join-Path $packagingContentDir "*") -Destination $contentDir -Recurse -Force

function Resolve-JPackage {
    param([string]$ExplicitPath)

    if ($ExplicitPath -and (Test-Path $ExplicitPath)) {
        return (Resolve-Path $ExplicitPath).Path
    }

    $cmd = Get-Command jpackage -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    $jdkCandidates = Get-ChildItem -Path "C:\Program Files\Java" -Filter "jdk*" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending

    foreach ($jdk in $jdkCandidates) {
        $candidate = Join-Path $jdk.FullName "bin\jpackage.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "Cannot find jpackage.exe. Install full JDK 21+ or pass -JPackagePath"
}

$jpackageExe = Resolve-JPackage -ExplicitPath $JPackagePath

Write-Host "[3/4] Building Windows app-image (.exe launcher)..."
$jpackageArgs = @(
    "--type", "app-image",
    "--dest", $distDir,
    "--name", "MuaSamCong Downloader",
    "--input", $inputDir,
    "--main-jar", $mainJar.Name,
    "--main-class", "vn.muasamcong.downloader.app.DownloaderFxApp",
    "--java-options", "--module-path `$APPDIR",
    "--java-options", "--add-modules=javafx.controls",
    "--app-version", $AppVersion,
    "--vendor", "MuaSamCong",
    "--app-content", $contentDir
)

if ($WinConsole) {
    $jpackageArgs += "--win-console"
}
& $jpackageExe @jpackageArgs

$exePath = Join-Path $appImageDir "MuaSamCong Downloader.exe"

Write-Host "[4/4] Done"
Write-Host "Launcher: $exePath"
Write-Host "Ship folder: $appImageDir"
