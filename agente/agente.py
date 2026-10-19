#!/usr/bin/env python3
"""Agente de voz con Gemini que ve y controla dispositivos Android (la Anbernic RG353V) por adb.

Uso:  uv run agente.py            (Enter para hablar, o escribe y Enter)
      uv run agente.py --texto    (solo texto, sin micrófono)
"""
import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from google import genai
from google.genai import types

DIR = Path(__file__).resolve().parent
MAX_PASOS = 15          # acciones máximas por pedido
IMAGENES_EN_HISTORIAL = 2  # capturas viejas se descartan para ahorrar tokens


def cargar_env():
    env = DIR / ".env"
    if env.exists():
        for linea in env.read_text().splitlines():
            if "=" in linea and not linea.strip().startswith("#"):
                k, v = linea.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())


cargar_env()
MODELO = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")

# ---------- dispositivos ----------

estado = {"serial": None}


def adb(*args, binario=False, timeout=20):
    cmd = ["adb"] + (["-s", estado["serial"]] if estado["serial"] else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return r.stdout if binario else r.stdout.decode(errors="replace").strip()


def dispositivos():
    out = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True).stdout
    res = []
    for linea in out.splitlines()[1:]:
        p = linea.split()
        if len(p) >= 2 and p[1] == "device":
            modelo = next((x.split(":", 1)[1] for x in p if x.startswith("model:")), "?")
            res.append({"serial": p[0], "modelo": modelo})
    return res


def captura():
    png = adb("exec-out", "screencap", "-p", binario=True)
    return png if png[:4] == b"\x89PNG" else None


def resolucion():
    s = adb("shell", "wm", "size")
    return s.split(":")[-1].strip() if s else "?"


# ---------- herramientas que Gemini puede usar ----------

BOTONES = {
    "A": 96, "B": 97, "X": 99, "Y": 100, "L1": 102, "R1": 103, "L2": 104, "R2": 105,
    "START": 108, "SELECT": 109, "ARRIBA": 19, "ABAJO": 20, "IZQUIERDA": 21, "DERECHA": 22,
    "ATRAS": 4, "INICIO": 3, "RECIENTES": 187, "VOLUMEN_MAS": 24, "VOLUMEN_MENOS": 25,
    "ENTER": 66,
}


def tocar(x: int, y: int) -> str:
    """Toca la pantalla en la coordenada (x, y) en píxeles de la captura."""
    adb("shell", "input", "tap", str(x), str(y))
    return f"Toqué ({x}, {y})."


def deslizar(x1: int, y1: int, x2: int, y2: int, milisegundos: int = 300) -> str:
    """Desliza el dedo de (x1, y1) a (x2, y2). Sirve para hacer scroll."""
    adb("shell", "input", "swipe", *map(str, (x1, y1, x2, y2, milisegundos)))
    return "Deslicé."


def boton(nombre: str, veces: int = 1) -> str:
    """Presiona un botón físico. Nombres: A, B, X, Y, L1, R1, L2, R2, START, SELECT,
    ARRIBA, ABAJO, IZQUIERDA, DERECHA, ATRAS, INICIO, RECIENTES, VOLUMEN_MAS, VOLUMEN_MENOS, ENTER."""
    code = BOTONES.get(nombre.upper())
    if code is None:
        return f"Botón desconocido: {nombre}."
    for _ in range(max(1, min(veces, 30))):
        adb("shell", "input", "keyevent", str(code))
    return f"Presioné {nombre} {veces} vez/veces."


def escribir_texto(texto: str) -> str:
    """Escribe texto en el campo que tenga el foco."""
    adb("shell", "input", "text", texto.replace(" ", "%s").replace("'", ""))
    return "Escribí el texto."


def listar_apps() -> str:
    """Devuelve las apps instaladas por el usuario (nombre de paquete)."""
    out = adb("shell", "pm", "list", "packages", "-3")
    return "\n".join(l.replace("package:", "") for l in out.splitlines())


