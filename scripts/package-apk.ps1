$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdkPath = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$gradle = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path $gradle)) {
    throw "No se encontró el wrapper de Gradle."
}

$env:ANDROID_HOME = $sdkPath
$env:ANDROID_SDK_ROOT = $sdkPath
& $gradle -p $repoRoot clean assembleDebug
if ($LASTEXITCODE -ne 0) { throw "La compilación Android ha fallado." }

$source = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$metadataPath = Join-Path $repoRoot "app\build\outputs\apk\debug\output-metadata.json"
$metadata = Get-Content -Raw $metadataPath | ConvertFrom-Json
$VersionCode = [int]$metadata.elements[0].versionCode
$VersionName = [string]$metadata.elements[0].versionName
$apkDirectory = Join-Path $repoRoot "apk"
$target = Join-Path $apkDirectory "lengua-reaccion.apk"
New-Item -ItemType Directory -Force -Path $apkDirectory | Out-Null
Copy-Item -Force $source $target

$hash = (Get-FileHash -Algorithm SHA256 $target).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    versionCode = $VersionCode
    versionName = $VersionName
    apkUrl = "https://raw.githubusercontent.com/raul-s-c/languaje_reaccion/main/apk/lengua-reaccion.apk"
    sha256 = $hash
    notes = "Versión $VersionName de Lengua Reacción."
}
$manifest | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $repoRoot "updates\latest.json")

Write-Host "APK preparada: $target"
Write-Host "Versión: $VersionName ($VersionCode)"
Write-Host "SHA-256: $hash"
