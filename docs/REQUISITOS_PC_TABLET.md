# Requisitos: procesador de episodios para PC y reproductor de estudio Android

Estado: borrador de trabajo para validar antes de implementar.

## Decisiones confirmadas el 6 de septiembre de 2026

- PC: Windows 11 Pro, Ryzen 7 5800X (8 núcleos/16 hilos), 16 GB RAM, RTX 3070 con 8 GB de VRAM confirmados por nvidia-smi. El dato WMI de 4 GB no se utilizará.
- Python 3.11.9 y FFmpeg 8.1 disponibles en el PC.
- Destino: `C:\Users\rauls\OneDrive\cosas\doblados`.
- Organización: una carpeta por serie, sin subcarpetas de temporadas; conservar identificadores de temporada/episodio en el nombre.
- Primera prueba con transcripción y traducción locales. Las explicaciones conceptuales siguen siendo exclusivamente bajo demanda.
- Vídeo de prueba localizado en `doblados\sakamoto`: Sakamoto Days S01E03 V2.
- Inspección real con FFprobe: 1503,68 segundos (25 min 3,68 s), vídeo HEVC 1920×1080; pista 1 japonesa E-AC-3 de 6 canales y pista 2 inglesa E-AC-3 de 6 canales.
- El archivo también contiene subtítulos embebidos; la prueba generará su transcripción desde la pista japonesa, sin utilizarlos.
- Perfil inicial propuesto: faster-whisper large-v3, CUDA, int8_float16; un episodio a la vez. Medir velocidad y memoria antes de fijar expectativas.
- Traducción local candidata: NLLB-200 distilled 600M, pendiente de validar calidad japonés–español con diálogos reales. Descargar los modelos inicialmente requiere Internet.
- Cargar transcriptor y traductor por etapas para limitar memoria. No cargar ambos modelos GPU simultáneamente por defecto.
- Guardar inicialmente vídeo y paquete como archivos hermanos dentro de la carpeta de serie, evitando duplicar el vídeo dentro del ZIP. Esta es una decisión de implementación revisable.
- Mantener modelos y temporales fuera de OneDrive; escribir únicamente resultados terminados en el destino sincronizado.
- La importación desde OneDrive debe verificarse en la tablet: el acceso a archivos individuales no garantiza que su proveedor Android permita autorizar o recorrer una carpeta completa. No dar por probada esa integración hasta ejecutar la prueba.
- Prototipo 0.3.0: procesador Windows y formato lrpack v1 implementados; importación manual sobre el vídeo abierto y posición por URI. Biblioteca, asociación automática, historial lingüístico y explicaciones conceptuales pendientes.
- Prueba real: Sakamoto S01E03 procesado con Large v3 CUDA y NLLB local; 305 segmentos, paquete de aproximadamente 34 KB. Integridad y estructura validadas; fidelidad lingüística todavía requiere revisión.

## 1. Objetivo

Crear un sistema personal para estudiar japonés con vídeos propios, compuesto por:

1. Un único programa para Windows que procese por lotes vídeos o carpetas completas.
2. Un formato de paquete por episodio que contenga todos los datos de estudio reutilizables.
3. La aplicación Android de la Xiaomi Pad 7 Pro, capaz de importar esos paquetes, reproducir el vídeo y ofrecer interacción tipo Language Reactor.
4. Explicaciones conceptuales mediante GPT-5.4 Mini únicamente cuando el usuario las solicite.

El procesamiento previo no debe pedir explicaciones conceptuales a GPT. La transcripción, sincronización, traducción, segmentación, furigana y datos de diccionario deben quedar guardados para no repetir trabajo.

## 2. Flujo principal

