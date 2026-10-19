# Configuración

## Monki en la consola

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
| `color` | Color de la cara: verde, azul, cian, morado, rosa, rojo, naranja, amarillo, blanco, `#RRGGBB` o `ánimo` | `verde` |
| `animo` | Fija el ánimo 30 min: emocionado, feliz, tranquilo, aburrido, cansado, triste o molesto | (no se guarda) |
| `pronunciar` | Palabras que la voz lee mal, una por línea (`\n`): `Oscar=Óscar` | vacío |
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

Para compilar hacen falta el NDK `27.2.12479018` y CMake `3.22.1` del SDK de Android, y el submódulo (`git submodule update --init`).

## `casa` (Mac o Windows)

| Archivo | Para qué |
|---|---|
| `casa/.env` | `CASA_TOKEN=…`; se crea solo, no se sube |
| `configuracion/launchd/com.limber.casa.plist` | Arranque automático en macOS (cambia `$HOME` por tu ruta y cópialo a `~/Library/LaunchAgents/`) |
| `casa/instalar-windows.ps1` | Instalación en Windows |

Dependencias nuevas: `edge-tts` (voz) y `pyautogui` (teclado y ratón). En macOS, teclado y ratón requieren permiso de **Accesibilidad** para `uv`/`python`. `pc_captura` y `pc_raton` usan solo la pantalla principal.

Si la Mac cambia de IP, Monki deja de alcanzar `casa`. Vuelve a mandar `mcp_servers` con la IP nueva, o reserva la IP en el router.

## Alexa

| Variable o archivo | Para qué |
|---|---|
| `ALEXA_PRINCIPAL` | Nombre del Echo por defecto, tal como lo da `alexa_dispositivos` |
| `casa/alexa/alexa-cookie.json` | Sesión de Amazon. **Secreto**: no lo subas ni lo compartas |
| `configuracion/launchd/com.limber.alexa.plist` | Arranque automático del puente |

Para volver a iniciar sesión, borra `alexa-cookie.json`, reinicia el puente y abre `http://127.0.0.1:3456`.

## PPSSPP

`configuracion/ppsspp/controls.ini` mapea los botones para los jugadores 1 a 3 (dispositivos 10, 11 y 12), y la cruceta también como teclas de Android (19 a 22). Va en `/storage/emulated/0/PSP/SYSTEM/controls.ini`. Cópialo **con PPSSPP cerrado**, porque PPSSPP reescribe el archivo al salir.
