# Arquitectura

## App de la consola (`asistente-apk`)

Kotlin, sin dependencias de UI externas, `minSdk 30` (Android 11). Todo vive en `app/src/main/java/com/limber/asistente/`:

| Archivo | Qué hace |
|---|---|
| `AgentService.kt` | Servicio de accesibilidad principal: interfaz flotante (burbuja, panel con cara, widgets y reproductor), micrófono, voz, palabra de activación, ánimo y las herramientas de la consola (`Controls`) |
| `Agent.kt` | Bucle del agente: arma el *system prompt* (persona, nombre, ubicación, ánimo, memoria, rutinas), elige proveedor (`think`), llama herramientas y devuelve la respuesta con una emoción |
| `OpenAiCompat.kt` | Respaldos con formato OpenAI (Groq, NVIDIA, OpenRouter): traduce el historial Gemini ⇄ OpenAI, recorta lo viejo, Whisper y preguntas rápidas |
| `LocalLlm.kt` + `cpp/monki_llm.cpp` | Modelo local con llama.cpp por JNI: último respaldo, sin herramientas |
| `Gemini.kt` | Cliente REST de Gemini: generar con modelos de respaldo ante 429, transcribir audio y voz (TTS) |
| `Mood.kt` | Estado de ánimo persistente y colores de la cara |
| `WakeWord.kt` | "hey monki" sin internet con Vosk y una gramática cerrada (ver más abajo) |
| `VoiceRecorder.kt` | Graba hasta 0.9 s de silencio; detecta la voz con un filtro paso alto de 300 Hz que quita el zumbido del micrófono |
| `OfflineVoice.kt` | Voz de respaldo con el motor Android de sherpa-onnx (Piper `es_MX`) |
| `GestureService.kt` | Segundo servicio de accesibilidad, **solo** para tocar la pantalla; se prende y apaga solo |
| `Tools.kt` | Herramientas `self.*`: pantalla, botones, apps, volumen, brillo |
| `Web.kt` | `internet.buscar` (DuckDuckGo), `internet.leer`, `internet.clima` (wttr.in) |
| `Extras.kt` | Alarmas y recordatorios, memoria a largo plazo, rutinas |
| `WakeOnLan.kt` | Prender computadoras de la red |
| `McpClient.kt` / `McpServer.kt` | Cliente MCP (para usar `casa`) y servidor MCP propio en el puerto 8765 (para que otros usen la consola) |
| `EyesView.kt` | Cara animada: ojos con 20 expresiones, boca que sigue la voz y vaivén con música alegre |
| `MainActivity.kt` | Ajustes, y configuración por `adb am start --es …` |
| `Prefs.kt` | Preferencias y valores por defecto |

### Un turno por voz

```
palabra de activación (Vosk) → aviso sonoro → graba hasta 0.9 s de silencio
  → Whisper (Groq) transcribe; si tarda más de 8 s o falla, Gemini
  → responde el primer proveedor disponible, llamando herramientas las veces que haga falta
  → voz: Edge (desde casa); si no, Gemini TTS; si no, Piper sin internet, por oraciones
  → vuelve a escuchar unos segundos (seguimiento) y luego a la palabra de activación
```

Cada paso deja su tiempo en el log: `adb logcat -s Tiempos`. Un turno típico: ~1 s de transcripción, ~1.5 s de respuesta y ~1.5 s hasta que suena la voz.

### Proveedores (`Agent.think`)

1. Si en la conversación hay una captura de pantalla: Gemini primero (es el único que ve imágenes).
2. Groq → NVIDIA → OpenRouter, cada uno con varios modelos. Cualquier falla (429, 413, 404, 410, 5xx, tiempo) pasa al siguiente.
3. Gemini (si no se usó en el paso 1). Tras fallar no se reintenta en 10 minutos.
4. Modelo local, si existe `files/llm.gguf`.

Para los proveedores con formato OpenAI se mandan solo los últimos 8 mensajes, los resultados viejos de herramientas se recortan a 300 letras, las descripciones de herramientas a 120 y `max_tokens` a 700. Groq da 8 000 tokens por minuto por modelo y cuenta los tokens de respuesta reservados: sin esos recortes responde 413. NVIDIA no acepta puntos en los nombres de herramientas: `internet.clima` viaja como `internet__clima`.

### Modelo local