1. El usuario abre el programa de Windows.
2. Añade uno o varios vídeos, o selecciona una carpeta y opcionalmente sus subcarpetas.
3. Selecciona una carpeta de destino, idealmente dentro de la carpeta sincronizada de OneDrive.
4. El programa muestra la cola, detecta archivos ya procesados y permite iniciar, pausar, reintentar o cancelar trabajos.
5. Por cada vídeo genera un paquete autocontenido de estudio (`.lrpack`) y un informe de posibles incidencias.
6. OneDrive sincroniza los paquetes.
7. En Android, el usuario concede acceso a la carpeta mediante el selector de archivos del sistema.
8. La aplicación importa o descarga el paquete a una caché local, lo indexa y lo asocia con el vídeo local, con el vídeo incluido o con una fuente Plex.
9. Al reproducir, la aplicación registra progreso, frases mostradas y vocabulario encontrado.
10. Solo al pulsar **Explicar** se envía a GPT la palabra o frase seleccionada, junto con el contexto mínimo necesario.

## 3. Programa de Windows

### 3.1 Entrada y cola

- Admitir selección múltiple de archivos.
- Admitir una carpeta completa y búsqueda recursiva opcional.
- Reconocer al menos MKV, MP4, M4V, WEBM y AVI.
- Mostrar nombre, duración, tamaño, idioma/pistas detectadas, estado y progreso.
- Permitir ordenar la cola y establecer prioridad.
- Omitir archivos ya procesados cuando su huella no haya cambiado.
- Poder reanudar después de cerrar el programa o reiniciar Windows.
- No perder los resultados válidos si un episodio de la cola falla.
- Mantener un registro legible de errores por archivo.

### 3.2 Configuración del procesamiento

- Carpeta de salida configurable.
- Perfil de calidad: rápido, equilibrado y máxima precisión.
- Selección automática de la pista japonesa, con elección manual disponible.
- Opción de incluir el vídeo en el paquete o mantenerlo externo.
- Opción de copiar/remultiplexar el vídeo sin recodificar.
- Opción de generar un vídeo compatible con Android cuando el códec original no lo sea.
- Configuración de CPU/GPU, concurrencia y límite de uso de recursos.
- Apagado o suspensión opcional al terminar una cola larga.

### 3.3 Procesamiento multimedia

- Usar FFmpeg para inspeccionar, extraer, convertir o remultiplexar audio y vídeo.
- Aceptar códecs habituales de anime, incluidos AAC, Opus, FLAC, AC-3, E-AC-3 y DTS.
- Convertir el audio de trabajo al formato requerido por el transcriptor sin degradar el vídeo.
- Detectar silencios para mejorar los cortes y reducir trabajo innecesario.
- Conservar la duración y los tiempos originales con precisión suficiente para navegar por frases.
- Generar miniaturas o fotogramas bajo demanda para tarjetas de estudio.
- Generar clips de audio bajo demanda o durante la exportación a Anki.

### 3.4 Transcripción japonesa

- Ejecutarse localmente en el PC; el audio no debe enviarse a OpenAI.
- Usar un modelo de Whisper configurable, priorizando máxima precisión en japonés.
- Procesar vídeos largos por segmentos con solapamiento y deduplicación.
- Incluir marcas de tiempo por frase y, cuando sean fiables, por palabra.
- Aplicar VAD y reconstrucción de frases para evitar cortes antinaturales.
- Detectar y marcar segmentos de baja confianza.
- Permitir editar el japonés y reajustar los límites temporales antes o después de exportar.
- Conservar tanto el texto original del transcriptor como la versión corregida.

### 3.5 Preparación lingüística embebida

- Segmentar cada frase japonesa en tokens.
- Guardar forma superficial, lema, lectura, pronunciación y categoría gramatical.
- Generar furigana por palabra conservando kana, puntuación y espacios visuales.
- Detectar conjugaciones y enlazarlas con su forma de diccionario.
- Resolver expresiones multipalabra conocidas cuando sea posible.
- Incorporar significados de diccionario sin consumir la API.
- Incluir datos de JLPT y frecuencia cuando exista una fuente con licencia compatible.
- Identificar nombres propios y marcar resultados ambiguos.
- Generar y guardar una traducción española por frase antes de importar el paquete.
- Permitir corregir manualmente traducción, tokenización y furigana.

### 3.6 Uso de OpenAI durante la preparación

