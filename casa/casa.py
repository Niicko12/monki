#!/usr/bin/env python3
"""Centro de control de la casa: servidor MCP en la Mac (como el servidor de xiaozhi).

El Asistente de la Anbernic (u otro agente) se conecta aquí para ver la red y controlar
la Mac, teles/Chromecast y celulares Android.

Uso:  uv run casa.py            (escucha en 0.0.0.0:8800/mcp, pide la clave de .env)
"""
import functools
import inspect
import io
import os
import secrets
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import anyio
import pychromecast
import uvicorn
from mcp.server.fastmcp import FastMCP, Image
from mcp.server.transport_security import TransportSecuritySettings
from PIL import Image as PILImage
from starlette.responses import JSONResponse
from zeroconf import ServiceBrowser, Zeroconf

DIR = Path(__file__).resolve().parent
PUERTO = 8800


def clave() -> str:
    env = DIR / ".env"
    if env.exists():
        for linea in env.read_text().splitlines():
            if linea.startswith("CASA_TOKEN="):
                return linea.split("=", 1)[1].strip()
    token = secrets.token_hex(10)
    env.write_text(f"CASA_TOKEN={token}\n")
    env.chmod(0o600)
    return token


TOKEN = clave()

mcp = FastMCP(
    "casa",
    instructions="Controla la Mac, los dispositivos de la red, teles/Chromecast y celulares Android de la casa.",
    host="0.0.0.0", port=PUERTO, stateless_http=True, json_response=True,
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
)


# Las herramientas son funciones normales (bloqueantes). FastMCP las corre en el mismo hilo que atiende las
# peticiones: una que se atore (red, adb, Chromecast…) dejaba a casa sin responder a nada. Cada herramienta
# corre en un hilo aparte.
_registrar_herramienta = mcp.tool


def _herramienta_en_hilo(*args, **kwargs):
    registrar = _registrar_herramienta(*args, **kwargs)

    def envolver(fn):
        if inspect.iscoroutinefunction(fn):
            return registrar(fn)

        @functools.wraps(fn)
        async def en_hilo(*a, **k):
            return await anyio.to_thread.run_sync(functools.partial(fn, *a, **k))

        registrar(en_hilo)
        return fn

    return envolver


mcp.tool = _herramienta_en_hilo


def sh(*cmd, timeout=20) -> str:
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return (r.stdout or r.stderr).strip()


def osa(script: str) -> str:
    return sh("osascript", "-e", script)


ES_WIN = sys.platform.startswith("win")


def jpeg(path_or_bytes, ancho=1024) -> Image:
    img = PILImage.open(io.BytesIO(path_or_bytes) if isinstance(path_or_bytes, bytes) else path_or_bytes).convert("RGB")
    if img.width > ancho:
        img = img.resize((ancho, int(img.height * ancho / img.width)))
    out = io.BytesIO()
    img.save(out, "JPEG", quality=60)
    return Image(data=out.getvalue(), format="jpeg")


# ---------------- red ----------------

SERVICIOS = {
    "_googlecast._tcp.local.": "Chromecast / Google TV",
    "_androidtvremote2._tcp.local.": "Android TV",
    "_airplay._tcp.local.": "AirPlay (Apple TV / Mac / bocina)",
    "_raop._tcp.local.": "Bocina AirPlay",
    "_spotify-connect._tcp.local.": "Spotify Connect",
    "_hap._tcp.local.": "Accesorio HomeKit",
    "_adb-tls-connect._tcp.local.": "Android con depuración inalámbrica",
    "_ipp._tcp.local.": "Impresora",
    "_hue._tcp.local.": "Philips Hue",
    "_home-assistant._tcp.local.": "Home Assistant",
    "_matter._tcp.local.": "Dispositivo Matter",
}


@mcp.tool(name="red_escanear")
def red_escanear(segundos: float = 4) -> str:
    """Busca los dispositivos conectados a la red de la casa (teles, Chromecast, bocinas, celulares, impresoras, focos)."""
    vistos = []

    class Oyente:
        # No resolver aquí: el aviso corre en el hilo de zeroconf y no se puede bloquear
        def add_service(self, zc, tipo, nombre): vistos.append((tipo, nombre))
        def update_service(self, *a): pass
        def remove_service(self, *a): pass

    zc = Zeroconf()
    ServiceBrowser(zc, list(SERVICIOS), Oyente())
    time.sleep(min(max(segundos, 2), 10))
    encontrados = {}
    for tipo, nombre in vistos:
        info = zc.get_service_info(tipo, nombre, timeout=1500)
        ip = info.parsed_addresses()[0] if info and info.parsed_addresses() else "?"
        props = {k.decode(): (v or b"").decode(errors="ignore") for k, v in (info.properties if info else {}).items()}
        amigable = props.get("fn") or props.get("n") or nombre.split(".")[0]
        encontrados[(ip, SERVICIOS[tipo])] = amigable
    zc.close()

    lineas = [f"- {nombre} ({tipo}) en {ip}" for (ip, tipo), nombre in sorted(encontrados.items())]
    import re
    ips = {ip for ip, _ in encontrados}
    tabla = sh("arp", "-an") if not ES_WIN else sh("arp", "-a")
    vecinos = re.findall(r"\((\d+\.\d+\.\d+\.\d+)\) at ([0-9a-f:]+)", tabla) or \
        re.findall(r"(\d+\.\d+\.\d+\.\d+)\s+([0-9a-f-]{17})\s+din", tabla)
    otros = [ip for ip, mac in vecinos if ip not in ips and not ip.startswith(("224.", "239.")) and mac != "ff:ff:ff:ff:ff:ff"]
    return ("Dispositivos que se anuncian:\n" + ("\n".join(lineas) or "ninguno") +
            f"\n\nOtras direcciones activas en la red ({len(otros)}): " + ", ".join(otros[:40]))