`app/src/main/cpp/llama.cpp` es un submódulo de git (versión fija). `CMakeLists.txt` lo compila estático dentro de `libmonki_llm.so` para `armv8.2-a+dotprod+fp16` (Cortex-A55). `monki_llm.cpp` expone cargar, conversar (con la plantilla de chat del modelo), detener y liberar. Con Qwen2.5-0.5B Q4_0: ~8 s de carga y ~7-8 s por respuesta; Qwen3.5-0.8B tardaba 20-30 s. El modelo se libera tras 5 minutos sin uso.

### Cara, ánimo y pantalla

- `Mood` guarda alegría (−10 a 10) y molestia (0 a 10). Cada respuesta las mueve según su emoción y vuelven solas a "tranquilo" (1 punto cada 15 min). Noche o batería ≤15 % = cansado; 45 min sin uso = aburrido.
- Durante 30 s la cara muestra la emoción de la última respuesta; después, el ánimo.
- Con música sonando, Groq clasifica cada canción nueva por título y artista; la cara usa ese ánimo y su color, y se mece si es feliz o emocionado.
- Widgets cada 15 s; clima cada 20 min (wttr.in); computadoras cada minuto (`pc_info corto=true`); reproductor cada 10 s (`alexa_reproduciendo`).

### Palabra de activación

Vosk con el modelo pequeño de español no conoce "monki". La gramática acepta lo que el modelo entiende al oírlo: `monkey`, `mon qui`, `mon ki`, `moni`, `qui`, `mon`.

- Con "hey" delante, aunque llegue en otra frase dentro de 3 s, basta una seguridad de 0.3.
- **Mientras Monki habla** (y 1.5 s después) su voz entra al micrófono: solo cuenta "hey" (≥0.5) seguido del nombre (≥0.35), y nunca "mon" suelto.
- Sin "hey": `mon qui` o `mon ki` desde 0.5, `mon` o `monkey` desde 0.7.
- `qui` y `moni` sueltos nunca lo despiertan, porque el ruido de los juegos los produce.
- La ganancia es automática, hasta ×4, para no saturar la voz.
- Cada vez que oye algo, guarda los últimos 4 s en `Android/data/com.limber.asistente/files/wake-debug.wav`, para analizarlos con `herramientas/voz/`.

## Hub `casa`

`casa/casa.py` es un servidor MCP (FastMCP, Streamable HTTP, sin estado) en `0.0.0.0:8800/mcp`. Exige `Authorization: Bearer CASA_TOKEN`, y la clave se genera en `.env` la primera vez. Funciona en macOS y en Windows (`instalar-windows.ps1`).

Herramientas: `red_escanear`; `pc_*`: estado, info, captura, abrir app o URL, apps abiertas, volumen, silencio, brillo, decir, notificar, música, teclado, ratón, portapapeles, ventanas, archivos y energía (reiniciar y apagar piden `confirmar=true`); `tv_*` (Chromecast/YouTube); `android_*` (celulares por adb); `focos_estado`; `alexa_*`: comando, decir, dispositivos, reproduciendo y control.

Además de MCP, `casa` atiende `POST /voz` (con la misma clave): recibe `{"texto": …}` y devuelve un MP3 con la voz neuronal de Edge (`es-MX-JorgeNeural`, tono −6 Hz) generada con `edge-tts`.

### Puente de Alexa

`casa/alexa/alexa.js` usa `alexa-remote2` con Amazon México (`amazon.com.mx`, servidor `pitangui.amazon.com`).

- Escucha solo en `127.0.0.1:8801`. Rutas: `/estado`, `/dispositivos`, `/comando` (manda texto al Echo como si le hablaras), `/decir`, `/reproduciendo` y `/control`.
- `/reproduciendo` usa `/api/np/list-media-sessions`: el `getPlayerInfo` clásico devuelve vacío cuando suena Spotify. `/control` manda órdenes de texto ("pausa", "siguiente canción"), porque `/api/np/command` no controla esas sesiones.
- El inicio de sesión se hace en un proxy local (`127.0.0.1:3456`) y la sesión queda en `alexa-cookie.json`, con permisos 600.
- El log filtra cookies y tokens.
- `ALEXA_PRINCIPAL` indica qué Echo usar cuando no se nombra ninguno.

## Agente de escritorio (`agente`)

`agente/agente.py` es la primera versión: corre en la Mac con la API de Gemini y maneja la consola por adb, con capturas, toques y escritura. Hoy la app de la consola hace lo mismo sin necesitar la Mac.
