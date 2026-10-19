# Arquitectura

## App de la consola (`asistente-apk`)

Kotlin, sin dependencias de UI externas, `minSdk 30` (Android 11). Todo vive en `app/src/main/java/com/limber/asistente/`:

| Archivo | Qué hace |
|---|---|
| `AgentService.kt` | Servicio de accesibilidad principal: interfaz flotante (burbuja, panel, ojos), micrófono, voz, palabra de activación y las herramientas de la consola (`Controls`) |
| `Agent.kt` | Bucle con Gemini: arma el *system prompt* (persona, nombre, ubicación, memoria, rutinas), llama herramientas y devuelve la respuesta con una emoción |
| `Gemini.kt` | Cliente REST de Gemini: generar con modelos de respaldo ante 429, transcribir audio y voz (TTS) |
| `WakeWord.kt` | "hey monki" sin internet con Vosk y una gramática cerrada (ver más abajo) |
| `VoiceRecorder.kt` | Graba hasta 1.3 s de silencio |
| `OfflineVoice.kt` | Voz de respaldo con el motor Android de sherpa-onnx (Piper `es_MX`) |
| `GestureService.kt` | Segundo servicio de accesibilidad, **solo** para tocar la pantalla; se prende y apaga solo |
| `Tools.kt` | Herramientas `self.*`: pantalla, botones, apps, volumen, brillo |
| `Web.kt` | `internet.buscar` (DuckDuckGo), `internet.leer`, `internet.clima` (wttr.in) |
| `Extras.kt` | Alarmas y recordatorios, memoria a largo plazo, rutinas |
| `WakeOnLan.kt` | Prender computadoras de la red |
| `McpClient.kt` / `McpServer.kt` | Cliente MCP (para usar `casa`) y servidor MCP propio en el puerto 8765 (para que otros usen la consola) |
| `EyesView.kt` | Ojos animados con emociones |
| `MainActivity.kt` | Ajustes, y configuración por `adb am start --es …` |
| `Prefs.kt` | Preferencias y valores por defecto |

### Un turno por voz

```
palabra de activación (Vosk) → aviso sonoro → graba hasta el silencio
  → Gemini transcribe el audio
  → Gemini responde, llamando herramientas las veces que haga falta
  → voz: Gemini TTS; si falla (429), Piper sin internet, por oraciones
  → vuelve a escuchar unos segundos (seguimiento) y luego a la palabra de activación
```

Cada paso deja su tiempo en el log: `adb logcat -s Tiempos`. Con la voz sin internet, un turno típico tarda unos 6 s: 1.3 s de silencio, 1.8 s de transcripción, 1 s de Gemini y unos 2-3 s hasta que suena la primera oración.

### Palabra de activación

Vosk con el modelo pequeño de español no conoce "monki". La gramática acepta lo que el modelo entiende al oírlo: `monkey`, `mon qui`, `mon ki`, `moni`, `qui`, `mon`.

- Con "hey" delante, aunque llegue en otra frase dentro de 3 s, basta una seguridad de 0.3.
- Sin "hey": `mon qui` o `mon ki` desde 0.5, `mon` o `monkey` desde 0.7.
- `qui` y `moni` sueltos nunca lo despiertan, porque el ruido de los juegos los produce.
- La ganancia es automática, hasta ×4, para no saturar la voz.
- Cada vez que oye algo, guarda los últimos 4 s en `Android/data/com.limber.asistente/files/wake-debug.wav`, para analizarlos con `herramientas/voz/`.

## Hub `casa`

`casa/casa.py` es un servidor MCP (FastMCP, Streamable HTTP, sin estado) en `0.0.0.0:8800/mcp`. Exige `Authorization: Bearer CASA_TOKEN`, y la clave se genera en `.env` la primera vez. Funciona en macOS y en Windows (`instalar-windows.ps1`).

Herramientas: `red_escanear`, `pc_*` (estado, captura, apps, URL, volumen, decir, notificar, música), `tv_*` (Chromecast/YouTube), `android_*` (celulares por adb), `focos_estado` y `alexa_*`.

### Puente de Alexa

`casa/alexa/alexa.js` usa `alexa-remote2` con Amazon México (`amazon.com.mx`, servidor `pitangui.amazon.com`).

- Escucha solo en `127.0.0.1:8801`. Rutas: `/estado`, `/dispositivos`, `/comando` (manda texto al Echo como si le hablaras) y `/decir`.
- El inicio de sesión se hace en un proxy local (`127.0.0.1:3456`) y la sesión queda en `alexa-cookie.json`, con permisos 600.
- El log filtra cookies y tokens.
- `ALEXA_PRINCIPAL` indica qué Echo usar cuando no se nombra ninguno.

## Agente de escritorio (`agente`)

`agente/agente.py` es la primera versión: corre en la Mac con la API de Gemini y maneja la consola por adb, con capturas, toques y escritura. Hoy la app de la consola hace lo mismo sin necesitar la Mac.