# ---------------- computadora (Mac o Windows) ----------------

ES_MAC = sys.platform == "darwin"
ES_WIN = sys.platform.startswith("win")


def ps(script: str) -> str:
    """Ejecuta PowerShell (Windows)."""
    return sh("powershell", "-NoProfile", "-Command", script)


def tecla_multimedia(vk: int):
    """Simula una tecla multimedia en Windows (play/pausa, siguiente, anterior)."""
    import ctypes
    ctypes.windll.user32.keybd_event(vk, 0, 0, 0)
    ctypes.windll.user32.keybd_event(vk, 0, 2, 0)


@mcp.tool(name="pc_estado")
def pc_estado() -> str:
    """Batería, volumen, app al frente y hora de esta computadora."""
    import psutil
    b = psutil.sensors_battery()
    bateria = f"{int(b.percent)}%{' (cargando)' if b.power_plugged else ''}" if b else "sin batería"
    if ES_MAC:
        volumen = osa("output volume of (get volume settings)")
        frente = osa('tell application "System Events" to get name of first application process whose frontmost is true')
    else:
        volumen = str(_vol_win().GetMasterVolumeLevelScalar() * 100 // 1) if ES_WIN else "?"
        frente = ps("(Get-Process | Where-Object {$_.MainWindowHandle -ne 0} | Sort-Object CPU -Descending | Select-Object -First 1).MainWindowTitle")
    sistema = "Mac" if ES_MAC else "Windows" if ES_WIN else sys.platform
    return f"{sistema}. Batería: {bateria}. Volumen: {volumen}%. Ventana al frente: {frente}. Hora: {time.strftime('%H:%M')}."


@mcp.tool(name="pc_captura")
def pc_captura() -> Image:
    """Mira la pantalla de esta computadora (imagen)."""
    if ES_MAC:
        f = Path(tempfile.mkstemp(suffix=".jpg")[1])
        sh("screencapture", "-x", "-t", "jpg", str(f))
        try:
            return jpeg(f)
        finally:
            f.unlink(missing_ok=True)
    from PIL import ImageGrab
    out = io.BytesIO()
    ImageGrab.grab(all_screens=True).save(out, "PNG")
    return jpeg(out.getvalue())


@mcp.tool(name="pc_abrir_app")
def pc_abrir_app(nombre: str) -> str:
    """Abre una app por su nombre (Safari, Spotify, Chrome, Bloc de notas, Steam…)."""
    if ES_MAC:
        return sh("open", "-a", nombre) or f"Abrí {nombre}."
    alias = {"bloc de notas": "notepad", "calculadora": "calc", "explorador": "explorer", "chrome": "chrome", "edge": "msedge"}
    ps(f"Start-Process '{alias.get(nombre.lower(), nombre)}'")
    return f"Abrí {nombre}."


@mcp.tool(name="pc_abrir_url")
def pc_abrir_url(url: str) -> str:
    """Abre una página web en esta computadora."""
    import webbrowser
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    webbrowser.open(url)
    return f"Abrí {url}."


@mcp.tool(name="pc_apps_abiertas")
def pc_apps_abiertas() -> str:
    """Lista las apps con ventana abiertas en esta computadora."""
    if ES_MAC:
        return osa('tell application "System Events" to get name of every application process whose background only is false')
    return ps("Get-Process | Where-Object {$_.MainWindowTitle} | ForEach-Object { $_.ProcessName + ': ' + $_.MainWindowTitle }")


def _vol_win():
    from comtypes import CLSCTX_ALL
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
    dev = AudioUtilities.GetSpeakers()
    return dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None).QueryInterface(IAudioEndpointVolume)


@mcp.tool(name="pc_volumen")
def pc_volumen(nivel: int) -> str:
    """Cambia el volumen de esta computadora (0 a 100)."""
    nivel = max(0, min(100, nivel))
    if ES_MAC:
        osa(f"set volume output volume {nivel}")
    else:
        _vol_win().SetMasterVolumeLevelScalar(nivel / 100, None)
    return f"Volumen al {nivel}%."


