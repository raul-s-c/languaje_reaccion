"""Small Windows executable launching the installed local processing environment."""
from pathlib import Path
import subprocess
import sys
import ctypes
import os
import winreg


def main():
    repo = Path(sys.executable).resolve().parent.parent
    python = repo / '.tools/desktop-venv/Scripts/python.exe'
    app = repo / 'desktop/app.py'
    if not python.exists() or not app.exists():
        ctypes.windll.user32.MessageBoxW(None,
            'Mantén este ejecutable dentro de la carpeta pc del proyecto. Se necesita el entorno .tools/desktop-venv instalado.',
            'Lengua Reacción', 16)
        return
    env = os.environ.copy()
    for hive, key in [(winreg.HKEY_CURRENT_USER, 'Environment'),
                      (winreg.HKEY_LOCAL_MACHINE, r'SYSTEM\CurrentControlSet\Control\Session Manager\Environment')]:
        try:
            with winreg.OpenKey(hive, key) as handle:
                value = winreg.QueryValueEx(handle, 'Path')[0]
                env['PATH'] = env.get('PATH', '') + ';' + os.path.expandvars(value)
        except OSError:
            pass
    logs = Path(os.environ.get('LOCALAPPDATA', str(repo))) / 'LenguaReaccion'
    logs.mkdir(parents=True, exist_ok=True)
    with (logs / 'desktop.log').open('a', encoding='utf-8') as output:
        subprocess.Popen([str(python), str(app)], cwd=str(repo), env=env, stdout=output, stderr=output,
                         creationflags=subprocess.CREATE_NO_WINDOW)


if __name__ == '__main__':
    main()