def abrir_app(paquete: str) -> str:
    """Abre una app por su nombre de paquete (usa listar_apps para encontrarlo)."""
    out = adb("shell", "monkey", "-p", paquete, "-c", "android.intent.category.LAUNCHER", "1")
    return "Abierta." if "Events injected" in out else f"No pude abrir {paquete}."


def cerrar_app(paquete: str) -> str:
    """Cierra a la fuerza una app."""
    adb("shell", "am", "force-stop", paquete)
    return f"Cerré {paquete}."


def esperar(segundos: float) -> str:
    """Espera unos segundos a que la pantalla cargue."""
    time.sleep(max(0.0, min(segundos, 15)))
    return "Listo."


def listar_dispositivos() -> str:
    """Lista los dispositivos Android conectados y cuál se está controlando."""
    ds = dispositivos()
    if not ds:
        return "No hay dispositivos conectados."
    return "\n".join(f"{d['serial']} ({d['modelo']})" + (" <- actual" if d["serial"] == estado["serial"] else "") for d in ds)


def elegir_dispositivo(serial: str) -> str:
    """Cambia el dispositivo que se controla."""
    if serial not in [d["serial"] for d in dispositivos()]:
        return f"No encuentro {serial}."
    estado["serial"] = serial
    return f"Ahora controlo {serial}."


HERRAMIENTAS = [tocar, deslizar, boton, escribir_texto, listar_apps, abrir_app, cerrar_app,
                esperar, listar_dispositivos, elegir_dispositivo]
POR_NOMBRE = {f.__name__: f for f in HERRAMIENTAS}

INSTRUCCIONES = """Eres el asistente de la consola Anbernic RG353V del usuario (Android 11, pantalla {res}).
Hablas español latino, breve y amigable, como un amigo gamer. Tus respuestas se leen en voz alta:
nada de markdown, listas ni emojis; máximo 3 frases.

Con cada mensaje recibes una captura actual de la pantalla. Las coordenadas de tocar() son píxeles de esa captura.
Para cumplir un pedido usa las herramientas paso a paso; después de cada acción recibes una captura nueva.
Si algo ya está hecho o es solo una pregunta, responde sin usar herramientas.

Contexto de la consola:
- El lanzador es Daijishō (pestañas Library, Widgets, Apps, Settings arriba).
- Emuladores: RetroArch, PPSSPP (PSP), DuckStation (PS1), Mupen64Plus FZ (N64), DraStic (DS), Flycast, NES/SNES/GBA/MD/NEO.emu.
- Juegos: GBA, MAME y Neo Geo en la SD de 64 GB; PSP en memoria interna (PSP/GAME).
- El selector de carpetas de Android ignora los toques: ahí usa la cruceta (ARRIBA/ABAJO) y A o ENTER.

Reglas: no borres archivos, no desinstales apps, no cambies cuentas ni contraseñas y no hagas compras.
Si el pedido es riesgoso o ambiguo, pregunta primero."""


# ---------- voz ----------

def grabar_voz():
    wav = Path(tempfile.mkstemp(suffix=".wav")[1])
    p = subprocess.Popen(["rec", "-q", "-c", "1", "-r", "16000", str(wav)],
                         stderr=subprocess.DEVNULL)
    input("🎙  Grabando... Enter para terminar ")
    p.send_signal(signal.SIGINT)
    p.wait()
    data = wav.read_bytes()
    wav.unlink(missing_ok=True)
    return data if len(data) > 8000 else None


VOZ = None


def hablar(texto):
    global VOZ
    if VOZ is None:
        voces = subprocess.run(["say", "-v", "?"], capture_output=True, text=True).stdout
        VOZ = next((v for v in ("Paulina", "Flo (Español (México))", "Eddy (Español (México))")
                    if v.split(" ")[0] in voces), "")
    subprocess.run(["say"] + (["-v", VOZ] if VOZ else []) + [texto])


