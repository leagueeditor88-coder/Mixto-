# Revisión técnica de Automix — integración global

## Integración realizada

- Se conserva una sola sesión de reproducción basada en Media3/ExoPlayer.
- El botón AUTOMIX controla el motor automático; Aleatorio es un modo de selección independiente.
- El selector solo reordena candidatas cuando Aleatorio está activo. En los demás modos conserva el orden de la cola.
- `BpmAnalysisRepository` comparte BPM/beatgrid entre la biblioteca y el servicio, y persiste los resultados por URI.
- Se añadió `KeyAnalysisRepository` para compartir y persistir la detección de tonalidad entre la biblioteca y el preanálisis del servicio.
- El preanálisis prioriza varias candidatas cuando Aleatorio está activo y solo la siguiente en modo ordenado; el análisis de estructura se limita para controlar el coste de CPU.
- Los resultados de BPM/beatgrid que terminan durante la reproducción se publican en el selector y pueden actualizar la rejilla de la pista que esté sonando.
- La biblioteca captura el orden visible antes de iniciar el trabajo en segundo plano, para analizar primero las canciones más próximas.
- `TransitionPlanner` considera equivalencias de medio/doble tempo al decidir la duración y el estilo de transición.
- Se añadieron pruebas unitarias para el fallback del planificador y la equivalencia 70/140 BPM.

## Verificación disponible

- Se inspeccionó la estructura del ZIP y la presencia de configuración Gradle, manifiesto, recursos, servicio y analizadores.
- Se ejecutó un intento de análisis con `kotlinc`, pero sin Android SDK ni dependencias Android/Compose/Media3 el proyecto produce referencias no resueltas; ese intento no equivale a una compilación Android válida.
- No se pudo ejecutar `gradle assembleDebug` en este entorno porque Gradle y Android SDK no están disponibles.
- Las pruebas unitarias están añadidas, pero todavía deben ejecutarse mediante Gradle/Android Studio o el workflow de GitHub Actions.

## Limitaciones conocidas

- El análisis RMS no identifica de forma fiable voces, versos, estribillos ni frases musicales.
- BPM, tonalidad, beatgrid y energía son estimaciones y pueden fallar según el contenido de audio.
- El beatmatching actual limita el cambio de velocidad y no incorpora un algoritmo de time-stretch especializado que garantice preservar el tono.
- La integración debe probarse en un dispositivo real para validar permisos, notificaciones, controles de pantalla bloqueada, transiciones audibles y consumo de batería.
