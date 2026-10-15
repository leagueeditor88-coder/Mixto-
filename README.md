# Automix Android — versión 1.2

Reproductor de música local para Android con transición automática entre canciones.

## Incluido
- **Análisis automático local de biblioteca**: al cargar las canciones, analiza en segundo plano las que todavía no tengan datos guardados.
- **Detección estimada de BPM** a partir del audio decodificado.
- **Detección estimada de tonalidad** (nota y modo mayor/menor) con análisis espectral/chroma, sin enviar el audio a Internet.
- **Smart Reorder armónico**: combina cercanía de BPM y compatibilidad aproximada de tonalidad mediante la rueda Camelot. Las canciones con datos incompletos tienen menos prioridad y las no analizadas quedan al final.
- Caché persistente de BPM y tonalidades en el almacenamiento privado de la app; no se vuelve a analizar una canción que ya tenga ambos datos, salvo que se borren los datos de la aplicación.
- Modo **Aleatorio**, crossfade configurable y dos curvas de transición: **Desvanecer** y **Ascender**.
- Mashup opcional básico: superposición de dos pistas; no realiza separación de stems ni sincronización de beat automática.
- Búsqueda online de género con MusicBrainz y consulta de respaldo al catálogo de iTunes.
- Filtro por carpetas, saltar intro, porcentaje de reproducción y controles de reproducción.

## Límites importantes
- BPM y tonalidad son **estimaciones**, no análisis profesional garantizado. Percusión fuerte, audio comprimido, cambios de tonalidad, canciones en vivo o mezclas pueden producir resultados incorrectos o no detectables.
- El análisis de biblioteca puede tardar y consumir batería/CPU, especialmente con bibliotecas grandes. Se ejecuta en segundo plano mientras la app permanece abierta; los resultados obtenidos se guardan localmente.
- Smart Reorder prioriza BPM y tonalidad estimados, pero no realiza beatmatching ni key-shifting en tiempo real.
- El Mashup es una superposición básica de dos archivos, no un mezclador DJ completo. No incluye ecualizador DSP real, filtros paso alto/bajo ni eco DSP.
- Las curvas Desvanecer/Ascender afectan la envolvente de volumen; no sincronizan golpes.
- El ZIP contiene cambios de código, pero debe compilarse en GitHub Actions y probarse en el teléfono antes de considerar estable el APK. No se ha verificado la compilación de esta versión en un entorno Android SDK.

## Compilar desde GitHub
1. Si tu workflow extrae un ZIP, reemplaza ese ZIP y conserva exactamente el nombre que espera el workflow.
2. Si usas el workflow incluido, sube el contenido del proyecto a la raíz del repositorio.
3. Abre **Actions** y ejecuta **Build APK**, o espera a que se active tras el push.
4. Descarga el artefacto **Automix-APK** e instala `app-debug.apk`.

## Privacidad
El análisis de BPM y tonalidad se realiza localmente con el audio del teléfono. La búsqueda de género sí usa Internet y consulta servicios públicos de metadatos; no sube el archivo de audio.