@mcp.tool(name="pc_decir")
def pc_decir(texto: str) -> str:
    """Dice un texto en voz alta por las bocinas de esta computadora."""
    if ES_MAC:
        subprocess.Popen(["say", "-v", "Paulina", texto], stderr=subprocess.DEVNULL)
    else:
        t = texto.replace("'", "")
        subprocess.Popen(["powershell", "-NoProfile", "-Command",
                          f"Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('{t}')"])
    return "Lo dije en la computadora."


@mcp.tool(name="pc_notificar")
def pc_notificar(titulo: str, texto: str) -> str:
    """Muestra un aviso en la pantalla de esta computadora."""
    t = titulo.replace('"', "'").replace("'", ""); x = texto.replace('"', "'").replace("'", "")
    if ES_MAC:
        osa(f'display notification "{x}" with title "{t}"')
    else:
        ps(f"Add-Type -AssemblyName System.Windows.Forms; $n=New-Object System.Windows.Forms.NotifyIcon; "
           f"$n.Icon=[System.Drawing.SystemIcons]::Information; $n.Visible=$true; $n.ShowBalloonTip(5000,'{t}','{x}','Info'); Start-Sleep 6")
    return "Aviso enviado."


@mcp.tool(name="pc_musica")
def pc_musica(accion: str) -> str:
    """Controla la música de esta computadora: play, pausa, siguiente, anterior."""
    a = accion.lower()
    if ES_MAC:
        cmd = {"play": "play", "pausa": "pause", "siguiente": "next track", "anterior": "previous track"}.get(a)
        if not cmd:
            return "Acción no válida: usa play, pausa, siguiente o anterior."
        for app in ("Spotify", "Music"):
            if osa(f'application "{app}" is running') == "true":
                osa(f'tell application "{app}" to {cmd}')
                return f"{accion} en {app}."
        return "No hay Spotify ni Música abiertos."
    vk = {"play": 0xB3, "pausa": 0xB3, "siguiente": 0xB0, "anterior": 0xB1}.get(a)
    if not vk:
        return "Acción no válida: usa play, pausa, siguiente o anterior."
    tecla_multimedia(vk)
    return f"{accion} enviado."


def _modelo() -> str:
    """Nombre del equipo para mostrar ("MacBook Air", "PC Windows"); se calcula una vez."""
    global _MODELO
    if "_MODELO" not in globals():
        nombre = ""
        if ES_MAC:
            for linea in sh("system_profiler", "SPHardwareDataType", timeout=15).splitlines():
                if "Model Name" in linea:
                    nombre = linea.split(":", 1)[1].strip()
        elif ES_WIN:
            nombre = ps("(Get-CimInstance Win32_ComputerSystemProduct).Name") or "PC Windows"
        _MODELO = nombre or ("Mac" if ES_MAC else "PC")
    return _MODELO


@mcp.tool(name="pc_info")
def pc_info(corto: bool = False, formato: str = "texto") -> str:
    """CPU, memoria, disco, batería, IP y tiempo encendida de esta computadora. corto=true: una línea para widgets;
    formato="json": datos para el widget de la consola."""
    import json
    import psutil
    cpu = psutil.cpu_percent(interval=0.5)
    ram = psutil.virtual_memory().percent
    b = psutil.sensors_battery()
    bat = f"{int(b.percent)}%{'⚡' if b.power_plugged else ''}" if b else "enchufada"
    disco = psutil.disk_usage("C:\\" if ES_WIN else "/")
    if formato == "json":
        return json.dumps({
            "modelo": _modelo(), "sistema": "macOS" if ES_MAC else "Windows" if ES_WIN else sys.platform,
            "bateria": int(b.percent) if b else None, "cargando": bool(b and b.power_plugged),
            "cpu": round(cpu), "ram": round(ram), "disco_libre_gb": round(disco.free / 1e9),
        })
    if corto:
        return f"🔋{bat} · CPU {cpu:.0f}% · RAM {ram:.0f}%"
    horas = (time.time() - psutil.boot_time()) / 3600
    import socket
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as so:
            so.connect(("8.8.8.8", 80)); ip = so.getsockname()[0]
    except OSError:
        ip = "?"
    return (f"CPU {cpu:.0f}%, RAM {ram:.0f}%, disco libre {disco.free / 1e9:.0f} GB de {disco.total / 1e9:.0f} GB, "
            f"batería {bat}, IP {ip}, encendida hace {horas:.1f} h.")


