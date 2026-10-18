Automix — primera capa de análisis de estructura

Archivo: TrackStructureAnalyzer.kt
Paquete: com.example.automix

Esta primera capa recibe niveles RMS ya calculados y estima regiones de baja energía,
subidas, energía alta, cambios y estabilidad. Es un detector de candidatos, no un
clasificador semántico: no afirma detectar voces, versos, coros, puentes ni interludios.

La función estimate(levels, durationMs, windowMs) es pura y se puede probar por separado.
La siguiente integración consiste en hacer que el análisis PCM compartido produzca la
envolvente RMS y guardar Analysis en una caché por URI; después el TransitionPlanner
combinará esos candidatos con BPM, compases, actividad vocal y tiempo de gracia.

No se afirma que el APK compile: este archivo aún debe añadirse al módulo Android y
conectarse al análisis PCM. No reemplaza AutomixService.kt ni los detectores existentes.
