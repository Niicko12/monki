# Pendientes

## En curso

- [ ] Probar a fondo "hey monki" mientras Monki habla (modo estricto) con ruido de juego.

## Ideas

- [ ] **Muse Gadget SDK (Meta):** convertir a Monki en un "gadget" de Muse para usarlo como otro cerebro con voz y que Muse controle la consola. Requiere cuenta y token de Muse, emparejar por Bluetooth LE (falta comprobar si la consola puede anunciarse como periférico) y portar a Kotlin el cifrado Noise XX, el emparejamiento v5 y el canal `/link-control` (~2 500 líneas de Python). No exponer `system.run`; solo las herramientas de Monki.
- [ ] **Abrir juegos por voz:** buscar la ROM en la SD y abrirla directo en su emulador.
- [ ] **Controles de juego por voz:** pausar, guardar o cargar partida.
- [ ] Modelo local más capaz si algún día hay más RAM (la consola tiene 2 GB).