@mcp.tool(name="pc_energia")
def pc_energia(accion: str, confirmar: bool = False) -> str:
    """bloquear, dormir, apagar_pantalla, reiniciar o apagar esta computadora. reiniciar/apagar piden confirmar=true (pregunta antes al usuario)."""
    a = accion.lower().strip()
    if a in ("reiniciar", "apagar") and not confirmar:
        return "Pregúntale al usuario si está seguro y vuelve a llamar con confirmar=true."
    if ES_MAC:
        cmd = {
            "bloquear": lambda: sh("pmset", "displaysleepnow"),
            "apagar_pantalla": lambda: sh("pmset", "displaysleepnow"),
            "dormir": lambda: osa('tell application "System Events" to sleep'),
            "reiniciar": lambda: osa('tell application "System Events" to restart'),
            "apagar": lambda: osa('tell application "System Events" to shut down'),
        }.get(a)
    else:
        cmd = {
            "bloquear": lambda: sh("rundll32.exe", "user32.dll,LockWorkStation"),
            "apagar_pantalla": lambda: ps("(Add-Type '[DllImport(\"user32.dll\")]public static extern int SendMessage(int h,int m,int w,int l);' -Name a -Pas)::SendMessage(-1,0x0112,0xF170,2)"),
            "dormir": lambda: sh("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"),
            "reiniciar": lambda: sh("shutdown", "/r", "/t", "5"),
            "apagar": lambda: sh("shutdown", "/s", "/t", "5"),
        }.get(a)
    if not cmd:
        return "Acción no válida: bloquear, dormir, apagar_pantalla, reiniciar o apagar."
    cmd()
    return f"Listo: {a}."


@mcp.tool(name="pc_silencio")
def pc_silencio(activar: bool = True) -> str:
    """Silencia (activar=true) o quita el silencio de esta computadora."""
    if ES_MAC:
        osa(f"set volume output muted {'true' if activar else 'false'}")
    else:
        _vol_win().SetMute(1 if activar else 0, None)
    return "Silenciada." if activar else "Con sonido."


@mcp.tool(name="pc_brillo")
def pc_brillo(nivel: int) -> str:
    """Brillo de la pantalla de esta computadora (0 a 100). En PC de escritorio con monitor externo puede no funcionar."""
    nivel = max(0, min(100, nivel))
    if ES_MAC:
        # macOS no trae comando de brillo: baja todo con la tecla de brillo y sube los pasos necesarios (16 pasos)
        osa("tell application \"System Events\"\nrepeat 16 times\nkey code 145\nend repeat\n"
            f"repeat {round(nivel / 6.25)} times\nkey code 144\nend repeat\nend tell")
    else:
        ps(f"(Get-WmiObject -Namespace root/WMI -Class WmiMonitorBrightnessMethods).WmiSetBrightness(1,{nivel})")
    return f"Brillo al {nivel}%."


def _gui():
    import pyautogui
    pyautogui.FAILSAFE = False
    return pyautogui


@mcp.tool(name="pc_teclado")
def pc_teclado(texto: str = "", atajo: str = "") -> str:
    """Escribe texto y/o presiona un atajo en esta computadora. atajo: teclas con + (ej. "enter", "ctrl+c", "command+space", "alt+tab", "esc")."""
    g = _gui()
    if texto:
        if ES_MAC or not texto.isascii():
            # Acentos y ñ: pegar desde el portapapeles es más fiable que teclearlos
            _copiar(texto); g.hotkey("command" if ES_MAC else "ctrl", "v")
        else:
            g.write(texto, interval=0.01)
    if atajo:
        teclas = [t.strip().lower().replace("cmd", "command").replace("control", "ctrl").replace("intro", "enter")
                  for t in atajo.split("+") if t.strip()]
        g.hotkey(*teclas)
    return "Listo." if texto or atajo else "No me diste texto ni atajo."


@mcp.tool(name="pc_raton")
def pc_raton(accion: str, x: int = -1, y: int = -1, cantidad: int = 5) -> str:
    """Mouse de esta computadora: clic, doble_clic, clic_derecho, mover (en x,y de pc_captura) o scroll (cantidad + arriba, - abajo)."""
    g = _gui()
    w, h = g.size()
    if x >= 0 and y >= 0:
        # Las coordenadas vienen de pc_captura (reducida a 1024 de ancho)
        ancho_cap = 1024
        alto_cap = round(h * ancho_cap / w)
        x, y = round(x * w / ancho_cap), round(y * h / alto_cap)
    pos = (x, y) if x >= 0 and y >= 0 else (None, None)
    a = accion.lower()
    if a == "clic": g.click(*pos)
    elif a == "doble_clic": g.doubleClick(*pos)
    elif a == "clic_derecho": g.rightClick(*pos)
    elif a == "mover": g.moveTo(*pos, duration=0.2)
    elif a == "scroll": g.scroll(cantidad * (1 if ES_WIN else 1), *pos)
    else:
        return "Acción no válida: clic, doble_clic, clic_derecho, mover o scroll."
    return "Listo."


