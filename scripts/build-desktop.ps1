$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
& "$repo\.tools\desktop-venv\Scripts\python.exe" -m PyInstaller --noconfirm --onefile --windowed --name LenguaReaccion --distpath "$repo\pc" --workpath "$repo\.tools\pyinstaller" --specpath "$repo\.tools" "$repo\desktop\launcher.py"
if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar el ejecutable' }