# ---------- bucle del agente ----------

def podar_imagenes(historial):
    """Deja solo las últimas capturas en el historial."""
    vistas = 0
    for msg in reversed(historial):
        nuevas = []
        for part in msg.parts:
            if part.inline_data and part.inline_data.mime_type == "image/png":
                vistas += 1
                if vistas > IMAGENES_EN_HISTORIAL:
                    nuevas.append(types.Part.from_text(text="[captura anterior omitida]"))
                    continue
            if part.inline_data and part.inline_data.mime_type.startswith("audio/"):
                nuevas.append(types.Part.from_text(text="[mensaje de voz anterior]"))
                continue
            nuevas.append(part)
        msg.parts = nuevas


def turno(cliente, config, historial, pedido_parts):
    png = captura()
    parts = list(pedido_parts)
    parts.append(types.Part.from_bytes(data=png, mime_type="image/png") if png
                 else types.Part.from_text(text="[No pude capturar la pantalla]"))
    historial.append(types.Content(role="user", parts=parts))

    for _ in range(MAX_PASOS):
        podar_imagenes(historial)
        r = cliente.models.generate_content(model=MODELO, contents=historial, config=config)
        cand = r.candidates[0].content if r.candidates else None
        if cand is None:
            return "No obtuve respuesta de Gemini."
        historial.append(cand)
        llamadas = [p.function_call for p in cand.parts if p.function_call]
        if not llamadas:
            return "".join(p.text or "" for p in cand.parts).strip() or "Listo."
        respuestas = []
        for fc in llamadas:
            f = POR_NOMBRE.get(fc.name)
            try:
                res = f(**(fc.args or {})) if f else f"Herramienta desconocida: {fc.name}"
            except Exception as e:  # el error vuelve a Gemini para que corrija
                res = f"Error: {e}"
            print(f"   · {fc.name}({dict(fc.args or {})}) → {res.splitlines()[0] if res else ''}")
            respuestas.append(types.Part.from_function_response(name=fc.name, response={"resultado": res}))
        time.sleep(1.2)
        png = captura()
        if png:
            respuestas.append(types.Part.from_bytes(data=png, mime_type="image/png"))
        historial.append(types.Content(role="user", parts=respuestas))
    return "Hice varios pasos y me detuve para no pasarme. ¿Sigo?"


def main():
    solo_texto = "--texto" in sys.argv
    clave = os.environ.get("GEMINI_API_KEY")
    if not clave:
        sys.exit(f"❌ Falta GEMINI_API_KEY: ponla en {DIR / '.env'}")
    ds = dispositivos()
    if not ds:
        sys.exit("❌ No hay dispositivos: conecta la consola por USB con depuración USB activa.")
    estado["serial"] = ds[0]["serial"]

    cliente = genai.Client(api_key=clave)
    config = types.GenerateContentConfig(
        system_instruction=INSTRUCCIONES.format(res=resolucion()),
        tools=HERRAMIENTAS,
        automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
    )
    historial = []
    print(f"✅ Agente listo · {ds[0]['modelo']} · modelo {MODELO}")
    print("Enter vacío = hablar · escribe y Enter = texto · 'salir' para terminar\n")

    while True:
        try:
            linea = input("Tú: ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if linea.lower() in ("salir", "exit", "q"):
            break
        if linea:
            pedido = [types.Part.from_text(text=linea)]
        elif solo_texto:
            continue
        else:
            audio = grabar_voz()
            if not audio:
                print("(no se escuchó nada)")
                continue
            pedido = [types.Part.from_bytes(data=audio, mime_type="audio/wav"),
                      types.Part.from_text(text="(El usuario habló por voz.)")]
        try:
            respuesta = turno(cliente, config, historial, pedido)
        except Exception as e:
            respuesta = f"Tuve un problema con Gemini: {e}"
        print(f"Agente: {respuesta}\n")
        hablar(respuesta)


if __name__ == "__main__":
    main()
