# Pendientes

## En curso

- [ ] Probar a fondo "hey monki" mientras Monki habla (modo estricto) con ruido de juego.
- [ ] Instalar `casa` en la PC con Windows y conectarla a la consola.
- [ ] Permiso de Accesibilidad en macOS para que `pc_teclado` y `pc_raton` funcionen.

## Ideas

- [ ] **Muse Gadget SDK (Meta):** convertir a Monki en un "gadget" de Muse para usarlo como otro cerebro con voz y que Muse controle la consola. Requiere cuenta y token de Muse, emparejar por Bluetooth LE (falta comprobar si la consola puede anunciarse como periférico) y portar a Kotlin el cifrado Noise XX, el emparejamiento v5 y el canal `/link-control` (~2 500 líneas de Python). No exponer `system.run`; solo las herramientas de Monki.
- [ ] **OpenClaw** en la Mac como agente para tareas largas, conectado por MCP.
- [ ] **Abrir juegos por voz:** buscar la ROM en la SD y abrirla directo en su emulador.
- [ ] **Controles de juego por voz:** pausar, guardar o cargar partida.
- [ ] **Reloj inteligente:** usar el asistente del celular desde el reloj, o una app de botones rápidos que llame a `casa` publicado por HTTPS.
- [ ] Publicar `casa` con nombre fijo en la red (mDNS) para no depender de la IP de la computadora.
- [ ] Modelo local más capaz si algún día hay más RAM (la consola tiene 2 GB).