def _copiar(texto: str):
    if ES_MAC:
        subprocess.run(["pbcopy"], input=texto, text=True)
    else:
        subprocess.run(["powershell", "-NoProfile", "-Command", "Set-Clipboard -Value $input"], input=texto, text=True)


@mcp.tool(name="pc_portapapeles")
def pc_portapapeles(texto: str = "") -> str:
    """Sin texto: lee el portapapeles de esta computadora. Con texto: lo copia ahí."""
    if texto:
        _copiar(texto)
        return "Copiado."
    actual = sh("pbpaste") if ES_MAC else ps("Get-Clipboard -Raw")
    return actual[:2000] or "El portapapeles está vacío."


@mcp.tool(name="pc_ventana")
def pc_ventana(accion: str, app: str) -> str:
    """cerrar, enfocar o minimizar una app de esta computadora por su nombre (Safari, Chrome, Spotify, notepad…)."""
    a = accion.lower()
    if ES_MAC:
        if a == "cerrar": osa(f'tell application "{app}" to quit')
        elif a == "enfocar": osa(f'tell application "{app}" to activate')
        elif a == "minimizar": osa(f'tell application "System Events" to set visible of process "{app}" to false')
        else: return "Acción no válida: cerrar, enfocar o minimizar."
        return f"Listo: {a} {app}."
    n = app.replace("'", "")
    if a == "cerrar":
        ps(f"Get-Process | Where-Object {{ $_.ProcessName -like '*{n}*' -or $_.MainWindowTitle -like '*{n}*' }} | "
           "Where-Object { $_.MainWindowHandle -ne 0 } | ForEach-Object { $_.CloseMainWindow() | Out-Null }")
    elif a in ("enfocar", "minimizar"):
        modo = 9 if a == "enfocar" else 6
        ps("Add-Type '[DllImport(\"user32.dll\")]public static extern bool ShowWindow(System.IntPtr h,int c);"
           "[DllImport(\"user32.dll\")]public static extern bool SetForegroundWindow(System.IntPtr h);' -Name w -Namespace u; "
           f"Get-Process | Where-Object {{ ($_.ProcessName -like '*{n}*' -or $_.MainWindowTitle -like '*{n}*') -and $_.MainWindowHandle -ne 0 }} | "
           f"Select-Object -First 1 | ForEach-Object {{ [u.w]::ShowWindow($_.MainWindowHandle,{modo}); "
           + ("[u.w]::SetForegroundWindow($_.MainWindowHandle) }" if a == "enfocar" else "}"))
    else:
        return "Acción no válida: cerrar, enfocar o minimizar."
    return f"Listo: {a} {app}."


@mcp.tool(name="pc_archivos")
def pc_archivos(accion: str, nombre: str) -> str:
    """buscar: archivos de la carpeta del usuario cuyo nombre contenga [nombre] (hasta 10). abrir: abre la ruta [nombre] con su app."""
    if accion.lower() == "abrir":
        ruta = Path(nombre).expanduser()
        if not ruta.exists():
            return "No existe esa ruta."
        if ES_MAC: sh("open", str(ruta))
        else: os.startfile(str(ruta))
        return f"Abrí {ruta.name}."
    if ES_MAC:
        res = sh("mdfind", "-onlyin", str(Path.home()), "-name", nombre).splitlines()
    else:
        res = ps(f"Get-ChildItem -Path $HOME -Recurse -Depth 4 -ErrorAction SilentlyContinue -Filter '*{nombre.replace(chr(39), '')}*' | "
                 "Select-Object -First 10 -ExpandProperty FullName").splitlines()
    res = [r for r in res if "/Library/" not in r and "\\AppData\\" not in r][:10]
    return "\n".join(res) or "No encontré archivos con ese nombre."


# ---------------- teles / Chromecast ----------------

_casts: dict[str, pychromecast.Chromecast] = {}
_browser = None


def casts(refrescar=False):
    global _browser
    if refrescar or not _casts:
        lista, _browser = pychromecast.get_chromecasts(timeout=6)
        _casts.clear()
        for c in lista:
            _casts[c.cast_info.friendly_name] = c
    return _casts


def cast(nombre: str) -> pychromecast.Chromecast:
    cs = casts()
    c = cs.get(nombre) or next((v for k, v in cs.items() if nombre.lower() in k.lower()), None)
    if c is None:
        raise ValueError(f"No encontré la tele '{nombre}'. Disponibles: {', '.join(cs) or 'ninguna'}")
    c.wait(timeout=10)
    return c


@mcp.tool(name="tv_listar")
def tv_listar() -> str:
    """Lista las teles, Chromecast y bocinas Google de la casa, con lo que están reproduciendo."""
    cs = casts(refrescar=True)
    if not cs:
        return "No encontré teles ni Chromecast en la red de la Mac."
    out = []
    for nombre, c in cs.items():
        c.wait(timeout=5)
        app = c.status.display_name if c.status else "?"
        vol = int((c.status.volume_level or 0) * 100) if c.status else "?"
        out.append(f"- {nombre} ({c.cast_info.model_name}) en {c.cast_info.host}: {app or 'inactiva'}, volumen {vol}%")
    return "\n".join(out)


