# Lengua Reacción

Aplicación Android personal para estudiar japonés con vídeos propios. Reproduce el vídeo, extrae su audio en la tablet, genera subtítulos japoneses con Whisper local y, de forma opcional, usa GPT-5.4 Mini para corregir el reconocimiento, traducirlo al español y producir su lectura.

## Instalar

### [⬇ Descargar la APK más reciente](https://github.com/raul-s-c/languaje_reaccion/raw/refs/heads/main/apk/lengua-reaccion.apk)

La primera instalación necesita permitir APK de esta procedencia. Después puede usarse **Buscar actualización** dentro de la app. Cada descarga se valida con SHA-256 y Android exige una confirmación visible antes de instalarla.

El diseño del procesador por lotes para PC, los paquetes de estudio, OneDrive y el historial personal está documentado en [docs/REQUISITOS_PC_TABLET.md](docs/REQUISITOS_PC_TABLET.md).

## Funciones de la versión 0.3.0

- Prototipo Windows para preparar vídeos por lotes con Whisper y traducción local: [instrucciones](desktop/README.md).
- Importación manual de paquetes `.lrpack` para el vídeo abierto y reanudación de posición por URI.
- Biblioteca automática, historial lingüístico y explicaciones bajo demanda: pendientes.

- Reproductor Media3 para vídeos locales y enlaces HTTP/HTTPS directos, incluidos enlaces de reproducción de Plex.
- Extracción y conversión del audio a PCM mono de 16 kHz dentro de Android.
- Whisper.cpp nativo ARM64 con cuatro modelos descargables: Tiny, Base, Small y Large v3 Turbo cuantizados.
- Base como opción recomendada para la Xiaomi Pad 7 Pro y Tiny para pruebas rápidas.
- Descargas de modelos verificadas con SHA-256 y reanudables tras una interrupción.
- Procesamiento en primer plano y bloqueo parcial de suspensión para HyperOS.
- Detección de silencio previa a Whisper para ahorrar tiempo y reducir alucinaciones.
- Subtítulo sincronizado con el reproductor.
- Segmentación, lecturas, forma de diccionario y categoría gramatical sin conexión mediante Kuromoji/IPADIC.
- Corrección contextual, traducción al español y lectura completa mediante la Responses API y `gpt-5.4-mini`.
- Clave de OpenAI cifrada con Android Keystore; `store: false`; nunca se incluye en la APK ni en registros.
- Exportación y uso compartido en SRT o WebVTT.
- Actualizador interno, validación de integridad y limpieza de cachés prescindibles.
- Recuperación de cierres inesperados.

El vídeo y el audio temporal nunca se envían a OpenAI. Solo se envían los segmentos japoneses cuando el usuario pulsa expresamente **Corregir y traducir con GPT-5.4 Mini**. Una URL de Plex, que puede contener un token, se conserva únicamente durante la sesión actual y no se escribe en preferencias.

## Primeras pruebas

Las instrucciones exactas y la información que necesito del usuario están en [docs/PRUEBAS_USUARIO.md](docs/PRUEBAS_USUARIO.md).

## Desarrollo

Requisitos: JDK 17, Android SDK 36, NDK 27.0.12077973 y Gradle 8.14.5. Whisper.cpp se incorpora como submódulo:

```powershell
git clone --recurse-submodules https://github.com/raul-s-c/languaje_reaccion.git
cd languaje_reaccion
.\scripts\package-apk.ps1
```

Al crear una versión nueva hay que incrementar `versionCode` y `versionName` en `app/build.gradle.kts`. `scripts/package-apk.ps1` construye `apk/lengua-reaccion.apk` y actualiza `updates/latest.json` con el SHA-256 real. La clave estable de firma vive en `.signing/`, está excluida de Git y debe conservarse de forma privada.

## Arquitectura

- `app/`: interfaz Compose, Media3, audio, persistencia, exportación, OpenAI y actualizador.
- `whisperlib/`: puente Kotlin/JNI y compilación ARM64.
- `third_party/whisper.cpp`: submódulo oficial del motor.
- `updates/latest.json`: manifiesto consultado por el actualizador.
- `apk/lengua-reaccion.apk`: APK pública y estable.

## Pendiente para alcanzar la experiencia completa

- Navegador de bibliotecas Plex con autenticación cifrada, en vez de pegar un enlace directo.
- Editor visual de tiempos y texto por segmento.
- Diccionario JMdict japonés-español completo y guardado de vocabulario.
- Repetición espaciada, estadísticas y exportación a Anki.
- Modos de estudio como pausa automática, repetición de frase y ocultación de subtítulos.
- Pruebas largas de velocidad y calidad en la Xiaomi con audio japonés real.
