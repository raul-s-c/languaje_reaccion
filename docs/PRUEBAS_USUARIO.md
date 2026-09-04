# Pasos que necesitaré del usuario

No compartas por chat ni la clave de OpenAI ni un token de Plex. Ambos deben introducirse únicamente en la aplicación.

## Prueba mínima al volver

1. Desbloquear la Xiaomi Pad 7 Pro, mantenerla en la misma Wi-Fi que el PC y abrir **Lengua Reacción**.
2. En HyperOS, abrir la información de la app → **Ahorro de batería** → **Sin restricciones**. Permitir notificaciones si Android lo solicita; la notificación mantiene viva una transcripción larga.
3. Pulsar **Buscar actualización** e instalar la versión más reciente. Android pedirá una confirmación visible.
4. Elegir un vídeo japonés corto y claro, idealmente de 30 segundos a 3 minutos, mediante **Abrir vídeo**.
5. Elegir **Equilibrado (Base, 60 MB)**, descargarlo y pulsar **Generar subtítulos**. Hacer la primera prueba con la tablet conectada al cargador.
6. Anotar duración del vídeo, tiempo total de transcripción y cualquier frase claramente incorrecta. Una captura basta; no hace falta enviar el vídeo si es privado.
7. En **Configurar clave OpenAI**, pegar la clave directamente en la tablet y pulsar **Corregir y traducir con GPT-5.4 Mini**. Confirmar si japonés, lectura y español son razonables.
8. Tocar varias palabras bajo el subtítulo y comprobar lectura, forma de diccionario y categoría gramatical.
9. Probar **Compartir SRT** o **Compartir VTT** y confirmar que el archivo incluye japonés, lectura y español.

## Plex

Para una primera prueba puede pulsarse **Plex/URL** y pegar una URL directa reproducible. La app no persiste esa URL porque podría contener `X-Plex-Token`.

Para construir el navegador de biblioteca necesito, sin revelar credenciales:

- confirmar si Plex funciona solo en la red local o también fuera de casa;
- indicar la URL base visible desde la tablet, por ejemplo `http://192.168.1.x:32400`;
- introducir el token posteriormente en un formulario cifrado que añadiré a la app, nunca en GitHub ni en este chat;
- indicar qué biblioteca de Plex contiene los vídeos japoneses.

## Conexión de desarrollo inalámbrica

El puerto de depuración cambia al desactivar o reactivar **Depuración inalámbrica**. Si se desea una sesión ADB nueva, necesito que la pantalla permanezca desbloqueada y estos dos datos mientras siguen visibles:

- **Dirección IP y puerto** de la pantalla principal de Depuración inalámbrica, para conectar.
- Solo si se ha perdido el emparejamiento: **IP y puerto de emparejamiento** y el código temporal de seis dígitos.

No hace falta conectar la tablet por USB. HyperOS puede pedir en pantalla permiso para instalar la APK auxiliar de pruebas; esa confirmación no puede ni debe automatizarse.

## Decisiones posteriores

- Elegir si priorizar velocidad (Base) o fidelidad (Small/Large procesado en el PC).
- Decidir si el siguiente hito debe ser navegador Plex, editor de subtítulos o vocabulario/SRS.
- Facilitar 2–3 ejemplos cortos de errores recurrentes (nombres propios, habla rápida, música) para ajustar la segmentación y los parámetros de Whisper.