@mcp.tool(name="tv_youtube")
def tv_youtube(tele: str, video: str) -> str:
    """Pone un video de YouTube en una tele/Chromecast. 'video' es el enlace o el ID del video."""
    from pychromecast.controllers.youtube import YouTubeController
    vid = video.split("v=")[-1].split("&")[0].split("/")[-1]
    c = cast(tele)
    yt = YouTubeController()
    c.register_handler(yt)
    yt.play_video(vid)
    return f"Puse el video {vid} en {c.cast_info.friendly_name}."


@mcp.tool(name="tv_reproducir_url")
def tv_reproducir_url(tele: str, url: str, tipo: str = "video/mp4") -> str:
    """Reproduce un archivo de video o audio por URL (mp4, mp3) en una tele/Chromecast."""
    c = cast(tele)
    c.media_controller.play_media(url, tipo)
    return f"Reproduciendo en {c.cast_info.friendly_name}."


@mcp.tool(name="tv_control")
def tv_control(tele: str, accion: str, nivel: int = 50) -> str:
    """Controla una tele/Chromecast: pausa, play, detener, volumen (con nivel 0-100), silenciar."""
    c = cast(tele)
    mc = c.media_controller
    a = accion.lower()
    if a == "pausa": mc.pause()
    elif a == "play": mc.play()
    elif a == "detener": c.quit_app()
    elif a == "volumen": c.set_volume(max(0, min(100, nivel)) / 100)
    elif a == "silenciar": c.set_volume_muted(True)
    else: return "Acción no válida: pausa, play, detener, volumen o silenciar."
    return f"{accion} en {c.cast_info.friendly_name}."


# ---------------- celulares Android ----------------

def adb(serial: str | None, *args, binario=False, timeout=20):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return r.stdout if binario else (r.stdout or r.stderr).decode(errors="replace").strip()


@mcp.tool(name="android_dispositivos")
def android_dispositivos() -> str:
    """Lista los celulares/tablets Android conectados a la Mac (por cable o Wi-Fi)."""
    return adb(None, "devices", "-l")


@mcp.tool(name="android_conectar")
def android_conectar(direccion: str) -> str:
    """Conecta un Android por Wi-Fi (depuración inalámbrica). 'direccion' es ip:puerto."""
    return adb(None, "connect", direccion)


@mcp.tool(name="android_captura")
def android_captura(serial: str) -> Image:
    """Mira la pantalla de un Android (imagen)."""
    return jpeg(adb(serial, "exec-out", "screencap", "-p", binario=True), ancho=720)


@mcp.tool(name="android_tocar")
def android_tocar(serial: str, x: int, y: int) -> str:
    """Toca la pantalla de un Android en (x, y) en píxeles reales de la pantalla."""
    adb(serial, "shell", "input", "tap", str(x), str(y))
    return "Toqué."


@mcp.tool(name="android_boton")
def android_boton(serial: str, boton: str) -> str:
    """Presiona un botón en un Android: INICIO, ATRAS, RECIENTES, VOLUMEN_MAS, VOLUMEN_MENOS, ENCENDER."""
    codigos = {"INICIO": 3, "ATRAS": 4, "RECIENTES": 187, "VOLUMEN_MAS": 24, "VOLUMEN_MENOS": 25, "ENCENDER": 26}
    c = codigos.get(boton.upper())
    if c is None:
        return "Botón no válido."
    adb(serial, "shell", "input", "keyevent", str(c))
    return f"Presioné {boton}."


@mcp.tool(name="android_apps")
def android_apps(serial: str) -> str:
    """Lista las apps instaladas por el usuario en un Android."""
    return adb(serial, "shell", "pm", "list", "packages", "-3").replace("package:", "")


@mcp.tool(name="android_abrir_app")
def android_abrir_app(serial: str, paquete: str) -> str:
    """Abre una app en un Android por su paquete."""
    out = adb(serial, "shell", "monkey", "-p", paquete, "-c", "android.intent.category.LAUNCHER", "1")
    return "Abierta." if "Events injected" in out else f"No pude abrir {paquete}."


@mcp.tool(name="android_escribir")
def android_escribir(serial: str, texto: str) -> str:
    """Escribe texto en el campo con foco de un Android."""
    adb(serial, "shell", "input", "text", texto.replace(" ", "%s"))
    return "Escribí el texto."


# ---------------- focos y enchufes ----------------

@mcp.tool(name="focos_estado")
def focos_estado() -> str:
    """Estado de los focos y enchufes inteligentes."""
    return ("Todavía no hay focos configurados. Para conectarlos necesito la marca o app que usan "
            "(Smart Life/Tuya, Google Home, Alexa, Philips Hue, Home Assistant).")


