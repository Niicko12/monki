<div align="center">

# Monki

### Asistente de voz con cara para la consola Anbernic RG353V

Lo despiertas con *"hey monki"*, te entiende en español y **actúa**: contesta, busca en internet, maneja la consola,
controla tu Mac y tu PC, pone música y alarmas en el Echo y reproduce YouTube en su propia pantalla.

![Android 11](https://img.shields.io/badge/Android-11-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-app-7F52FF?logo=kotlin&logoColor=white)
![Python](https://img.shields.io/badge/Python-hub%20MCP-3776AB?logo=python&logoColor=white)
![llama.cpp](https://img.shields.io/badge/llama.cpp-modelo%20local-black)
![Costo](https://img.shields.io/badge/costo-%240-brightgreen)
![Licencia MIT](https://img.shields.io/badge/licencia-MIT-blue)

<img src="docs/imagenes/monki-hablando.png" width="640" alt="Monki hablando en la pantalla de la consola: ojos, boca abierta y widgets de hora, clima, alarma y computadora">

[Funciones](#funciones) ·
[Galería](#galería) ·
[Cómo funciona](#cómo-funciona) ·
[Instalación](#instalación) ·
[Uso](#uso) ·
[Documentación](#documentación)

</div>

---

## Por qué Monki

- **100 % gratis.** Encadena APIs gratuitas (Groq → NVIDIA → OpenRouter → Gemini) y, si todas se agotan o no hay
  internet, contesta con un modelo que corre **dentro de la consola**.
- **No depende de una computadora.** El control del Echo y de Spotify vive dentro de la app: con la Mac apagada sigue
  funcionando todo.
- **Tiene personalidad.** Cara animada con boca que sigue la voz, ánimo que cambia con la conversación, la hora, la
  batería, la canción o el video que suena, y un solo color que tiñe toda la interfaz.
- **Pensado para un control de juego.** Todo se usa con la cruceta y los botones, o tocando la pantalla.

> **Estado:** en desarrollo activo y en uso diario en una consola real. Lo que falta está en
> [Pendientes](docs/pendientes.md).

---

## Funciones

| Área | Qué puedes pedirle |
|---|---|
| **Conversar** | Cualquier pregunta, en 1 o 2 frases directas. Personalidad de mayordomo con humor seco |
| **Internet** | Búsquedas (DuckDuckGo), leer páginas y clima (wttr.in), sin gastar cuota de IA |
| **Música** | *"Pon música relajante en Spotify"*, pausa, siguiente, volumen, adelantar. Suena en el Echo o en la consola |
| **YouTube** | *"Pon en YouTube…"*: lista con miniaturas para elegir y reproductor en el lugar de la cara |
| **Alarmas en el Echo** | *"Pon una alarma mañana a las 7 con Metallica"*: queda en el Echo y suena con esa música de Spotify |
| **Tiempo y memoria** | Temporizadores, recordatorios y datos tuyos que recuerda entre conversaciones |
| **Rutinas** | Frases que hacen varias cosas (*"buenas noches"*). Se crean y se borran hablando |
| **La consola** | Ve la pantalla, toca, desliza, abre apps y juegos, escribe, volumen, brillo y batería |
| **Mac y Windows** (vía `casa`) | Estado, captura, apps, teclado y mouse, portapapeles, archivos, brillo, bloquear, dormir y apagar (con confirmación) |
| **Teles y celulares** (vía `casa`) | Chromecast y YouTube en la tele, celulares Android por adb, Wake-on-LAN |

### La cara

- **Ojos y boca animados** con 20 expresiones; la boca se mueve con el volumen real de la voz.
- **Se siente viva:** respira, tiene destello en los ojos, rebota al cambiar de expresión, parpadea doble, muestra
  puntitos al pensar y "z" al dormir.
- **Ánimo persistente:** sube con respuestas alegres, baja con tristes, se cansa de noche o con poca batería y se aburre
  si no le hablas.
- **Ánimo de lo que suena:** al cambiar la canción o abrir un video, decide si es bailable, alegre, tranquilo, triste o
  pesado. La cara, los widgets, los botones y el reproductor toman ese color.

<p align="center">
  <img src="docs/imagenes/monki-animos.png" alt="Seis ánimos de Monki con su color: feliz, emocionado, triste, molesto, cansado y aburrido">
  <br><sub>Feliz, emocionado, triste, molesto, cansado y aburrido.</sub>
</p>

### La pantalla

- **Widgets** a los lados de la cara: hora, clima, próxima alarma del Echo y cada computadora conectada (modelo,
  batería, disco, CPU, RAM). Tócalos (o elígelos con la cruceta y A) para ver el detalle; regresa solo a la cara en 10 s.
- **Reproductor** con portada, fondo del color de la portada, **barra que se arrastra** para adelantar o regresar y
  botones ⏮ ⏯ ⏭. Aparece cuando suena Spotify en la consola o en el Echo y se oculta tras un minuto en pausa.
  Tócalo para ver la lista de canciones y elegir otra.
- **Con un video abierto, el reproductor controla el video:** ⏯ pausa, ⏮ ⏭ saltan 10 s y la barra recorre el video.
- **Una sola cosa suena a la vez:** abrir un video pausa Spotify, y darle play a Spotify en la consola pausa el video.
- **Pantalla completa**, sin las barras de Android, y botones en forma de píldora.

### Por dónde suena

<p align="center">
  <img src="docs/imagenes/monki-botones.png" alt="Botones de arriba: salida de audio, historial, YouTube, nueva conversación y cerrar">
</p>

- El botón **📺 Consola / 🔊 Bocina** cambia la salida de audio de un toque. La música o el video que suena **sigue sin
  cortarse**: Spotify se pasa al otro equipo por Spotify Connect y el audio de la consola va al Echo por Bluetooth.
- **Botones de volumen de la consola:** controlan el equipo donde suena (Echo o consola) y muestran en pantalla a dónde
  fue y el nivel.

---

## Galería

<table>
  <tr>
    <td width="50%"><img src="docs/imagenes/monki-pantalla.png" alt="Pantalla principal con cara, widgets y reproductor de Spotify"></td>
    <td width="50%"><img src="docs/imagenes/monki-youtube-lista.png" alt="Lista de videos de YouTube con miniaturas para elegir"></td>
  </tr>
  <tr>
    <td align="center"><sub><b>Pantalla principal:</b> cara, widgets y reproductor de Spotify.</sub></td>
    <td align="center"><sub><b>YouTube:</b> eliges el video tocándolo.</sub></td>
  </tr>
  <tr>
    <td><img src="docs/imagenes/monki-youtube.png" alt="Video de YouTube reproduciéndose; la interfaz tomó el color rojo del ánimo del video y el reproductor controla el video"></td>
    <td><img src="docs/imagenes/monki-detalle.png" alt="Detalle de la computadora al tocar su widget: CPU, RAM, disco, batería y tiempo encendida"></td>
  </tr>
  <tr>
    <td align="center"><sub><b>Video:</b> la interfaz toma el color de su ánimo y el reproductor lo controla.</sub></td>
    <td align="center"><sub><b>Detalle de un widget:</b> la computadora con CPU, RAM y disco.</sub></td>
  </tr>
  <tr>
    <td colspan="2" align="center"><img src="docs/imagenes/monki-lista.png" width="420" alt="Lista de canciones de lo que suena, con la canción actual marcada"><br><sub><b>Lista de canciones</b> de lo que suena; toca una para ponerla.</sub></td>
  </tr>
</table>

---

## Cómo funciona

### Cerebro: varias IA gratis en cadena

```
pedido ─► Groq ──► NVIDIA ──► OpenRouter ──► Gemini ──► modelo local (en la consola)
          rápido   más cupo   emergencia     ve imágenes  sin internet ni límites
```

- **Groq** (`gpt-oss-120b`, `qwen3.8-27b`, `gpt-oss-20b`) contesta primero en ~1.5 s.
- **Gemini** va primero solo cuando Monki necesita *ver* la pantalla.
- Ante 429, 413, 404, 410 o demora, pasa al siguiente modelo y luego al siguiente proveedor.
- **Modelo local:** Qwen2.5-0.5B con [llama.cpp](https://github.com/ggml-org/llama.cpp) compilado en la app. Conversa sin
  internet (~8 s por respuesta), sin herramientas.
- **Oído:** Vosk sin internet para *"hey monki"* y Whisper de Groq (~1 s) para entender el pedido.

### Voz

```
Edge (es-MX, generada en la Mac) ─► Gemini TTS ─► Piper sin internet (en la consola)
           ~1.5 s                                          ~3.5 s
```

Monki **nunca dice "Alexa"** en voz alta (la cambia por "la bocina") para no despertar los Echo. Las pronunciaciones de
nombres se configuran (`Oscar=Óscar`).

### Arquitectura

```
              "hey monki"
                   │
┌──────────────────▼────────────────────────────┐        ┌────────────────────────────────┐
│ Consola RG353V  (asistente-apk, Kotlin)       │  MCP   │ Mac o Windows  (casa, Python)  │
│                                               │ ─────► │   opcional                     │
│ Vosk → "hey monki"                            │  HTTP  │ casa.py  servidor MCP :8800    │
│ Groq / NVIDIA / Gemini → piensa y usa tools   │ +clave │  ├─ computadora, red, teles    │
│ llama.cpp (Qwen 0.5B) → último respaldo       │        │  └─ POST /voz  (voz Edge)      │
│ Edge / Gemini / Piper → voz                   │ ◄───── │                                │
│ AlexaClient → Echo (música, alarmas, volumen) │  voz   └────────────────────────────────┘
│ Spotify Web API → reproductor y Connect       │
│ WebView → YouTube en miniatura                │ ─────► Echo · Spotify · YouTube
│ Accesibilidad → ve y toca la pantalla         │
└───────────────────────────────────────────────┘
```

### Sin la computadora

| Funciona solo con la consola | Necesita `casa` encendido |
|---|---|
| "Hey monki", respuestas (Groq, NVIDIA, Gemini o local), internet, clima | Control de la Mac o la PC |
| Echo: música, alarmas con Spotify, volumen, adelantar, Bluetooth | Voz de Edge (sin ella usa Gemini o Piper) |
| Spotify: reproductor, lista de canciones, pasar la música entre equipos | Chromecast y celulares |
| YouTube, cara, ánimo, widgets, temporizadores, rutinas, memoria | |

---

## Instalación

**Necesitas:** una consola Android 11 con depuración USB (probado en RG353V), una Mac con Homebrew y al menos una clave
**gratis** de [Groq](https://console.groq.com/keys), [NVIDIA](https://build.nvidia.com) o
[Gemini](https://aistudio.google.com/apikey). Con varias, Monki casi nunca se queda sin respuesta.

### 1. App en la consola

```bash
git clone --recursive https://github.com/Niicko12/monki.git    # --recursive trae llama.cpp
cd monki
herramientas/descargar-modelo-vosk.sh          # modelo de voz para "hey monki" (~58 MB)

cd asistente-apk
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
"$ANDROID_HOME"/cmdline-tools/latest/bin/sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Permiso para prender y apagar el servicio de toques (ver docs/problemas-conocidos.md)
adb shell pm grant com.limber.asistente android.permission.WRITE_SECURE_SETTINGS

# Tus claves, tu nombre y tu ciudad (se guardan solo en la consola)
adb shell "am start -f 0x10008000 -n com.limber.asistente/.MainActivity \
  --es groq_key 'TU_CLAVE_GROQ' --es nvidia_key 'TU_CLAVE_NVIDIA' --es key 'TU_CLAVE_GEMINI' \
  --es user_name 'Tu nombre' --es location 'Tu ciudad, Estado, País'"
```

En la consola: **Ajustes → Accesibilidad → Monki → activar**, y dale permiso de micrófono.

### 2. Modelo local (recomendado)

```bash
curl -L -o qwen.gguf https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf
adb push qwen.gguf /data/local/tmp/llm.gguf
adb shell "run-as com.limber.asistente sh -c 'mkdir -p files && cp /data/local/tmp/llm.gguf files/llm.gguf'"
adb shell rm /data/local/tmp/llm.gguf
```

### 3. Spotify (opcional)

Crea una app gratis en el [panel de Spotify](https://developer.spotify.com/dashboard) con la Redirect URI
`http://127.0.0.1:8899/callback` y autoriza una vez (PKCE, sin *client secret*):

```bash
python3 herramientas/spotify-autorizar.py TU_CLIENT_ID
```

### 4. Echo de Alexa (opcional, no oficial)

Inicia sesión una vez con el puente y copia la sesión a la consola; después la Mac ya no hace falta:

```bash
cd casa/alexa && npm install && node alexa.js     # abre http://127.0.0.1:3456 e inicia sesión en Amazon
python3 herramientas/alexa-a-consola.py "Nombre de tu Echo"
```

Para el botón **🔊 Bocina**, empareja una vez: *"hey monki, empareja la consola con el Echo"*. Usa la API no oficial de
Amazon (la misma de [alexa-remote2](https://github.com/Apollon77/alexa-remote)) y puede dejar de funcionar si Amazon
cambia algo.

### 5. Hub `casa` en la computadora (opcional)

```bash
cd casa
uv run casa.py      # la primera vez crea .env con CASA_TOKEN
adb shell "am start -f 0x10008000 -n com.limber.asistente/.MainActivity --es mcp_servers 'casa|http://IP_DE_LA_MAC:8800/mcp|CASA_TOKEN'"
```

- **macOS:** arranque automático con `configuracion/launchd/com.limber.casa.plist`. Da permiso de *Accesibilidad* a
  Python para el teclado y el mouse.
- **Windows:** `powershell -ExecutionPolicy Bypass -File .\instalar-windows.ps1` como administrador.

### 6. Voz sin internet (opcional)

Instala el motor [sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html) con la voz `es_MX-ald-medium`
(arm64) y ponlo como motor del sistema.

Todos los ajustes están en [docs/configuracion.md](docs/configuracion.md).

---

## Uso

| Control | Acción |
|---|---|
| *"hey monki"* o **X** | Escucha |
| **B** | Interrumpe, cierra el video o el detalle |
| Cruceta + **A** | Elige widgets, videos, canciones y botones |
| Botones de volumen | Volumen del Echo o de la consola, según dónde suene |

**Ejemplos:** *"¿cómo estará el clima mañana?"* · *"pon una alarma a las 7 con Metallica"* · *"pon en YouTube Nirvana"* ·
*"pon música relajante en Spotify"* · *"siguiente canción"* · *"¿cómo está mi Mac?"* · *"ponte morado"* ·
*"crea una rutina 'a jugar' que suba el brillo a 80 y abra PPSSPP"*.

El nombre solo, sin "hey", despierta a Monki únicamente si se entiende muy claro, para que no se active con la tele, los
juegos o el propio video.

### Doble sistema: dArkOS en la SD

Graba [dArkOS](https://github.com/christianhaitian/dArkOS/releases) para RG353V en una SD con Raspberry Pi Imager y ponla
en **TF1**: con la SD arranca dArkOS; sin ella, Android con Monki. Los juegos van en TF2 (*Options → Advanced → Switch to
SD2 for Roms*). ROCKNIX actual no sirve: solo arranca si borras el Android interno.

---

## Estructura del repositorio

| Carpeta | Contenido |
|---|---|
| [`asistente-apk/`](asistente-apk) | App Android en Kotlin: Monki en la consola (llama.cpp como submódulo) |
| [`casa/`](casa) | Hub MCP en Python (FastMCP) para Mac o Windows, voz Edge y puente de Alexa en Node |
| [`agente/`](agente) | Versión de escritorio: el agente corre en la Mac y maneja la consola por adb |
| [`herramientas/`](herramientas) | Modelo Vosk, autorización de Spotify, sesión de Alexa y análisis de voz |
| [`configuracion/`](configuracion) | Plantillas de LaunchAgent (macOS) y controles de PPSSPP |
| [`docs/`](docs) | Documentación técnica |

## Documentación

- [Arquitectura](docs/arquitectura.md): componentes, flujo de un pedido y decisiones.
- [Configuración](docs/configuracion.md): todos los ajustes por adb.
- [Problemas conocidos](docs/problemas-conocidos.md): lo aprendido a golpes con esta consola.
- [Pendientes](docs/pendientes.md): lo que falta.

## Privacidad y seguridad

- Claves, nombre, ciudad, sesión de Amazon y permiso de Spotify se guardan **solo en la consola**, nunca en el código.
- `casa/.env`, `casa/alexa/alexa-cookie.json`, logs, modelos y APKs están en `.gitignore`. **No los compartas.**
- El hub `casa` exige su clave en cada petición y el puente de Alexa solo escucha en `127.0.0.1`.

## Créditos

- [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) y [Qwen2.5](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) (Apache 2.0): modelo local.
- [Vosk](https://alphacephei.com/vosk/) (Apache 2.0): palabra de activación.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) y [Piper](https://github.com/rhasspy/piper): voz sin internet.
- [edge-tts](https://github.com/rany2/edge-tts): voz neuronal de Microsoft Edge.
- [MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk), [alexa-remote2](https://github.com/Apollon77/alexa-remote), [PyChromecast](https://github.com/home-assistant-libs/pychromecast), [PyAutoGUI](https://github.com/asweigart/pyautogui).
- Inspirado en [xiaozhi-esp32](https://github.com/78/xiaozhi-esp32).

<div align="center">

Código bajo licencia [MIT](LICENSE).

</div>