- No generar explicaciones conceptuales automáticamente.
- No enviar vídeo ni audio.
- La traducción previa debe tener un motor configurable: local o GPT-5.4 Mini.
- Si se usa GPT para traducir, enviar frases por lotes y guardar el resultado definitivamente.
- Utilizar respuestas estructuradas y validar el esquema antes de aceptar resultados.
- Mostrar una estimación y un resumen del consumo de tokens.
- No guardar la clave de OpenAI dentro de ningún `.lrpack`.

## 4. Formato `.lrpack`

### 4.1 Propiedades

- Un paquete por vídeo o episodio.
- Formato versionado y documentado.
- Importación atómica: un paquete incompleto no debe aparecer en la biblioteca.
- Integridad comprobada mediante hashes.
- Compatible hacia atrás cuando evolucione el formato.
- Compresión ZIP o equivalente, legible sin herramientas propietarias.
- Identificador estable del episodio independiente del nombre del archivo.

### 4.2 Contenido mínimo

- Manifiesto con versión de formato, identificador, título, serie, episodio y duración.
- Huellas del vídeo y audio utilizados.
- Referencia opcional a ruta relativa, Plex o vídeo incluido.
- Subtítulos japoneses con tiempos.
- Traducción española con tiempos y relación uno a uno o muchos a uno.
- Tokens, lemas, lecturas, furigana, categorías y entradas de diccionario.
- Indicadores de confianza y de corrección manual.
- Metadatos de los modelos y diccionarios usados.
- Miniatura de portada opcional.

### 4.3 Datos que no deben incluirse

- Clave de OpenAI.
- Token o credenciales de Plex.
- Historial personal de reproducción.
- Palabras guardadas y estadísticas personales.

Los paquetes de contenido serán inmutables. El progreso personal se guardará en una base de datos separada para evitar perderlo al reimportar o regenerar un episodio.

## 5. Integración con OneDrive

### 5.1 Primera versión recomendada

- El programa de Windows escribe en una carpeta local sincronizada por OneDrive.
- Android usa el Storage Access Framework para que el usuario elija esa carpeta desde el proveedor de OneDrive.
- La aplicación conserva el permiso cuando Android y el proveedor lo permitan.
- Los paquetes se descargan o copian a almacenamiento local antes de reproducirse para evitar cortes de red.
- La biblioteca permite actualizar su índice manualmente y al abrir la aplicación.
- Mostrar claramente los estados: solo nube, descargando, disponible localmente y desactualizado.
- Permitir eliminar la copia local sin borrar el original de OneDrive.

### 5.2 Evolución opcional

- Sincronización directa con Microsoft Graph si el selector de documentos no ofrece fiabilidad suficiente.
- Descarga automática de nuevos paquetes en Wi-Fi.
- Copia de seguridad cifrada del perfil y del progreso en OneDrive.
- Resolución de conflictos si se usa más de una tablet.

## 6. Biblioteca y reproductor Android

### 6.1 Biblioteca

- Agrupar por serie y ordenar episodios numéricamente.
- Mostrar portada, título, duración, progreso y fecha de última reproducción.
- Estados: no empezado, en curso y terminado.
- Continuar automáticamente desde el último episodio y posición.
- Buscar por serie, episodio, palabra o frase.
- Filtrar descargados, pendientes, vistos y favoritos.
- Detectar paquetes nuevos o actualizados en la carpeta autorizada.
- Asociar manualmente un paquete con un vídeo si la detección automática falla.
- Admitir vídeo local, vídeo incluido y reproducción mediante Plex.

### 6.2 Reproductor interactivo

- Subtítulos simultáneos en japonés y español.
- Furigana siempre visible, al tocar, o desactivado.
- Traducción visible, oculta o revelada temporalmente.
- Lista desplazable de subtítulos sincronizada con la reproducción.
- Pulsar una frase para saltar a su inicio.
- Repetir frase, anterior, siguiente, reproducir/pausar y cambiar velocidad.
- Autopausa al final de cada frase.
- Repetición configurable de una frase o intervalo.
- Ocultar la próxima frase para practicar escucha.
- Seleccionar una palabra o una expresión de varias palabras.
- Modo horizontal y pantalla completa manteniendo subtítulos interactivos.
- Controles táctiles adaptados a la tablet.

