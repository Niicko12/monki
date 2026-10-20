// Puente a Alexa (no oficial, alexa-remote2): Monkey le manda texto al Echo como si le hablaras.
// Solo escucha en la Mac (127.0.0.1:8801); casa.py lo expone a la consola como herramientas MCP.
// Primer uso: abrir http://127.0.0.1:3456 en la Mac e iniciar sesión en Amazon. La sesión queda en alexa-cookie.json.
const fs = require('fs');
const path = require('path');
const http = require('http');
const Alexa = require('alexa-remote2');

const COOKIE = path.join(__dirname, 'alexa-cookie.json');
const PUERTO = 8801;
const PUERTO_LOGIN = 3456;
/** Echo al que van las órdenes si no se dice otro (variable ALEXA_PRINCIPAL; vacío = el primer Echo en línea). */
const PRINCIPAL = process.env.ALEXA_PRINCIPAL || '';

const alexa = new Alexa();
let listo = false;
let error = '';

function guardarCookie(datos) {
  fs.writeFileSync(COOKIE, JSON.stringify(datos), { mode: 0o600 });
}

const cookieGuardada = fs.existsSync(COOKIE) ? JSON.parse(fs.readFileSync(COOKIE, 'utf8')) : undefined;

alexa.on('cookie', () => { if (alexa.cookieData) guardarCookie(alexa.cookieData); });

alexa.init({
  cookie: cookieGuardada,
  formerRegistrationData: cookieGuardada,
  proxyOnly: true,
  proxyOwnIp: '127.0.0.1',
  proxyPort: PUERTO_LOGIN,
  proxyListenBind: '127.0.0.1',
  proxyLogLevel: 'warn',
  amazonPage: 'amazon.com.mx',
  baseAmazonPage: 'amazon.com',
  amazonPageProxyLanguage: 'es_MX',
  acceptLanguage: 'es-MX',
  alexaServiceHost: 'pitangui.amazon.com',
  useWsMqtt: false,
  usePushConnectType: 0,
  cookieRefreshInterval: 4 * 24 * 60 * 60 * 1000,
  // Solo errores, y nunca líneas con cookies o tokens de la sesión
  logger: (m) => { if (/error/i.test(m) && !/cookie:|token|csrf|Atza|session/i.test(m)) console.log(m); },
}, (err) => {
  if (err) {
    error = String(err.message || err);
    console.log('Alexa: falta iniciar sesión en http://127.0.0.1:' + PUERTO_LOGIN, error);
    return;
  }
  listo = true;
  error = '';
  if (alexa.cookieData) guardarCookie(alexa.cookieData);
  console.log('Alexa: lista,', Object.keys(alexa.serialNumbers).length, 'dispositivos');
});

/** Echos y bocinas que entienden comandos (no la app del celular ni grupos). */
function dispositivos() {
  return Object.values(alexa.serialNumbers || {})
    .filter((d) => d.capabilities && d.capabilities.includes('VOLUME_SETTING'))
    .map((d) => ({ nombre: d.accountName, serie: d.serialNumber, tipo: d.deviceFamily, en_linea: d.online }));
}

/** El pedido o, si no dice, el principal. */
function elegir(nombre) {
  const todos = dispositivos();
  if (nombre) {
    const n = nombre.toLowerCase();
    const d = todos.find((x) => x.nombre.toLowerCase() === n) || todos.find((x) => x.nombre.toLowerCase().includes(n));
    if (d) return d;
  }
  // Por defecto el principal; si no está, un Echo de verdad, no un grupo ("En todas partes") ni la app del celular
  return todos.find((x) => x.en_linea && x.nombre === PRINCIPAL) || todos.find((x) => x.en_linea && x.tipo === 'ECHO') || todos.find((x) => x.en_linea) || todos[0];
}

function secuencia(dispositivo, comando, valor) {
  return new Promise((ok, mal) => {
    const d = elegir(dispositivo);
    if (!d) return mal(new Error('No encontré ningún Echo en la cuenta.'));
    alexa.sendSequenceCommand(d.serie, comando, valor, (err) => (err ? mal(err) : ok(d.nombre)));
  });
}

