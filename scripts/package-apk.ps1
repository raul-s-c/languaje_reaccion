$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdkPath = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$gradle = Join-Path $repoRoot "gradlew.bat"
$buildRoot = Join-Path $env:TEMP "lengua-reaccion-build"

if (-not (Test-Path $gradle)) {
    throw "No se encontró el wrapper de Gradle."
}

$env:ANDROID_HOME = $sdkPath
$env:ANDROID_SDK_ROOT = $sdkPath
$env:LENGUA_REACCION_BUILD_DIR = $buildRoot
& $gradle -p $repoRoot assembleDebug
if ($LASTEXITCODE -ne 0) { throw "La compilación Android ha fallado." }

$source = Join-Path $buildRoot "app\outputs\apk\debug\app-debug.apk"
$metadataPath = Join-Path $buildRoot "app\outputs\apk\debug\output-metadata.json"
$metadata = Get-Content -Raw $metadataPath | ConvertFrom-Json
$VersionCode = [int]$metadata.elements[0].versionCode
$VersionName = [string]$metadata.elements[0].versionName
$apkDirectory = Join-Path $repoRoot "apk"
$target = Join-Path $apkDirectory "lengua-reaccion.apk"
New-Item -ItemType Directory -Force -Path $apkDirectory | Out-Null
Copy-Item -Force $source $target

$hash = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
$manifestPath = Join-Path $repoRoot "updates\latest.json"
$releaseNotes = "Versión $VersionName de Lengua Reacción."
if (Test-Path $manifestPath) {
    $existingManifest = Get-Content -Raw $manifestPath | ConvertFrom-Json
    if ($existingManifest.versionName -eq $VersionName -and $existingManifest.notes) {
        $releaseNotes = [string]$existingManifest.notes
    }
}
$manifest = [ordered]@{
    versionCode = $VersionCode
    versionName = $VersionName
    # Keep the APK itself on the raw endpoint so clients older than 0.2.1 receive binary data.
    apkUrl = "https://raw.githubusercontent.com/raul-s-c/languaje_reaccion/main/apk/lengua-reaccion.apk"
    sha256 = $hash
    notes = $releaseNotes
}
$manifest | ConvertTo-Json | Set-Content -Encoding UTF8 $manifestPath

Write-Host "APK preparada: $target"
Write-Host "Versión: $VersionName ($VersionCode)"
Write-Host "SHA-256: $hash"
