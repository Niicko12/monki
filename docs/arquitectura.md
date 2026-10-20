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
  → si el pedido fue por voz y no suena música, vuelve a escuchar (seguimiento: espera 4 s, graba máx. 10 s);
    luego a la palabra de activación
```

Cada paso deja su tiempo en el log: `adb logcat -s Tiempos`. Un turno típico: ~1 s de transcripción, ~1.5 s de respuesta y ~1.5 s hasta que suena la voz.

### Proveedores (`Agent.think`)

1. Si en la conversación hay una captura de pantalla: Gemini primero (es el único que ve imágenes).
2. Groq → NVIDIA → OpenRouter, cada uno con varios modelos. Cualquier falla (429, 413, 404, 410, 5xx, tiempo) pasa al siguiente.
3. Gemini (si no se usó en el paso 1). Tras fallar no se reintenta en 10 minutos.
4. Modelo local, si existe `files/llm.gguf`.

Para los proveedores con formato OpenAI se mandan solo los últimos 8 mensajes, los resultados viejos de herramientas se recortan a 300 letras, las descripciones de herramientas a 120 y `max_tokens` a 700. Groq da 8 000 tokens por minuto por modelo y cuenta los tokens de respuesta reservados: sin esos recortes responde 413. NVIDIA no acepta puntos en los nombres de herramientas: `internet.clima` viaja como `internet__clima`.

### Puente de Alexa y Spotify en la consola

- `AlexaClient.kt` hace lo mismo que `casa/alexa/alexa.js` contra `pitangui.amazon.com` con la sesión de Amazon copiada a la consola (`herramientas/alexa-a-consola.py`): órdenes de texto y volumen (`/api/behaviors/preview`), qué suena (`/api/np/list-media-sessions`), salto (`SeekCommand`, en segundos), alarmas (`/api/notifications`) y renovación de la sesión con el refresh token (`www.amazon.com.mx/ap/exchangetoken/cookies` + csrf). Expone las mismas herramientas `alexa_*`; si está configurado, Monki oculta las de `casa`. `echoCall()` usa el local y, si falla, el de `casa`.
- `Spotify.kt`: Web API con PKCE (permiso de `herramientas/spotify-autorizar.py`, dura 180 días): dispositivos, transferir la reproducción, qué suena, play/pausa/siguiente/anterior/seek/volumen y tocar una canción dentro de su lista. La lista de canciones se lee de la página pública para insertar (`open.spotify.com/embed/…`).
- El DNS de la consola es `dns.google` (DNS privado de Android): el del router no resolvía `i.scdn.co` (portadas).

### Modelo local

`app/src/main/cpp/llama.cpp` es un submódulo de git (versión fija). `CMakeLists.txt` lo compila estático dentro de `libmonki_llm.so` para `armv8.2-a+dotprod+fp16` (Cortex-A55). `monki_llm.cpp` expone cargar, conversar (con la plantilla de chat del modelo), detener y liberar. Con Qwen2.5-0.5B Q4_0: ~8 s de carga y ~7-8 s por respuesta; Qwen3.5-0.8B tardaba 20-30 s. El modelo se libera tras 5 minutos sin uso.

### Cara, ánimo y pantalla

- `Mood` guarda alegría (−10 a 10) y molestia (0 a 10). Cada respuesta las mueve según su emoción y vuelven solas a "tranquilo" (1 punto cada 15 min). Noche o batería ≤15 % = cansado; 45 min sin uso = aburrido.
- Durante 30 s la cara muestra la emoción de la última respuesta; después, el ánimo.
- Con música sonando, Groq clasifica cada canción nueva por título y artista; la cara usa ese ánimo y su color, y se mece si es feliz o emocionado.
- Widgets cada 15 s; clima cada 20 min (wttr.in); computadoras cada minuto (`pc_info formato=json`: modelo, batería, disco, CPU, RAM); reproductor cada 10 s (`alexa_reproduciendo`). Los widgets y los botones principales usan el color del ánimo (el de la canción si suena música).
- Las alarmas que pides a Monki van al Echo (`alexa_alarma`); el widget muestra la próxima del Echo y, al tocarlo, todas.
- Al tocar el reproductor aparece la lista de canciones de lo que suena (`spotify_lista`: `casa` lee la página pública de Spotify para insertar listas, `open.spotify.com/embed/…`); tocar una canción se la pide al Echo.
- **Volumen y salida de sonido:** el botón "📺 Consola / 🔊 Bocina" de arriba cambia por dónde sale el sonido de la consola (YouTube, juegos): su bocina o el Echo por Bluetooth. YouTube suena donde esté elegido. Los botones de volumen ajustan el Echo solo si suena Spotify en él; si no, el volumen de la consola (que llega al Echo si está por Bluetooth). Al presionarlos se ve el destino y el nivel. `audio.salida` (y el botón) cambia la salida: **Bocina** = prende el Bluetooth, empareja solo si no hay vínculo (`audio.emparejar`) y le pide al Echo "conéctate a <nombre Bluetooth de la consola>"; mientras conecta acepta solo la pregunta "¿Vincular?" que a veces lanza el Echo (si nadie la acepta, Android borra el vínculo). **Consola** = "desconéctate de <nombre>" y, si en 4 s no se desconecta, apaga el Bluetooth (en esta consola eso borra el emparejamiento; al volver a la bocina se rehace solo). El botón ignora toques mientras cambia. La voz de Monki siempre sale por la consola.
- `audio.emparejar` empareja la consola con el Echo una vez: pone al Echo en modo de emparejamiento, lo busca (nombre con "Echo"), llama a `createBond()`, oculta el panel para que se vea la pregunta de Android y la acepta tocando "Vincular" con el servicio de accesibilidad. Requiere `ACCESS_FINE_LOCATION` (por adb: `pm grant … ACCESS_FINE_LOCATION`). Por adb: `--es audio emparejar|bocina|consola`.
- El reproductor se oculta si Spotify lleva más de 1 minuto en pausa o se detuvo.
- **YouTube en la consola** (sin la Mac): `youtube.reproducir` busca en la página de resultados de YouTube (con user agent de escritorio: la versión móvil trae los datos escapados) y muestra hasta 8 resultados con miniatura (`i.ytimg.com`) en el lugar de la cara; el usuario toca uno. El video va en un WebView con el reproductor oficial para insertar, cargado con un origen propio (`https://monki.app/`) para que YouTube reciba un *referer*. Si el dueño prohíbe insertarlo (error 101/150, avisado por una interfaz JavaScript), Monki avisa y vuelve a la lista. Se cierra con B, con ✕ o pidiéndolo (`youtube.cerrar`).
- **El reproductor de abajo controla el video** mientras está abierto: lee `currentTime`, `duration` y el estado del reproductor cada segundo; ⏯ pausa, ⏮ ⏭ saltan 10 s y la barra hace `seekTo`.
- **Una sola cosa suena a la vez:** abrir un video manda la tecla de pausa del sistema y pausa Spotify por la API (esté en la consola o en el Echo); darle play a Spotify en la consola pausa el video.
- **Ánimo del video:** Groq clasifica el título (bailable, alegre, tranquilo, triste o pesado) y ese ánimo manda sobre el de la música mientras el video está abierto. Con un video abierto la palabra de activación queda en modo estricto.
- Cara: respira (ojos y flotación con un seno de 3.5 s), destello de luz en los ojos sin párpado, rebote y parpadeo al cambiar de expresión, color que se desvanece, parpadeo doble ocasional, puntitos al pensar, "z" al dormir y corazones que laten. Todo a ~30 cuadros por segundo.
- Al escribir con el teclado aparece "← Volver" y se ocultan "Hablar" y el reproductor.
- Al tocar un widget (o A con la cruceta) su detalle aparece en el lugar de la cara: estado de la consola, clima y pronóstico, alarmas o la información completa de la computadora. Se cierra solo a los 20 s, tocándolo o con B.
- Reproductor: portada (vía `casa`), fondo con el color de la portada, barra arrastrable (o cruceta) que llama a `alexa_saltar`, y los botones de volumen de la consola controlan el Echo (`alexa_volumen`, de 5 en 5) mientras el panel está abierto y suena música.
- El panel es de pantalla completa: la ventana usa `fitInsetsTypes = 0` y `FLAG_LAYOUT_IN_SCREEN`, y oculta la barra de estado de Android con `WindowInsetsController` (vuelve deslizando desde arriba). Sin eso empezaba debajo de la barra (y = 24) y la fila de abajo quedaba fuera.
- Los detalles de un widget se cierran solos a los 10 s, tocándolos o con cualquier botón.

