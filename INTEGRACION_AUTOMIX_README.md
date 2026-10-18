# Automix — integración del DJ inteligente

## Qué incluye esta iteración

- **AUTOMIX** es el modo DJ inteligente; Smart ya no es un modo independiente.
- **Aleatorio** se mantiene para reproducción normal y puede dar variedad a los candidatos de AUTOMIX.
- **BPM** sigue disponible en la biblioteca para análisis/ordenación. El análisis de BPM y tonalidad de la biblioteca se ejecuta en segundo plano; si termina mientras AUTOMIX está activo, se reorganiza la cola restante sin reiniciar la canción actual.
- El servicio comparte una sola decodificación PCM para detectar límites audibles y calcular la envolvente RMS.
- `TrackStructureAnalyzer` ahora recibe la envolvente RMS y produce candidatos de cambio basados en cambios de energía/zonas de baja energía.
- El porcentaje seleccionado (70 % por defecto) es el inicio de búsqueda: AUTOMIX prefiere un candidato estructural posterior si existe antes del margen de seguridad del final.
- Beat Grid, planificación de transición, ajuste de BPM y alineación rítmica siguen integrados como heurísticas conservadoras.
- Se reserva margen para iniciar el crossfade antes del final audible de la pista.

## Limitaciones explícitas

- El análisis RMS **no identifica de forma fiable voces, versos, estribillos, puentes instrumentales ni frases musicales**. Un cambio de energía es solo un candidato heurístico, no una comprensión semántica de la canción.
- Si no hay candidato adecuado, se usa el punto base limitado por el margen de recuperación. No hay una espera ilimitada: el sistema debe evitar terminar la pista sin transición.
- La detección de BPM, tonalidad y compases depende de la calidad del audio y puede fallar en canciones con percusión ambigua, cambios de tempo o música poco rítmica.
- No se ha podido verificar una compilación Android completa porque este paquete no incluye el proyecto Gradle, manifiesto, recursos ni `gradlew`. Se requiere probarlo dentro del proyecto original.

## Archivos

Copiar estos archivos al paquete Android existente, reemplazando los correspondientes:

- `MainActivity.kt`
- `AutomixService.kt`
- `AudioBoundaryDetector.kt`
- `TrackStructureAnalyzer.kt`
- `TransitionPlanner.kt`
- `BeatGridDetector.kt`

No se incluye un APK compilado.
