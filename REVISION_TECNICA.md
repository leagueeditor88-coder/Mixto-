# Revisión técnica de Automix — actualización de trabajo

## Cambios incluidos en esta entrega

- Se mejoró `BpmDetector.estimate`: la autocorrelación normalizada se calcula una sola vez por retardo, se pondera el segundo armónico para reducir algunos errores de medio/doble tempo y se aplica una penalización pequeña al subarmónico.
- Se añadió `BpmDetectorTest.kt` con pruebas JVM para una señal sintética de 120 BPM y para el rechazo de señales demasiado cortas.
- Se conservaron la interfaz, el servicio de reproducción y las dependencias originales; no se añadieron bibliotecas externas sin integración verificada.

## Límites conocidos

- Esta mejora no sustituye una detección de beats basada en modelos ni garantiza BPM correcto en todos los géneros. La estimación trabaja sobre la envolvente de energía de hasta los primeros 30 segundos.
- La detección real de intro/outro por contenido todavía requiere un analizador dedicado de PCM y su integración con la planificación del crossfade. No se debe interpretar la duración del archivo como una medida de outro musical.
- SoundTouch/Rubber Band necesitan una ruta de audio PCM real para cambiar tempo/tono; añadir solo la dependencia no implementaría beatmatching.
- Demucs/ONNX requiere un modelo compatible, pre/postprocesamiento y pruebas de memoria/rendimiento en dispositivos Android. No se activa en esta entrega.
- El motor actual `MediaPlayer` y la interfaz se han mantenido para minimizar el riesgo de regresiones.

## Verificación

El ZIP se ha comprobado estructuralmente. No se pudo ejecutar una compilación Gradle en este entorno porque no está disponible el ejecutable Gradle ni un wrapper dentro del proyecto. Por tanto, las pruebas nuevas están añadidas, pero aún deben ejecutarse en Android Studio o mediante CI.