/** Lo que suena en un Echo (título, artista, portada…) o null si no hay nada.
 *  Usa la API nueva de sesiones: la vieja (getPlayerInfo) devuelve vacío con Spotify. */
function infoDe(d) {
  return new Promise((ok) => {
    const tipo = alexa.serialNumbers[d.serie] && alexa.serialNumbers[d.serie].deviceType;
    alexa.httpsGet(`/api/np/list-media-sessions?deviceSerialNumber=${d.serie}&deviceType=${tipo}`, (err, r) => {
      const s = !err && r && (r.mediaSessionList || []).find((x) => x.nowPlayingData && x.nowPlayingData.infoText);
      if (!s) return ok(null);
      const p = s.nowPlayingData;
      const art = p.mainArt || {};
      // De qué lista o álbum viene la canción (para mostrar la lista en la consola)
      let lista = '';
      try { lista = JSON.parse((p.mediaReference && p.mediaReference.value) || '{}').contentId || ''; } catch (e) { lista = ''; }
      ok({
        dispositivo: d.nombre,
        estado: p.playerState === 'PLAYING' ? 'sonando' : p.playerState === 'PAUSED' ? 'pausa' : String(p.playerState || '').toLowerCase(),
        titulo: p.infoText.title || '',
        artista: p.infoText.subText1 || '',
        album: p.infoText.subText2 || '',
        // largeUrl = 300 px; mediumUrl de Spotify es de 64 px y se ve borrosa
        portada: art.largeUrl || art.mediumUrl || art.fullUrl || art.smallUrl || '',
        app: (p.provider && p.provider.providerName) || '',
        progreso: (p.progress && p.progress.mediaProgress) || 0,
        duracion: (p.progress && p.progress.mediaLength) || 0,
        lista: lista || p.queueId || '',
        cancion: p.mediaId || '',
      });
    });
  });
}

/** El Echo pedido o, si no dice, el primero que esté sonando (empezando por el principal). */
async function reproduciendo(nombre) {
  if (nombre) return infoDe(elegir(nombre));
  const todos = dispositivos().filter((x) => x.en_linea);
  todos.sort((a, b) => (b.nombre === PRINCIPAL) - (a.nombre === PRINCIPAL));
  let pausado = null;
  for (const d of todos) {
    const i = await infoDe(d);
    if (i && i.estado === 'sonando') return i;
    if (i && !pausado) pausado = i;
  }
  return pausado;
}

/** play, pausa, siguiente, anterior o volumen (0-100) en el Echo que está sonando (o el pedido).
 *  Se mandan como orden de texto al Echo: /api/np/command no controla las sesiones de Spotify. */
async function controlar(accion, nombre, valor) {
  const texto = { play: 'continúa la música', pausa: 'pausa', siguiente: 'siguiente canción', anterior: 'canción anterior' }[accion];
  if (!texto && accion !== 'volumen') throw new Error('Acción no válida: play, pausa, siguiente, anterior o volumen.');
  const actual = nombre ? null : await reproduciendo();
  const destino = actual ? actual.dispositivo : nombre;
  if (accion === 'volumen') return secuencia(destino, 'volume', Math.max(0, Math.min(100, valor | 0)));
  return secuencia(destino, 'textCommand', texto);
}

/** Salta a una posición (ms) de la canción que suena. Primero SeekCommand de la API de Amazon (mediaPosition va en
 *  SEGUNDOS: en ms se pasa del final y salta a la siguiente canción); si la canción
 *  no se movió (pasa con Spotify), una orden de texto relativa: "adelanta N segundos" / "regresa N segundos". */
