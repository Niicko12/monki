#!/usr/bin/env python3
"""Autoriza a Monki a controlar tu Spotify (una sola vez) y guarda el permiso en la consola por adb.

Uso:  python3 herramientas/spotify-autorizar.py TU_CLIENT_ID

Necesitas una app en https://developer.spotify.com/dashboard con la Redirect URI
http://127.0.0.1:8899/callback. Usa PKCE: no hace falta el "client secret".
El permiso (refresh token) solo viaja a la consola; no se guarda en este repo.
"""
import base64
import hashlib
import http.server
import json
import secrets
import subprocess
import sys
import urllib.parse
import urllib.request
import webbrowser

REDIRECT = "http://127.0.0.1:8899/callback"
SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    client_id = sys.argv[1].strip()
    verifier = secrets.token_urlsafe(64)[:96]
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": client_id, "response_type": "code", "redirect_uri": REDIRECT, "scope": SCOPES,
        "code_challenge_method": "S256", "code_challenge": challenge, "state": state,
    })
    code = {}

    class Callback(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if q.get("state", [""])[0] == state and "code" in q:
                code["value"] = q["code"][0]
                msg = "Listo: Monki ya puede controlar tu Spotify. Puedes cerrar esta ventana."
            else:
                msg = "No se autorizó: " + q.get("error", ["respuesta inválida"])[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(msg.encode())

        def log_message(self, *a):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 8899), Callback)
    print("Abriendo el navegador para que autorices a Monki…")
    webbrowser.open(url)
    while "value" not in code:
        server.handle_request()

    data = urllib.parse.urlencode({
        "grant_type": "authorization_code", "code": code["value"], "redirect_uri": REDIRECT,
        "client_id": client_id, "code_verifier": verifier,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request("https://accounts.spotify.com/api/token", data=data)) as r:
        tokens = json.load(r)
    refresh = tokens["refresh_token"]
    subprocess.run(["adb", "shell", "am", "start", "-f", "0x10008000", "-n", "com.limber.asistente/.MainActivity",
                    "--es", "spotify_client_id", client_id, "--es", "spotify_refresh", refresh], check=True,
                   stdout=subprocess.DEVNULL)
    print("✅ Permiso guardado en la consola.")


if __name__ == "__main__":
    main()