# ---------------- arranque con clave ----------------

def _alexa(ruta: str, **datos) -> str:
    """Llama al puente de Alexa (alexa/alexa.js) que corre en esta Mac."""
    import json, urllib.request, urllib.error
    req = urllib.request.Request(f"http://127.0.0.1:8801{ruta}", data=json.dumps(datos).encode() if datos else None,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode()).get("error", str(e))
    except OSError:
        return "El puente de Alexa no está corriendo en la Mac."


@mcp.tool(name="alexa_comando")
def alexa_comando(texto: str, dispositivo: str = "") -> str:
    """Le pide algo a Alexa como si le hablaras al Echo: música ("pon Bad Bunny en Spotify", "pausa", "sube el volumen"),
    luces y enchufes, rutinas, alarmas, etc. El texto va sin la palabra "Alexa". Dispositivo vacío = el Echo principal; alexa_dispositivos da los nombres."""
    r = _alexa("/comando", texto=texto, dispositivo=dispositivo)
    return f"Alexa ({r.split('\"dispositivo\":\"')[1].split('\"')[0]}) recibió: {texto}" if '"dispositivo"' in r else r


@mcp.tool(name="alexa_decir")
def alexa_decir(texto: str, dispositivo: str = "") -> str:
    """Hace que un Echo diga un texto en voz alta. Dispositivo vacío = el Echo principal."""
    r = _alexa("/decir", texto=texto, dispositivo=dispositivo)
    return "Lo dijo el Echo." if '"dispositivo"' in r else r


@mcp.tool(name="alexa_reproduciendo")
def alexa_reproduciendo(dispositivo: str = "") -> str:
    """Qué está sonando en los Echos (canción, artista, Spotify…), en JSON. Vacío = el que esté sonando."""
    return _alexa("/reproduciendo", dispositivo=dispositivo)


@mcp.tool(name="alexa_control")
def alexa_control(accion: str, nivel: int = 50, dispositivo: str = "") -> str:
    """Controla la música del Echo que está sonando: play, pausa, siguiente, anterior o volumen (con nivel 0-100)."""
    r = _alexa("/control", accion=accion, valor=nivel, dispositivo=dispositivo)
    return f"Listo: {accion}." if '"dispositivo"' in r else r


@mcp.tool(name="alexa_alarma")
def alexa_alarma(fecha: str, hora: str, musica: str = "", dispositivo: str = "") -> str:
    """Pone una alarma en el Echo. fecha AAAA-MM-DD, hora HH:MM (24 h). musica: lo que debe sonar en Spotify
    (artista, canción o lista, p. ej. "Nirvana"); vacío = tono normal. Dispositivo vacío = el Echo principal."""
    import json
    r = _alexa("/alarma", fecha=fecha, hora=hora, musica=musica, dispositivo=dispositivo)
    try:
        d = json.loads(r)
        return f"Alarma puesta en {d['dispositivo']} el {d['fecha']} a las {d['hora']}" + (f" con {d['musica']} en Spotify." if d.get("musica") else ".")
    except (ValueError, KeyError):
        return r


@mcp.tool(name="alexa_alarmas")
def alexa_alarmas() -> str:
    """Alarmas activas de los Echo (JSON: id, dispositivo, fecha, hora, música), la más próxima primero."""
    return _alexa("/alarmas")


@mcp.tool(name="alexa_borrar_alarma")
def alexa_borrar_alarma(id: str) -> str:
    """Borra una alarma del Echo por su id (de alexa_alarmas)."""
    r = _alexa("/alarma-borrar", id=id)
    return "Alarma borrada." if '"borrada"' in r else r


@mcp.tool(name="alexa_volumen")
def alexa_volumen(cambio: int, dispositivo: str = "") -> str:
    """Sube (cambio positivo) o baja (negativo) el volumen del Echo que está sonando. Devuelve el nivel nuevo."""
    import json
    r = _alexa("/volumen", cambio=cambio, dispositivo=dispositivo)
    try:
        return f"Volumen {json.loads(r)['volumen']}."
    except (ValueError, KeyError):
        return r


@mcp.tool(name="alexa_saltar")
def alexa_saltar(segundo: int, dispositivo: str = "") -> str:
    """Mueve la canción que suena a ese segundo (adelantar o regresar)."""
    r = _alexa("/saltar", posicion=max(0, segundo) * 1000, dispositivo=dispositivo)
    return f"Listo, en el segundo {segundo}." if '"metodo"' in r else r


