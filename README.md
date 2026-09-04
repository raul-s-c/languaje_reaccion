# Lengua Reacción

Aplicación Android personal para aprender japonés a partir de vídeos propios: reproducción local o desde Plex, subtítulos japoneses generados, traducción al español, furigana, diccionario y repaso.

## Instalar en la tablet

### [⬇ Descargar la APK más reciente](https://github.com/raul-s-c/languaje_reaccion/raw/refs/heads/main/apk/lengua-reaccion.apk)

En la primera instalación, Android solicitará permiso para instalar una aplicación descargada fuera de Google Play. A partir de entonces, usa **Buscar actualización** en la cabecera de la propia aplicación. La app comprobará `updates/latest.json`, descargará la APK, validará su SHA-256, limpiará cachés prescindibles y abrirá el instalador de Android.

> Android siempre exige una confirmación visible para actualizar una APK fuera de Google Play. La aplicación no intenta eludir esa protección.

## Estado de la versión 0.1.0

- Reproductor de vídeos locales con Media3.
- Diseño adaptativo para tablet y móvil.
- Comprobación y descarga de actualizaciones desde este repositorio.
- Verificación SHA-256 antes de instalar.
- Limpieza de caché antes de descargar una actualización.
- Estructura visual inicial japonés → español.
- Preparada para incorporar Plex y el servicio de transcripción del PC.

## Desarrollo local

Requisitos:

- JDK 17 o posterior.
- Android SDK 36.
- Gradle 8.14.5 o el wrapper del proyecto.

Compilar y actualizar la APK pública:

```powershell
.\scripts\package-apk.ps1
```

Al crear una versión nueva se deben actualizar también `versionCode` y `versionName` en `app/build.gradle.kts`. La firma de esta fase es la clave de depuración estable del PC de desarrollo. Antes de almacenar datos importantes o distribuir la app se migrará una única vez a una clave privada de publicación respaldada.

## Próximos hitos

1. Conexión y catálogo de Plex Media Server.
2. Servicio complementario en el PC y cola de procesamiento.
3. Transcripción japonesa y editor de sincronización.
4. Traducción contextual al español.
5. Furigana, JMdict y análisis morfológico.
6. Guardado de vocabulario y repetición espaciada.
