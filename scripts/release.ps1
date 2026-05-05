param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$JPackagePath = "",
    [string]$Repo = "",
    [switch]$SkipVersionBump,
    [switch]$SkipBuild,
    [switch]$SkipZip,
    [switch]$GenerateLatestJson
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($Version -notmatch '^\d+\.\d+\.\d+([-.][0-9A-Za-z.]+)?$') {
    throw "Invalid version '$Version'. Expected semver-like format, e.g. 1.0.1"
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$pomPath = Join-Path $projectRoot "pom.xml"
$appInfoPath = Join-Path $projectRoot "src\main\java\vn\muasamcong\downloader\app\AppInfo.java"
$distDir = Join-Path $projectRoot "target\dist"
$appFolder = Join-Path $distDir "MuaSamCong Downloader"
$zipName = "MuaSamCong-Downloader-$Version-win.zip"
$zipPath = Join-Path $distDir $zipName
$latestOutPath = Join-Path $projectRoot "packaging\latest.generated.json"

function Replace-FileContent {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][scriptblock]$Transform
    )

    if (-not (Test-Path $Path)) {
        throw "Missing file: $Path"
    }

    $old = Get-Content -Path $Path -Raw -Encoding UTF8
    $new = & $Transform $old

    if ($null -eq $new -or $new -eq $old) {
        return $false
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $new, $utf8NoBom)
    return $true
}

if (-not $SkipVersionBump) {
    Write-Host "[1/4] Updating versions..."

    $updatedPom = Replace-FileContent -Path $pomPath -Transform {
        param($text)
        $pattern = '(?s)(<artifactId>pdf-downloader</artifactId>\s*<version>)([^<]+)(</version>)'
        if ($text -notmatch $pattern) {
            throw "Cannot locate project version in pom.xml"
        }
        $replacement = {
            param($m)
            return $m.Groups[1].Value + $Version + $m.Groups[3].Value
        }
        return [regex]::Replace($text, $pattern, $replacement, 1)
    }

    $updatedAppInfo = Replace-FileContent -Path $appInfoPath -Transform {
        param($text)
        $pattern = 'public static final String VERSION = "[^"]+";'
        if ($text -notmatch $pattern) {
            throw "Cannot locate VERSION constant in AppInfo.java"
        }
        return [regex]::Replace($text, $pattern, "public static final String VERSION = `"$Version`";", 1)
    }

    if ($updatedPom -or $updatedAppInfo) {
        Write-Host "Updated version to $Version in source files"
    }
    else {
        Write-Host "Version already at $Version"
    }
}
else {
    Write-Host "[1/4] Skipping version update"
}

if (-not $SkipBuild) {
    Write-Host "[2/4] Building app-image..."
    $buildScript = Join-Path $projectRoot "scripts\build-exe.ps1"
    if (-not (Test-Path $buildScript)) {
        throw "Missing build script: $buildScript"
    }

    $buildArgs = @("-ExecutionPolicy", "Bypass", "-File", $buildScript, "-AppVersion", $Version)
    if ($JPackagePath) {
        $buildArgs += @("-JPackagePath", $JPackagePath)
    }

    & powershell @buildArgs
    if (-not $?) {
        throw "Build failed"
    }
}
else {
    Write-Host "[2/4] Skipping build"
}

if (-not $SkipZip) {
    Write-Host "[3/4] Creating release zip..."
    if (-not (Test-Path $appFolder)) {
        throw "App folder not found: $appFolder"
    }

    if (-not (Test-Path $distDir)) {
        New-Item -ItemType Directory -Path $distDir -Force | Out-Null
    }

    Compress-Archive -Path (Join-Path $appFolder "*") -DestinationPath $zipPath -Force
    Write-Host "Created: $zipPath"
}
else {
    Write-Host "[3/4] Skipping zip"
}

if ($GenerateLatestJson) {
    Write-Host "[4/4] Generating latest.generated.json..."

    $url = "https://github.com/your-org/your-repo/releases/download/v$Version/$zipName"
    if ($Repo) {
        $url = "https://github.com/$Repo/releases/download/v$Version/$zipName"
    }

    $releaseDate = Get-Date -Format "yyyy-MM-dd"
    $json = @{
        version = $Version
        releaseDate = $releaseDate
        downloadUrl = $url
        fileName = $zipName
        notes = @("Update release $Version")
    } | ConvertTo-Json -Depth 4

    Set-Content -Path $latestOutPath -Value $json -Encoding UTF8
    Write-Host "Created: $latestOutPath"
}
else {
    Write-Host "[4/4] Skip latest json generation"
}

Write-Host "Done."
Write-Host "- App folder: $appFolder"
if (-not $SkipZip) {
    Write-Host "- Zip file  : $zipPath"
}
