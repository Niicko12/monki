# Configuración

## Monki en la consola

### Desde el celular

La pantalla de configuración muestra un **código QR**. Escanéalo con el celular (misma WiFi) y se abre un formulario
para pegar las claves de IA, tu nombre, tu ciudad y el Client ID de Spotify. Lo sirve la propia consola
(`SetupServer.kt`, puerto 8790) **solo mientras la pantalla de configuración está abierta**, y cada petición debe traer
la clave aleatoria que va en el QR. Un campo vacío no borra lo que ya estaba guardado.

### Por adb

Todo se puede poner desde la pantalla de ajustes de la app o por adb, sin recompilar:

```bash
adb shell "am start -f 0x10008000 -n com.limber.asistente/.MainActivity --es <ajuste> '<valor>'"
```

`-f 0x10008000` crea la pantalla de nuevo para que lea los valores aunque ya esté abierta.

| Ajuste | Qué es | Por defecto |
|---|---|---|
| `groq_key` | Clave de Groq (agente y Whisper) | (ninguna) |
| `nvidia_key` | Clave de NVIDIA NIM | (ninguna) |
| `openrouter_key` | Clave de OpenRouter (modelos `:free`) | (ninguna) |
| `key` | Clave de la API de Gemini | (ninguna) |
| `model` | Modelo para pensar | `gemini-3.5-flash-lite` (con respaldos ante 429) |
| `tts_model` | Modelo de voz de Gemini | `gemini-3.8-flash-lite-tts` |
| `user_name` | Tu nombre: te dice "señor ‹nombre›" | vacío: solo "señor" |
| `location` | Tu ciudad: clima y respuestas locales | vacío: ubicación por IP |
| `persona` | Personalidad (texto libre) | Mayordomo IA educado y con humor seco |
| `name` | Nombre que muestra | `Monki` |
| `wake_word` | Palabra de activación (debe existir en el vocabulario de Vosk) | `monkey` |
| `mcp_servers` | Servidores MCP, uno por línea: `alias\|url\|clave` | vacío |
| `wol` | Equipos para Wake-on-LAN, uno por línea: `nombre\|MAC\|IP` | vacío |
| `color` | Color de Monki (cara, widgets y botones): `ánimo`, o fijo: verde, azul, cian, morado, rosa, rojo, naranja, amarillo, blanco o `#RRGGBB` | `ánimo` |
| `animo` | Fija el ánimo 30 min: emocionado, feliz, tranquilo, aburrido, cansado, triste o molesto | (no se guarda) |
| `pronunciar` | Palabras que la voz lee mal, una por línea (`\n`): `Oscar=Óscar` | vacío |
| `spotify_client_id`, `spotify_refresh` | Permiso de Spotify (lo pone **Conectar** en la configuración) | vacío |
| `alexa_cookie`, `alexa_csrf`, `alexa_refresh`, `alexa_principal` | Sesión de Amazon y Echo principal (lo pone **Iniciar sesión** en la configuración) | vacío |
| `audio` | Salida de sonido: `consola`, `bocina` o `emparejar` | (no se guarda) |
| `decir` | Prueba de voz: dice ese texto | (no se guarda) |
| `pedir` | Prueba: hace ese pedido escrito | (no se guarda) |
| `sin_gemini` (`--ez … true`) | Prueba: salta Gemini 10 minutos | (no se guarda) |
| `solo_local` (`--ez … true`) | Prueba: responde solo el modelo local | (no se guarda) |

Permiso extra (una vez, o tras desinstalar y reinstalar):

```bash
adb shell pm grant com.limber.asistente android.permission.WRITE_SECURE_SETTINGS
```

### Rutinas

Se crean y borran por voz ("crea una rutina…", "borra la rutina…"). Vienen dos de ejemplo:

- **buenas noches**: le pide a Alexa que apague las luces; baja el brillo a 10 y el volumen a 20; se despide.
- **buenos días**: dice la hora y el clima; le pide a Alexa que encienda las luces.

### Voz sin internet

1. Instala el APK del motor sherpa-onnx con una voz en español (probado: `vits-piper-es_MX-ald-medium`, arm64).
2. Ponlo como motor de voz del sistema: `adb shell settings put secure tts_default_synth com.k2fsa.sherpa.onnx.tts.engine`.
3. La primera carga tarda entre 20 y 35 s. Monki la hace al arrancar.

La voz suena 10 % más grave y lenta (estilo Jarvis): se baja la frecuencia de muestreo del WAV. La constante es `DEEPEN` en `OfflineVoice.kt`.

### Modelo local

El archivo va en la memoria interna de la app (`files/llm.gguf`). En Android 11, adb no puede escribir de verdad en `Android/data/…` (dice que copió, pero el archivo no aparece), por eso se copia con `run-as`:

```bash
adb push modelo.gguf /data/local/tmp/llm.gguf
adb shell "run-as com.limber.asistente sh -c 'mkdir -p files && cp /data/local/tmp/llm.gguf files/llm.gguf'"
adb shell rm /data/local/tmp/llm.gguf
```

También se descarga desde la pantalla de configuración (botón **Descargar**). Para compilar hacen falta el NDK `27.2.12479018` y CMake `3.22.1` del SDK de Android, y el submódulo (`git submodule update --init`).

## Spotify

Desde la pantalla de configuración de la app: pega el **Client ID** de una app gratis de
[Spotify for Developers](https://developer.spotify.com/dashboard) con la Redirect URI `http://127.0.0.1:8899/callback`
y toca **Conectar**. Se abre el inicio de sesión de Spotify dentro de Monki (PKCE, sin *client secret*) y el permiso
queda guardado en la consola.

## Echo de Alexa

En la pantalla de configuración, **Conexiones → Echo de Alexa → Iniciar sesión**: se abre el inicio de sesión de
Amazon dentro de Monki (como la app de Alexa: PKCE y registro en `api.amazon.com/auth/register`, igual que
alexa-cookie2). El permiso queda en la consola y la sesión se renueva sola. Después:

- **Elegir Echo**: el Echo que usa por defecto (si no eliges, toma el primero en línea).
- **Emparejar Bluetooth**: una vez, para el botón **🔊 Bocina**.

Usa la API no oficial de Amazon (la misma de [alexa-remote2](https://github.com/Apollon77/alexa-remote)); puede dejar
de funcionar si Amazon cambia algo.
