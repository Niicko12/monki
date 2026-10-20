# Problemas conocidos y lo aprendido

Cosas que costó encontrar en la RG353V con Android 11. Cada una dice qué pasaba, por qué y cómo quedó.

## La cruceta y el stick dejan de funcionar en todos los emuladores

**Qué pasaba:** los botones respondían, pero la cruceta y el stick no, en PPSSPP, en Neo Geo y en los demás emuladores.

**Por qué:** si **cualquier** servicio de accesibilidad tiene `canPerformGestures="true"`, Android instala su filtro de entrada (`AccessibilityInputFilter`). Los eventos del control se reinyectan como un dispositivo "Virtual", y los de joystick (ejes y cruceta HAT) se pierden. PPSSPP incluso registra ese dispositivo "Virtual" como jugador 1.

**Cómo quedó:** el servicio principal (`AgentService`) no tiene gestos. Un segundo servicio, `GestureService` ("Monki (toques)"), sí los tiene: Monki lo prende escribiendo en `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, con el permiso `WRITE_SECURE_SETTINGS` concedido por adb, y lo apaga 8 s después del último toque.

## El reproductor de Rockchip se traba si cambias velocidad o tono

**Qué pasaba:** después de hacer la voz más grave, Monki dejó de hablar.

**Por qué:** en esta consola `MediaPlayer` usa el reproductor `rockit` de Rockchip. Con `setPlaybackParams()` a una velocidad distinta de 1 se queda en estado `started`: no suena y nunca avisa que terminó.

**Cómo quedó:** no se usa `setPlaybackParams`. Para hacer la voz más grave se reescribe la frecuencia de muestreo en la cabecera del WAV.

## La cuota gratis de voz de Gemini se acaba

**Qué pasaba:** Monki entendía y respondía, pero se quedaba callado.

**Por qué:** los modelos TTS de Gemini tienen muy poca cuota gratis al día y responden 429. La suscripción Gemini Pro (app) **no** aplica a la API.

**Cómo quedó:** la voz principal es Edge (generada por `casa`); si no responde, Gemini; si falla (429), Piper sin internet, y no vuelve a intentar con Gemini en 30 minutos.

## Monki esperaba 8 s después de cada pedido por voz

**Qué pasaba:** todas las grabaciones duraban exactamente 8 s, aunque el pedido fuera corto.

**Por qué:** el zumbido de 60/180 Hz del micrófono es casi tan fuerte como la voz. El detector medía la energía total, nunca notaba que la voz empezaba o terminaba y cortaba por el máximo de 8 s.

**Cómo quedó:** el detector mide la energía después de un filtro paso alto de 300 Hz (dos biquads). Ahí la voz queda 3 a 5 veces por encima del ruido y corta 0.9 s después de que terminas. El audio que se manda a transcribir no se filtra.

## Monki se activaba con su propia voz

**Qué pasaba:** mientras hablaba, a veces pasaba a "Entendiendo…" y hacía cosas que nadie pidió.

**Por qué:** la palabra de activación sigue escuchando mientras habla (para poder interrumpirlo) y su voz por la bocina sonaba a "mon…".

**Cómo quedó:** modo estricto mientras habla y 1.5 s después: hace falta "hey" antes del nombre. No subas mucho los umbrales: un "hey monkey" real llega con ~0.45 de seguridad en el nombre.

## Si Monki dice "Alexa", se activa el Echo

La voz cambia "Alexa" por "la bocina" antes de hablar, y el *prompt* le prohíbe decirla.

## Groq respondía 413 con conversaciones largas

Groq limita a 8 000 tokens por minuto por modelo y cuenta los tokens de respuesta reservados. Ver [Proveedores](arquitectura.md#proveedores-agentthink): se recorta el historial y se fija `max_tokens`.

## Cambiar el idioma del motor de voz recarga el modelo

Con el motor sherpa-onnx, cada `tts.language = …` recarga el modelo completo, unos 4 s. Hay que fijar el idioma una sola vez, al inicializar.

## "hey monki" con un micrófono malo

El micrófono por el conector de audífonos de la consola trae zumbido eléctrico de 60 y 180 Hz y casi nada por encima de 1 kHz. Ahí están las consonantes de "monki", por eso el modelo pierde el "qui" o el "hey".

- Filtros (pasa-altos, *notch* y realce) **no** mejoraron el reconocimiento con audio real. Para probar, usa `herramientas/voz/`.
- Lo que sí funcionó: más variantes en la gramática, recordar el "hey" entre frases y umbrales por variante (ver [arquitectura](arquitectura.md#palabra-de-activación)).
- Si hay zumbido, prueba la consola desconectada del cargador.

## Otros

- **PPSSPP de fábrica** (1.11.3, modificado por Anbernic) está firmado distinto al oficial: para actualizarlo hay que desinstalarlo primero. Las partidas en `/storage/emulated/0/PSP` se conservan.
- **Zona horaria:** Android 11 trae datos viejos y aplica horario de verano en México, que se eliminó en 2022. Usa una zona fija: `adb shell service call alarm 3 s16 Etc/GMT+6`.
- **Ubicación por IP:** con doble NAT puede salir otra ciudad. Configura `location`.
- **Selector de carpetas de Android:** ignora `adb input tap`; solo responde a la cruceta.
- **`am force-stop com.limber.asistente` apaga la accesibilidad** (`accessibility_enabled` queda en 0). Para recuperarla: vaciar `enabled_accessibility_services`, volver a poner el servicio y `accessibility_enabled 1`.
- **adb y `Android/data` en Android 11:** `adb push` dice que copió pero el archivo no aparece. Usa `/data/local/tmp` y `run-as`.
- **wttr.in con user agent de navegador** devuelve HTML en vez de texto: el widget usa el de curl.
- **`casa` colgado tras reiniciarlo:** con `uv run casa.py` en el LaunchAgent, `launchctl kickstart -k` mataba a `uv` pero no al Python hijo, que se quedaba con el puerto 8800 sin responder (`address already in use` en el log). El LaunchAgent ahora lanza `.venv/bin/python casa.py` directo.
- **Alexa no crea alarmas por orden de texto:** "pon una alarma…" enviado como texto no hace nada. `alexa.js` usa la API de notificaciones (ver [arquitectura](arquitectura.md)).
- **El DNS del router no resuelve `i.scdn.co`** (portadas de Spotify) desde la consola, aunque la Mac sí: la portada se pide a `casa` (`POST /portada`).
- **Spotify en los Echo:** `getPlayerInfo` devuelve todo vacío; la información está en `/api/np/list-media-sessions`.
