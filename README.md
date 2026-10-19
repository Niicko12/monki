# Monki

Asistente de voz estilo mayordomo para la consola **Anbernic RG353V** (Android 11). Lo despiertas con "hey monki", entiende español y actúa: busca en internet, maneja la consola, controla la Mac, las teles, los celulares y los Echo de Alexa de la casa.

> **Estado:** en desarrollo activo. Funciona a diario en una consola real, pero todavía hay cosas por pulir (ver [Pendientes](docs/pendientes.md)).

Todo usa servicios gratuitos: la cuota gratis de la API de Gemini, Vosk y Piper sin internet, y DuckDuckGo y wttr.in para internet. No hace falta cuenta de pago.

## Qué puede hacer

| Área | Ejemplos |
|---|---|
| **Conversar y buscar** | Preguntas generales (Gemini Flash Lite), búsquedas en internet (DuckDuckGo), leer páginas, clima (wttr.in) |
| **Tiempo y memoria** | Temporizadores, alarmas, recordatorios; recuerda datos del usuario entre conversaciones |
| **Rutinas** | Frases que hacen varias cosas ("buenas noches": apaga luces con Alexa, baja brillo y volumen). Se crean por voz |
| **La consola** | Ve la pantalla, toca y desliza, abre apps y juegos, escribe, volumen, brillo, batería |
| **La Mac** (vía `casa`) | Estado, capturas, abrir apps/páginas, volumen, hablar por sus bocinas, avisos, música |
| **Teles y celulares** (vía `casa`) | Chromecast/YouTube, celulares Android por adb, Wake-on-LAN, focos |
| **Alexa** (vía `casa`) | Mandar órdenes a los Echo como si les hablaras (música en Spotify, luces, rutinas) y hacerlos hablar |

## Cómo está armado

```
            "hey monki"
                │
┌───────────────▼────────────────────────────┐        ┌───────────────────────────────┐
│ Consola Anbernic RG353V  (asistente-apk)   │  MCP   │ Mac  (casa)                   │
│                                            │ ─────► │                               │
│ Vosk (sin internet) → palabra de activación│  HTTP  │ casa.py  servidor MCP :8800   │
│ Gemini API → entiende, piensa, usa tools   │ +clave │  ├─ Mac, red, teles, Android  │
│ Gemini TTS o Piper (sin internet) → voz    │        │  └─ alexa/alexa.js :8801 ─────┼──► Echos
│ Servicio de accesibilidad → ve y toca      │        │     (solo 127.0.0.1)          │
└────────────────────────────────────────────┘        └───────────────────────────────┘
```

| Carpeta | Qué es |
|---|---|
| [`asistente-apk/`](asistente-apk) | App Android en Kotlin (`com.limber.asistente`): Monki en la consola |
| [`casa/`](casa) | Hub MCP en Python (FastMCP) para Mac o Windows, más el puente de Alexa en Node |
| [`agente/`](agente) | Versión de escritorio: el agente corre en la Mac y maneja la consola por adb |
| [`herramientas/`](herramientas) | Descarga del modelo Vosk y scripts para analizar el reconocimiento de voz |
| [`configuracion/`](configuracion) | Plantillas de LaunchAgent (macOS) y controles de PPSSPP |
| [`docs/`](docs) | Configuración, problemas conocidos y pendientes |

Más detalle en [docs/arquitectura.md](docs/arquitectura.md).

## Instalación rápida

**Requisitos:** consola Android 11 con depuración USB (probado en RG353V), una Mac con Homebrew, y una clave gratis de la [API de Gemini](https://aistudio.google.com/apikey).

### 1. App en la consola

```bash
herramientas/descargar-modelo-vosk.sh          # modelo de voz para "hey monki" (~58 MB)
cd asistente-apk
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Permiso para prender/apagar el servicio de toques (ver docs/problemas-conocidos.md)
adb shell pm grant com.limber.asistente android.permission.WRITE_SECURE_SETTINGS

# Clave de Gemini, tu nombre y tu ciudad
adb shell "am start -n com.limber.asistente/.MainActivity --es key 'TU_CLAVE' --es user_name 'Tu nombre' --es location 'Tu ciudad, Estado, País'"
```

Después, en la consola: **Ajustes → Accesibilidad → Monki → activar**, y dale permiso de micrófono.

**Voz sin internet (recomendada):** cuando se acaba la cuota gratis de voz de Gemini, Monki usa Piper. Instala el motor de voz [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html) con la voz `es_MX-ald-medium` (arm64).

### 2. Hub `casa` en la Mac (opcional)

```bash
cd casa
uv run casa.py      # la primera vez crea .env con CASA_TOKEN
```

Para que arranque solo, usa la plantilla `configuracion/launchd/com.limber.casa.plist`. Luego conecta Monki al hub:

```bash
adb shell "am start -n com.limber.asistente/.MainActivity --es mcp_servers 'casa|http://IP_DE_LA_MAC:8800/mcp|CASA_TOKEN'"
```

### 3. Alexa (opcional, no oficial)

```bash
cd casa/alexa && npm install && ALEXA_PRINCIPAL="Nombre de tu Echo" node alexa.js
```

Abre `http://127.0.0.1:3456` en la Mac e inicia sesión en Amazon. La sesión queda en `alexa-cookie.json`, que **nunca** debe subirse ni compartirse. Usa [alexa-remote2](https://github.com/Apollon77/alexa-remote), que no es oficial y puede dejar de funcionar si Amazon cambia algo.

Todos los ajustes están en [docs/configuracion.md](docs/configuracion.md).

## Uso

- **"hey monki"** (o "monki") o el botón **X**: escucha. **B** cancela. La burbuja abre el panel.
- Pídele lo que sea: "¿cómo estará el clima mañana?", "pon un temporizador de 10 minutos", "abre PPSSPP", "pon música en Spotify en el comedor", "buenas noches".
- Crea rutinas hablando: "crea una rutina 'a jugar' que suba el brillo a 80 y abra PPSSPP".

## Problemas conocidos

Lo aprendido a golpes está en [docs/problemas-conocidos.md](docs/problemas-conocidos.md). Lo más importante:
- Un servicio de accesibilidad con permiso de gestos rompe la cruceta y el stick en los emuladores.
- El reproductor de Rockchip se traba si se le cambia la velocidad.
- La cuota gratis de voz de Gemini se acaba rápido.

## Créditos y licencias

- [Vosk](https://alphacephei.com/vosk/) (Apache 2.0): reconocimiento de voz sin internet.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) y [Piper](https://github.com/rhasspy/piper): voz sin internet.
- [MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk), [alexa-remote2](https://github.com/Apollon77/alexa-remote), [PyChromecast](https://github.com/home-assistant-libs/pychromecast).
- Inspirado en [xiaozhi-esp32](https://github.com/78/xiaozhi-esp32).

Código bajo licencia [MIT](LICENSE).
