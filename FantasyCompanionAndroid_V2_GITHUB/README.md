# Fantasy Companion Android

Companion personal para LALIGA Fantasy en Android.

## V2 Beta

- Overlay mediante `AccessibilityService` sobre `com.lfp.laligafantasy`.
- Detección de jugador mediante puntuación de candidatos y filtrado de textos genéricos de la interfaz.
- Capa de estadísticas para goles, asistencias, amarillas, rojas y porterías a cero.
- Compilación automática de APK debug mediante GitHub Actions.

## Compilación automática

Cada push a `main` ejecuta **Build Android APK**. También se puede lanzar manualmente desde la pestaña **Actions** usando `workflow_dispatch`.

Cuando termina correctamente, GitHub publica el artifact:

`FantasyCompanion-V2-debug`

que contiene `app-debug.apk`.

## Estado

V2 beta experimental para uso personal. No modifica ni parchea la aplicación oficial de LALIGA Fantasy.
