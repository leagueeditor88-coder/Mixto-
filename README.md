# Automix Android — versión 2.0

Reproductor Android de música local con un motor Automix que coordina análisis, selección, preparación, sincronización y transiciones, además de una pantalla separada para Mashup.

## Pantallas

- **Reproductor:** biblioteca local, selección de carpetas, controles de reproducción, Automix como control maestro y Aleatorio como modo independiente de selección.
- **Mashup:** selección manual de pista A y pista B, búsqueda de una segunda pista compatible, y duración configurable de superposición.
- **Ajustes:** duración de reproducción antes de cambiar, duración del crossfade, salto de intro, análisis BPM/tonalidad y búsqueda de etiquetas de género online.

## Funciones

- Crossfade con curvas **Desvanecer** y **Ascender**.
- Reproducción de una fracción configurable de cada canción (por defecto 70%).
- Estimación local de BPM y tonalidad, con caché persistente en el teléfono.
- Selección musical de candidatas en Automix: pondera BPM, equivalencias de medio/doble tempo, tonalidad y género cuando hay datos; solo puede cambiar el orden pendiente si Aleatorio está activo.
- Mashup de dos pistas con fundido inicial/final y duración configurable (10–90 segundos).
- Etiquetas de género consultando los catálogos públicos de **Deezer** y **iTunes** con el título/artista. Se muestran solo etiquetas, no enlaces ni artículos. No se envía el audio a Internet.
- Ajustes de transición, modo, BPM, tonalidad y etiquetas guardados localmente.
- Controles con estados visuales: verde activo, gris inactivo, amarillo procesando y rojo error.

## Límites importantes

- **No es un mezclador DJ profesional.** Automix intenta alinear tiempos y ajustar la velocidad dentro de límites conservadores, pero no incorpora time-stretch especializado, detección semántica fiable de frases vocales ni separación de stems. Mashup superpone dos archivos y usa fundidos.
- Smart Reorder se basa en BPM/tonalidad estimados; no garantiza una transición musical perfecta.
- Las categorías que devuelven Deezer/iTunes dependen de sus catálogos. Esta versión no usa un modelo generativo de IA ni una búsqueda web general; emplea metadatos de catálogos musicales y no inventa etiquetas cuando no obtiene una coincidencia fiable.
- El análisis inicial de una biblioteca grande puede consumir CPU, batería y tiempo. Se ejecuta en segundo plano y guarda resultados en el dispositivo.
- La búsqueda online necesita conexión a Internet; puede no devolver etiquetas si el catálogo no tiene datos o limita las consultas.

## Compilar en GitHub Actions

El workflow incluido (`.github/workflows/build.yml`) ejecuta las pruebas unitarias y compila con Gradle 8.7; después publica el artefacto `Automix-APK` con `app-debug.apk`.

Para un repositorio nuevo, descomprime el ZIP y sube **el contenido del proyecto** a la raíz del repositorio (incluidos `app/`, `build.gradle.kts`, `settings.gradle.kts` y `.github/`). Si el repositorio tiene un workflow personalizado que construye un ZIP, sustituye el ZIP conservando exactamente el nombre que dicho workflow espera.

## Estado de verificación

Se revisaron estáticamente los archivos Kotlin y la configuración del proyecto. En el entorno de generación no está instalado el Android SDK ni Gradle y no hay acceso de red para descargarlos, así que **no se pudo realizar una compilación Android real ni una prueba en dispositivo**. El ZIP debe compilarse en GitHub Actions y probarse en el teléfono antes de tratar esta versión como estable. Consulta `REVISION_TECNICA.md` para los puntos revisados y las limitaciones restantes.

## Privacidad

BPM y tonalidad se analizan localmente. El título y el artista se consultan en los catálogos online al solicitar las etiquetas de género; el archivo de audio no se sube.