async function saltar(posicionMs, nombre) {
  const actual = await reproduciendo(nombre);
  if (!actual || !actual.duracion) throw new Error('No hay música sonando.');
  const d = elegir(actual.dispositivo);
  const tipo = alexa.serialNumbers[d.serie].deviceType;
  const destino = Math.max(0, Math.min(actual.duracion - 1000, posicionMs | 0));
  await new Promise((ok) => alexa.httpsGet(`/api/np/command?deviceSerialNumber=${d.serie}&deviceType=${tipo}`, () => ok(),
    { method: 'POST', data: JSON.stringify({ type: 'SeekCommand', mediaPosition: Math.round(destino / 1000), contentFocusClientId: null }) }));
  await new Promise((ok) => setTimeout(ok, 1500));
  const despues = await reproduciendo(actual.dispositivo);
  const esperado = destino + 1500;
  if (despues && Math.abs(despues.progreso - esperado) < 4000) return { dispositivo: d.nombre, metodo: 'seek', progreso: despues.progreso };
  // Si cambió de canción o la diferencia no tiene sentido, no se manda nada (una orden rara puede saltar de canción)
  if (!despues || despues.titulo !== actual.titulo) return { dispositivo: d.nombre, metodo: 'nada', motivo: 'cambió la canción' };
  const delta = Math.round((destino - despues.progreso) / 1000);
  if (Math.abs(delta) < 2 || Math.abs(delta) > actual.duracion / 1000) {
    return { dispositivo: d.nombre, metodo: 'nada', destino, antes: actual.progreso, despues: despues.progreso };
  }
  await secuencia(actual.dispositivo, 'textCommand', delta > 0 ? `adelanta ${delta} segundos` : `regresa ${-delta} segundos`);
  return { dispositivo: d.nombre, metodo: 'texto', segundos: delta };
}

// Último volumen que pusimos en cada Echo: getMedia tarda en reflejar los cambios
const volumenes = {};

/** Sube o baja el volumen del Echo que está sonando (cambio en puntos, p. ej. +10 / -10). Devuelve el nivel nuevo. */
async function cambiarVolumen(cambio, nombre) {
  const actual = nombre ? null : await reproduciendo();
  const d = elegir(actual ? actual.dispositivo : nombre);
  if (!d) throw new Error('No encontré ningún Echo en la cuenta.');
  const reciente = volumenes[d.serie] && Date.now() - volumenes[d.serie].at < 60000 ? volumenes[d.serie].vol : null;
  const ahora = reciente !== null ? reciente
    : await new Promise((ok) => alexa.getMedia(d.serie, (err, r) => ok(!err && r && typeof r.volume === 'number' ? r.volume : 30)));
  const nuevo = Math.max(0, Math.min(100, ahora + (cambio | 0)));
  volumenes[d.serie] = { vol: nuevo, at: Date.now() };
  await secuencia(d.nombre, 'volume', nuevo);
  return { dispositivo: d.nombre, volumen: nuevo };
}

/** Alarmas activas de los Echo (también las de música), ordenadas por la próxima en sonar. */
function alarmas() {
  return new Promise((ok, mal) => alexa.getNotifications(false, (err, r) => {
    if (err) return mal(err);
    const nombres = Object.fromEntries(Object.values(alexa.serialNumbers || {}).map((d) => [d.serialNumber, d.accountName]));
    const lista = ((r && r.notifications) || [])
      .filter((n) => /alarm/i.test(n.type) && n.status === 'ON')
      .map((n) => ({
        id: n.notificationIndex || n.id,
        dispositivo: nombres[n.deviceSerialNumber] || n.deviceSerialNumber,
        fecha: n.originalDate || '',
        hora: String(n.originalTime || '').slice(0, 5),
        repetir: n.recurringPattern || (n.rRuleData && n.rRuleData.recurrenceRules ? n.rRuleData.recurrenceRules.join(' ') : ''),
        musica: n.musicEntity || (n.musicAlarmId ? 'música' : ''),
        tipo: n.type,
        cuando: n.alarmTime || 0,
      }))
      .sort((a, b) => a.cuando - b.cuando);
    ok(lista);
  }));
}

/** Pone una alarma en el Echo por la API de notificaciones (por orden de texto Alexa no las crea).
 *  fecha "AAAA-MM-DD" y hora "HH:MM" (24 h); con musica, alarma de Spotify como las que se ponen por voz. */