### 6.3 Ficha de palabra

- Forma encontrada y lema.
- Furigana y lectura completa.
- Categoría gramatical y conjugación.
- Significados locales de diccionario.
- Traducción contextual preexistente de la frase.
- Frecuencia y JLPT si están disponibles.
- Número de veces encontrada y lista de apariciones anteriores.
- Acceso a todas las frases y vídeos donde apareció.
- Marcar como conocida, aprendiendo, ignorada o favorita.
- Guardar la palabra junto con la frase, vídeo y tiempo exacto.
- Botón explícito **Explicar con GPT**.

### 6.4 Ficha de frase

- Japonés, furigana y español.
- Lista de palabras y análisis morfológico.
- Reproducción repetida del intervalo.
- Guardar o marcar como favorita.
- Ver otras frases con una palabra seleccionada.
- Botón explícito **Explicar gramática y matices con GPT**.
- Permitir preguntas adicionales manteniendo el contexto de esa frase, con consumo visible.

## 7. Explicaciones conceptuales bajo demanda

- Nunca se ejecutarán al importar, indexar o reproducir normalmente.
- Requerirán una acción inequívoca del usuario.
- Antes de enviar, mostrar qué palabra/frase se analizará.
- Enviar únicamente la frase seleccionada, traducción, tokens relevantes y una pequeña ventana de contexto.
- Usar GPT-5.4 Mini de forma predeterminada.
- Pedir una respuesta estructurada: significado contextual, construcción gramatical, matiz, nivel aproximado y traducción literal/natural.
- Guardar en caché cada explicación y reutilizarla sin volver a consumir tokens.
- Permitir regenerarla expresamente.
- Mostrar errores de API sin afectar a la reproducción ni a los datos locales.
- Permitir desactivar completamente OpenAI.
- Proteger la clave mediante almacenamiento cifrado de Android.

## 8. Historial y perfil personal

### 8.1 Vídeos

- Guardar última posición, porcentaje visto y fecha de última reproducción.
- Guardar duración total reproducida y número de sesiones.
- Marcar terminado al superar un umbral configurable, inicialmente 90 %.
- Permitir marcar como visto/no visto manualmente.
- Reanudar algunos segundos antes de la última posición.
- Conservar el progreso aunque se regenere el paquete.

### 8.2 Frases reproducidas

- Registrar una frase como encontrada solo después de mostrarse/reproducirse un tiempo mínimo.
- Guardar primera y última vez, número de exposiciones y episodios donde aparece.
- Distinguir entre encontrada, consultada, guardada y dominada.
- Evitar inflar contadores al arrastrar rápidamente la barra del vídeo.
- Permitir abrir el vídeo desde cualquier aparición histórica.

### 8.3 Palabras reproducidas

- Registrar por lema sin perder las distintas formas superficiales.
- Guardar primera y última aparición, contador de exposiciones y contextos.
- Distinguir escuchar/ver una palabra de abrir su ficha.
- Mostrar historial cronológico y agregado.
- Recuperar todas las frases asociadas a una palabra.
- Permitir corregir una segmentación errónea sin perder el historial.

### 8.4 Guardados y repaso

- Lista separada de palabras y frases guardadas.
- Etiquetas, notas personales y filtros.
- Repetición espaciada en una fase posterior, sin bloquear el reproductor inicial.
- Exportación a Anki con texto, furigana, traducción, imagen, audio y referencia temporal.
- Exportación e importación del perfil para copia de seguridad.

## 9. Persistencia y sincronización del perfil

- Base de datos local transaccional en Android.
- Identificadores estables para vídeos, frases y tokens.
- Migraciones de esquema sin borrar datos.
- Copias de seguridad manuales desde la aplicación.
- Restauración con vista previa y comprobación de integridad.
- No almacenar secretos dentro de las copias de progreso.
- Sincronización automática del perfil considerada para una fase posterior.

## 10. Privacidad, seguridad y costes

