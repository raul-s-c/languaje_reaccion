# Reproductor 0.3.2

- Pantalla completa apaisada: la superficie del vídeo ocupa todo el espacio; los controles y subtítulos se superponen, sin reducir su tamaño.
- «Ajustar» conserva toda la imagen y su proporción. «Llenar (recorta)» elimina las bandas recortando bordes, sin estirar la imagen. Las bandas que ya estén grabadas dentro del vídeo no desaparecen necesariamente.
- Toca el vídeo para mostrar/ocultar los controles. En pantalla completa se ocultan automáticamente durante la reproducción.
- Toca una frase en pantalla completa para pausarla y consultar las palabras y lecturas. Cierra la ficha y pulsa Reproducir para continuar.
- Sincronización: audio y subtítulos independientes, −5 a +5 segundos, pasos de 100 ms. Positivo retrasa; negativo adelanta. Los botones «Ambos» conservan la diferencia entre los dos ajustes, incluso en los límites.
- Los ajustes se aplican en directo y se guardan por URI del vídeo. Si mueves el archivo o cambias su URL, se considera otro vídeo.
- El retardo de audio se aplica mediante LibVLC `setAudioDelay`, en microsegundos. No se simula adelantando/retrasando toda la reproducción.
- Se conserva la posición de reproducción existente. La app se pausa al pasar a segundo plano. Se puede elegir la pista de audio desde «Audio»; se solicita japonés por defecto.
- No se cargan subtítulos externos ni incrustados en el vídeo: se usan los generados/importados por la aplicación.

## Dependencia del reproductor

LibVLC Android 3.7.5, de VideoLAN, se incorpora sin modificaciones como biblioteca nativa dinámica para ARM64. Fuentes, licencia e instrucciones de construcción:

- https://code.videolan.org/videolan/vlc-android/-/tree/libvlc-3.7.5
- https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.5/
- https://www.videolan.org/legal.html

LibVLC se distribuye bajo LGPL 2.1 o posterior; sus componentes incluyen sus respectivas licencias. El código de integración está en este repositorio y puede recompilarse sustituyendo la dependencia. No se impide la modificación ni la ingeniería inversa para depurar modificaciones de esa biblioteca.

## Comprobaciones manuales en la tablet

1. Abrir un MKV con audio japonés e importar su `.lrpack`.
2. Entrar/salir de pantalla completa sin reiniciar el capítulo. Probar Ajustar y Llenar.
3. Aplicar audio +1000 ms: debe oírse un segundo más tarde, sin salto del vídeo. Probar −1000 ms y cero.
4. Aplicar subtítulos +1000 ms y −1000 ms: solo debe cambiar la aparición de las frases.
5. Mover ambos y comprobar que se conserva la diferencia. Salir/reabrir el vídeo y confirmar los valores.
6. Pausar, buscar, cambiar velocidad/pista, consultar palabras, girar y pasar a segundo plano.

Las pruebas unitarias verifican conversión de unidades, desplazamiento conjunto, identificadores de progreso y formato de tiempo. La compilación no sustituye la comprobación audiovisual en el dispositivo.