### Palabra de activación

Vosk con el modelo pequeño de español no conoce "monki". La gramática acepta lo que el modelo entiende al oírlo: `monkey`, `mon qui`, `mon ki`, `moni`, `qui`, `mon`.

- Con "hey" delante, aunque llegue en otra frase dentro de 3 s, basta una seguridad de 0.3.
- **Mientras Monki habla** (y 1.5 s después) su voz entra al micrófono: solo cuenta "hey" (≥0.5) seguido del nombre (≥0.35), y nunca "mon" suelto.
- Con "hey" delante, `mon` necesita 0.5 (es una sílaba muy común).
- Sin "hey": `monkey`, `mon qui` o `mon ki` desde 0.8.
- `mon`, `qui` y `moni` sueltos nunca lo despiertan: con el micrófono de la consola salían del ruido del cuarto y de los juegos.
- La ganancia es automática, hasta ×4, para no saturar la voz.
- Cada vez que oye algo, guarda los últimos 4 s en `Android/data/com.limber.asistente/files/wake-debug.wav`, para analizarlos con `herramientas/voz/`.

## Hub `casa`

`casa/casa.py` es un servidor MCP (FastMCP, Streamable HTTP, sin estado) en `0.0.0.0:8800/mcp`. Exige `Authorization: Bearer CASA_TOKEN`, y la clave se genera en `.env` la primera vez. Funciona en macOS y en Windows (`instalar-windows.ps1`).