- Todo el procesamiento de audio y vídeo será local en el PC.
- Diccionario, furigana, historial y reproducción funcionarán sin Internet.
- OpenAI solo recibirá contenido cuando se solicite una traducción configurada o una explicación conceptual.
- La interfaz indicará claramente cuándo una acción consume API.
- Las explicaciones quedarán cacheadas localmente.
- La clave de API nunca aparecerá en registros, paquetes, exportaciones ni repositorio.
- Los tokens de Plex se almacenarán aparte y cifrados.

## 11. Fiabilidad y experiencia de uso

- Una cola debe recuperarse tras un cierre inesperado.
- Los archivos se escribirán primero como temporales y se renombrarán al completarse.
- Cancelar no debe dejar paquetes aparentemente válidos.
- La aplicación Android debe seguir siendo utilizable si OneDrive, Plex u OpenAI no están disponibles.
- Un error de un paquete no debe bloquear el resto de la biblioteca.
- Las operaciones largas deben mostrar fase, porcentaje y tiempo aproximado.
- Los mensajes deben incluir acciones concretas y detalles técnicos copiables.
- Mantener el actualizador interno de APK con validación SHA-256.

## 12. Fases propuestas

### Fase 1: contrato y prototipo de extremo a extremo

- Cerrar el formato `.lrpack` v1.
- Crear procesador Windows mínimo para un vídeo y una carpeta.
- Transcribir, traducir, tokenizar y empaquetar.
- Importar desde una carpeta elegida en Android.
- Reproducir con subtítulos dobles, furigana y navegación por frases.
- Guardar posición del vídeo.

### Fase 2: biblioteca e historial lingüístico

- Biblioteca por series/episodios.
- Historial de vídeos, frases y palabras.
- Fichas completas de palabra y frase.
- Guardados, búsqueda y filtros.
- Asociación local/Plex.

### Fase 3: explicaciones bajo demanda

- Botones explícitos de explicación.
- GPT-5.4 Mini con salida estructurada.
- Caché, regeneración y contador de consumo.
- Preguntas de seguimiento sobre una frase.

### Fase 4: edición, repaso y exportación

- Editor de transcripción, tiempos, furigana y traducción.
- Tarjetas y repetición espaciada.
- Exportación Anki con medios.
- Copia de seguridad y restauración del perfil.

### Fase 5: automatización y sincronización avanzada

- Vigilancia automática de carpetas en Windows.
- Descarga automática en Android.
- Microsoft Graph si resulta necesario.
- Sincronización del perfil entre dispositivos.

## 13. Criterios de aceptación del primer producto útil

- Procesar de principio a fin una carpeta con al menos una temporada de anime sin intervención por episodio, salvo errores reales.
- Reanudar la cola después de reiniciar el programa.
- Importar los paquetes desde una carpeta de OneDrive elegida por el usuario.
- Abrir un episodio de 20–30 minutos y navegar por frases sin retrasos perceptibles.
- Mostrar japonés, español y furigana correctamente sincronizados.
- Consultar localmente cualquier palabra sin API.
- Reanudar el vídeo desde la última posición después de cerrar la aplicación.
- Recuperar una palabra y ver todas sus frases y episodios anteriores.
- Solicitar una explicación conceptual solo mediante un botón y reutilizarla desde caché.
- Regenerar o actualizar un paquete sin perder historial ni favoritos.

## 14. Decisiones pendientes para la siguiente sesión

1. Especificaciones del PC: CPU, RAM, GPU y VRAM.
2. Si los vídeos deben viajar dentro del `.lrpack`, quedar junto a él o reproducirse principalmente desde Plex.
3. Motor por defecto para las traducciones previas: local, GPT-5.4 Mini o híbrido.
4. Si la primera versión de Windows será una interfaz gráfica, un servicio de carpeta vigilada o ambas.
5. Organización deseada de OneDrive por serie/temporada.
6. Si se registra automáticamente toda palabra mostrada o solo después de reproducir una fracción suficiente de la frase.
7. Política para openings, endings, avances y episodios ya procesados.
8. Un vídeo corto representativo y, después, un episodio completo para las pruebas de aceptación.