@mcp.tool(name="spotify_lista")
def spotify_lista(lista: str) -> str:
    """Canciones de una lista, álbum o artista de Spotify ("spotify:playlist:ID", "spotify:album:ID"…), en JSON.
    Lee la página pública de Spotify para insertar; no necesita cuenta."""
    import json, re, urllib.request
    m = re.fullmatch(r"spotify:(playlist|album|artist|show):([A-Za-z0-9]{10,40})", lista.strip())
    if not m:
        return json.dumps({"error": "lista no válida"})
    req = urllib.request.Request(f"https://open.spotify.com/embed/{m.group(1)}/{m.group(2)}", headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=10) as r:
        html = r.read(3_000_000).decode("utf-8", "ignore")
    datos = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html, re.S)
    if not datos:
        return json.dumps({"error": "Spotify no devolvió la lista"})
    entidad = json.loads(datos.group(1)).get("props", {}).get("pageProps", {}).get("state", {}).get("data", {}).get("entity", {})
    canciones = [{"titulo": t.get("title", ""), "artista": (t.get("subtitle") or "").replace("\u00a0", " "),
                  "duracion": t.get("duration", 0), "uri": t.get("uri", "")} for t in entidad.get("trackList", [])[:60]]
    return json.dumps({"nombre": entidad.get("name") or entidad.get("title") or "", "canciones": canciones}, ensure_ascii=False)


@mcp.tool(name="alexa_dispositivos")
def alexa_dispositivos() -> str:
    """Lista los Echos de la cuenta de Amazon y si están en línea."""
    return _alexa("/dispositivos")


async def voz(request):
    """Voz neuronal gratis (Microsoft Edge) para Monki: mucho más rápida que la voz sin internet de la consola.
    POST /voz {"texto": "...", "voz": "es-MX-JorgeNeural", "tono": "-6Hz", "velocidad": "+0%"} → audio MP3."""
    import edge_tts
    from starlette.responses import Response
    datos = await request.json()
    texto = str(datos.get("texto", "")).strip()[:3000]
    if not texto:
        return JSONResponse({"error": "sin texto"}, status_code=400)
    com = edge_tts.Communicate(texto, datos.get("voz") or "es-MX-JorgeNeural",
                               pitch=datos.get("tono") or "-6Hz", rate=datos.get("velocidad") or "+0%")
    audio = bytearray()
    async for parte in com.stream():
        if parte["type"] == "audio":
            audio += parte["data"]
    return Response(bytes(audio), media_type="audio/mpeg")


PORTADAS_PERMITIDAS = ("i.scdn.co", "mosaic.scdn.co", "m.media-amazon.com", "images-na.ssl-images-amazon.com")


async def portada(request):
    """Descarga la portada de la canción para la consola: el DNS del router no resuelve i.scdn.co y la Mac sí.
    POST /portada {"url": "https://i.scdn.co/..."} → imagen. Solo hosts de portadas, para no servir de proxy abierto."""
    import urllib.request
    from urllib.parse import urlparse
    from starlette.responses import Response
    url = str((await request.json()).get("url", ""))
    u = urlparse(url)
    if u.scheme != "https" or u.hostname not in PORTADAS_PERMITIDAS:
        return JSONResponse({"error": "host no permitido"}, status_code=400)
    def bajar():
        with urllib.request.urlopen(url, timeout=10) as r:
            return r.read(2_000_000), r.headers.get("Content-Type", "image/jpeg")
    datos, tipo = await anyio.to_thread.run_sync(bajar)   # sin bloquear al resto de casa
    return Response(datos, media_type=tipo)


def app_con_clave():
    from starlette.requests import Request
    app = mcp.streamable_http_app()

    async def con_clave(scope, receive, send):
        if scope["type"] == "http":
            headers = dict(scope.get("headers") or [])
            if headers.get(b"authorization", b"").decode() != f"Bearer {TOKEN}":
                await JSONResponse({"error": "clave incorrecta"}, status_code=401)(scope, receive, send)
                return
            rutas = {"/voz": voz, "/portada": portada}
            if scope.get("path") in rutas and scope.get("method") == "POST":
                try:
                    resp = await rutas[scope["path"]](Request(scope, receive))
                except Exception as e:
                    resp = JSONResponse({"error": str(e)[:200]}, status_code=502)
                await resp(scope, receive, send)
                return
        await app(scope, receive, send)

    return app, con_clave


if __name__ == "__main__":
    app, protegida = app_con_clave()
    import socket
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as so:
            so.connect(("8.8.8.8", 80)); ip = so.getsockname()[0]
    except OSError:
        ip = "?"
    print(f"✅ Casa MCP en http://{ip}:{PUERTO}/mcp  ·  clave en {DIR / '.env'}")
    config = uvicorn.Config(protegida, host="0.0.0.0", port=PUERTO, log_level="warning", lifespan="on")
    # El lifespan lo maneja la app interna de FastMCP
    config.app = protegida
    server = uvicorn.Server(config)

    async def lifespan_app(scope, receive, send):
        if scope["type"] == "lifespan":
            await app(scope, receive, send)
        else:
            await protegida(scope, receive, send)

    config.app = lifespan_app
    server.run()