Herramientas: `red_escanear`; `pc_*`: estado, info, captura, abrir app o URL, apps abiertas, volumen, silencio, brillo, decir, notificar, música, teclado, ratón, portapapeles, ventanas, archivos y energía (reiniciar y apagar piden `confirmar=true`); `tv_*` (Chromecast/YouTube); `android_*` (celulares por adb); `focos_estado`; `alexa_*`: comando, decir, dispositivos, reproduciendo, control, saltar, volumen, alarma, alarmas y borrar_alarma; `spotify_lista` (canciones de una lista o álbum de Spotify).

Además de MCP, `casa` atiende `POST /voz` (con la misma clave): recibe `{"texto": …}` y devuelve un MP3 con la voz neuronal de Edge (`es-MX-JorgeNeural`, tono −6 Hz) generada con `edge-tts`. También atiende `POST /portada` (`{"url": …}`): descarga la portada de la canción para la consola, solo desde hosts de portadas de Spotify y Amazon.

### Puente de Alexa

`casa/alexa/alexa.js` usa `alexa-remote2` con Amazon México (`amazon.com.mx`, servidor `pitangui.amazon.com`).

- Escucha solo en `127.0.0.1:8801`. Rutas: `/estado`, `/dispositivos`, `/comando` (manda texto al Echo como si le hablaras), `/decir`, `/reproduciendo`, `/control`, `/saltar`, `/volumen`, `/alarmas`, `/alarma` y `/alarma-borrar`.
- Alarmas: por orden de texto Alexa **no** las crea. `/alarma` usa la API de notificaciones: sin música, el formato nuevo (`/v1/alerts/alarms`); con música, el clásico (`PUT /api/notifications/null` con `type: MusicAlarm`, `musicEntity` y `provider: Spotify`), porque el nuevo ignora la música.
- `/reproduciendo` usa `/api/np/list-media-sessions`: el `getPlayerInfo` clásico devuelve vacío cuando suena Spotify. `/control` manda órdenes de texto ("pausa", "siguiente canción"), porque `/api/np/command` no controla esas sesiones. `/saltar` sí usa `SeekCommand` de `/api/np/command`, con `mediaPosition` en **segundos** (en milisegundos se pasa del final y salta a la siguiente canción). `/volumen` recuerda el último nivel puesto, porque `getMedia` tarda en reflejarlo.
- El inicio de sesión se hace en un proxy local (`127.0.0.1:3456`) y la sesión queda en `alexa-cookie.json`, con permisos 600.
- El log filtra cookies y tokens.
- `ALEXA_PRINCIPAL` indica qué Echo usar cuando no se nombra ninguno.

## Agente de escritorio (`agente`)

`agente/agente.py` es la primera versión: corre en la Mac con la API de Gemini y maneja la consola por adb, con capturas, toques y escritura. Hoy la app de la consola hace lo mismo sin necesitar la Mac.
