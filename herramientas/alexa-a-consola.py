#!/usr/bin/env python3
"""Copia la sesión de Amazon del puente de Alexa (casa/alexa/alexa-cookie.json) a Monki en la consola, por adb.

Así el Echo se controla desde la consola aunque la Mac esté apagada. La sesión solo viaja a la consola
(preferencias de la app); no se imprime ni se guarda en el repo. Uso:

    python3 herramientas/alexa-a-consola.py [Nombre del Echo principal]
"""
import json
import shlex
import subprocess
import sys
from pathlib import Path

archivo = Path(__file__).resolve().parent.parent / "casa" / "alexa" / "alexa-cookie.json"
d = json.loads(archivo.read_text())
valores = {
    "alexa_cookie": d["localCookie"], "alexa_csrf": d["csrf"], "alexa_refresh": d["refreshToken"],
    "alexa_login": d.get("loginCookie", ""), "alexa_app": d.get("deviceAppName", ""),
}
if len(sys.argv) > 1:
    valores["alexa_principal"] = sys.argv[1]
extras = " ".join(f"--es {k} {shlex.quote(v)}" for k, v in valores.items() if v)
orden = f"am start -f 0x10008000 -n com.limber.asistente/.MainActivity {extras}"
subprocess.run(["adb", "shell", orden], check=True, stdout=subprocess.DEVNULL)
print("✅ Sesión de Alexa copiada a la consola.")
