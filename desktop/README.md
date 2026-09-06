# Procesador local: prototipo v1

Windows, Python 3.11, FFmpeg y FFprobe en PATH.

Desde la raíz del repositorio:

```powershell
python -m venv .tools/desktop-venv
.tools/desktop-venv/Scripts/python.exe -m pip install -r desktop/requirements.txt
.\scripts\start-desktop.ps1
```

Añadir una carpeta de serie y elegir como destino la carpeta padre de series.
Ejemplo: entrada `doblados/sakamoto`, destino `doblados`.
El programa genera `doblados/sakamoto/NombreDelVideo.lrpack`.
No copia el vídeo; para la prueba Android deben abrirse el vídeo original y su paquete.

CUDA requiere cuBLAS CUDA 12 y cuDNN 9 accesibles en PATH, además del controlador NVIDIA.
El primer uso descarga los modelos; posteriores ejecuciones reutilizan la caché local.
Whisper y NLLB usan CTranslate2 y GPU si se elige cuda, cargados por etapas.

En Android: abrir el vídeo, pulsar «Importar paquete PC» y elegir el lrpack
desde el selector de archivos, incluyendo OneDrive si su proveedor está disponible.
La asociación con el vídeo es manual. Comprobar que ambos nombres correspondan.

Se guardan puntos de recuperación al terminar la transcripción y después de cada
traducción. Una transcripción interrumpida se reinicia. Cerrar la ventana interrumpe
el trabajo; se conservan los puntos de recuperación terminados.

El prototipo no incluye aún biblioteca por episodios, historial de palabras/frases,
diccionario de significados, sincronización automática ni explicaciones conceptuales.
Los datos morfológicos se guardan en el paquete; Android calcula actualmente sus
fichas con IPADIC. Traducción automática local pendiente de evaluar con anime.

Formato: ZIP con `study.json` (formatVersion 1) y `study.sha256`.
El hash comprueba integridad, no autentica la procedencia.
Los subtítulos embebidos en el vídeo no se utilizan.