async function ponerAlarma(fecha, hora, musica, nombre) {
  const d = elegir(nombre);
  if (!d) throw new Error('No encontré ningún Echo en la cuenta.');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(fecha || '') || !/^\d{1,2}:\d{2}$/.test(hora || '')) throw new Error('Fecha u hora inválida.');
  const [h, m] = hora.split(':').map(Number);
  const cuando = new Date(`${fecha}T${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00`);
  const n = alexa.createNotificationObject(d.serie, 'Alarm', null, cuando, 'ON');
  let r;
  if (musica) {
    // Formato clásico (PUT /api/notifications): el nuevo (/v1/alerts/alarms) ignora la música
    const fecha2 = n.originalDate, hora2 = n.originalTime;
    const clasica = {
      type: 'MusicAlarm', status: 'ON', alarmTime: cuando.getTime(), createdDate: Date.now(),
      deviceSerialNumber: d.serie, deviceType: alexa.serialNumbers[d.serie].deviceType,
      originalDate: fecha2 || fecha, originalTime: hora2 || `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00.000`,
      musicEntity: musica, provider: 'Spotify', musicAlarmId: null, id: null, isRecurring: false, recurringPattern: null,
      reminderLabel: null, sound: null, isSaveInFlight: true, timeZoneId: null, reminderIndex: null,
    };
    r = await new Promise((ok, mal) => alexa.httpsGet('/api/notifications/null', (err, res) => (err ? mal(err) : ok(res)),
      { method: 'PUT', data: JSON.stringify(clasica) }));
  } else {
    r = await new Promise((ok, mal) => alexa.createNotification(n, (err, res) => (err ? mal(err) : ok(res))));
  }
  return { dispositivo: d.nombre, fecha, hora, musica: musica || '', id: r && (r.notificationIndex || r.id) };
}

/** Borra una alarma por su id (de /alarmas). */
async function borrarAlarma(id) {
  const n = await new Promise((ok, mal) => alexa.getNotifications(false, (err, r) => (err ? mal(err)
    : ok(((r && r.notifications) || []).find((x) => (x.notificationIndex || x.id) === id)))));
  if (!n) throw new Error('No encontré esa alarma.');
  await new Promise((ok, mal) => alexa.deleteNotification(n, (err) => (err ? mal(err) : ok())));
  return { borrada: id };
}

function responder(res, codigo, cuerpo) {
  res.writeHead(codigo, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(cuerpo));
}

http.createServer((req, res) => {
  let cuerpo = '';
  req.on('data', (c) => { cuerpo += c; });
  req.on('end', async () => {
    const datos = cuerpo ? JSON.parse(cuerpo) : {};
    if (req.url === '/estado') return responder(res, 200, { listo, error, login: `http://127.0.0.1:${PUERTO_LOGIN}` });
    if (!listo) return responder(res, 503, { error: `Alexa no tiene sesión. Abre http://127.0.0.1:${PUERTO_LOGIN} en la Mac e inicia sesión en Amazon.` });
    try {
      if (req.url === '/dispositivos') return responder(res, 200, dispositivos());
      if (req.url === '/comando') return responder(res, 200, { dispositivo: await secuencia(datos.dispositivo, 'textCommand', datos.texto) });
      if (req.url === '/reproduciendo') return responder(res, 200, (await reproduciendo(datos.dispositivo)) || { estado: 'nada' });
      if (req.url === '/alarmas') return responder(res, 200, await alarmas());
      if (req.url === '/alarma') return responder(res, 200, await ponerAlarma(datos.fecha, datos.hora, datos.musica, datos.dispositivo));
      if (req.url === '/alarma-borrar') return responder(res, 200, await borrarAlarma(datos.id));
      if (req.url === '/volumen') return responder(res, 200, await cambiarVolumen(datos.cambio, datos.dispositivo));
      if (req.url === '/saltar') return responder(res, 200, await saltar(datos.posicion, datos.dispositivo));
      if (req.url === '/control') return responder(res, 200, { dispositivo: await controlar(datos.accion, datos.dispositivo, datos.valor) });
      if (req.url === '/decir') return responder(res, 200, { dispositivo: await secuencia(datos.dispositivo, 'speak', datos.texto) });
      responder(res, 404, { error: 'ruta desconocida' });
    } catch (e) {
      responder(res, 500, { error: String(e.message || e) });
    }
  });
}).listen(PUERTO, '127.0.0.1', () => console.log('Puente Alexa en http://127.0.0.1:' + PUERTO));
