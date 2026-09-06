$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$env:Path = @($env:Path, [Environment]::GetEnvironmentVariable('Path','Machine'), [Environment]::GetEnvironmentVariable('Path','User')) -join ';'
$python = Join-Path $repo '.tools\desktop-venv\Scripts\python.exe'
if (-not (Test-Path $python)) { throw 'Prepara primero el entorno siguiendo desktop/README.md' }
& $python (Join-Path $repo 'desktop\app.py')
