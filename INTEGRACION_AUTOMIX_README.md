# Automix — integración del motor DJ automático

## Arquitectura integrada

- **AUTOMIX** es el control maestro de selección, preparación, sincronización y transición.
- **Aleatorio** controla si el motor puede reordenar las candidatas pendientes por compatibilidad. En modo ordenado se conserva estrictamente la cola.
- BPM/beatgrid se comparten mediante `BpmAnalysisRepository`, con caché en memoria y persistencia por URI.
- La tonalidad se comparte mediante `KeyAnalysisRepository`, también con caché y persistencia por URI.
- La biblioteca prioriza el análisis en el orden visible de reproducción y después completa la biblioteca en segundo plano.
- Durante AUTOMIX se preanalizan varias candidatas (BPM/beatgrid y tonalidad); la estructura y los límites audibles se preparan con una prioridad más conservadora para no bloquear la reproducción.
- La selección pondera BPM, equivalencias de medio/doble tempo, compatibilidad Camelot y género cuando hay datos disponibles.
- El planificador intenta elegir puntos de transición usando límites audibles, energía, rejilla rítmica y una ventana de recuperación.
- El beatmatching ajusta la velocidad de forma progresiva y limitada; si la relación es demasiado extrema o el análisis no es fiable, usa velocidad normal.
- La interfaz mantiene AUTOMIX como único control del motor y Aleatorio como modo independiente.

## Límites explícitos

- La estructura basada en RMS detecta cambios de energía y zonas de baja energía; no identifica de forma fiable voces, versos, estribillos o frases musicales.
- La detección de BPM, beatgrid y tonalidad es heurística y puede fallar en música con tempo variable, percusión ambigua o contenido poco rítmico.
- La sincronización de fase es conservadora. No equivale a un sistema de beatmatching profesional con detección robusta de frases y separación de stems.
- El motor ajusta velocidad mediante Media3; no se ha integrado un algoritmo de time-stretch dedicado que garantice preservar el tono en todo el rango.
- La compilación y las pruebas instrumentadas deben ejecutarse con Android SDK/Gradle disponible. El entorno de edición usado para esta integración no tiene Gradle ni Android SDK configurados, por lo que no debe afirmarse que se ha generado un APK aquí.

## Construcción

El proyecto contiene `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, manifiesto, recursos y un workflow de GitHub Actions que instala Gradle 8.7 y ejecuta `assembleDebug`. No incluye `gradlew`; en local puede compilarse con Gradle 8.7 instalado o mediante el workflow.
