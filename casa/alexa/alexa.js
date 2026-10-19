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
      ok({
        dispositivo: d.nombre,
        estado: p.playerState === 'PLAYING' ? 'sonando' : p.playerState === 'PAUSED' ? 'pausa' : String(p.playerState || '').toLowerCase(),
        titulo: p.infoText.title || '',
        artista: p.infoText.subText1 || '',
        album: p.infoText.subText2 || '',
        portada: art.mediumUrl || art.smallUrl || art.fullUrl || '',
        app: (p.provider && p.provider.providerName) || '',
        progreso: (p.progress && p.progress.mediaProgress) || 0,
        duracion: (p.progress && p.progress.mediaLength) || 0,
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
      if (req.url === '/control') return responder(res, 200, { dispositivo: await controlar(datos.accion, datos.dispositivo, datos.valor) });
      if (req.url === '/decir') return responder(res, 200, { dispositivo: await secuencia(datos.dispositivo, 'speak', datos.texto) });
      responder(res, 404, { error: 'ruta desconocida' });
    } catch (e) {
      responder(res, 500, { error: String(e.message || e) });
    }
  });
}).listen(PUERTO, '127.0.0.1', () => console.log('Puente Alexa en http://127.0.0.1:' + PUERTO));
