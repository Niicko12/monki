#!/usr/bin/env bash
# Descarga el modelo pequeño de español de Vosk (palabra de activación sin internet) a los assets de la app.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=asistente-apk/app/src/main/assets/model-es
[ -d "$DEST" ] && { echo "Ya existe $DEST"; exit 0; }
TMP=$(mktemp -d)
curl -L -o "$TMP/modelo.zip" https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip
unzip -q "$TMP/modelo.zip" -d "$TMP"
mkdir -p "$(dirname "$DEST")"
mv "$TMP/vosk-model-small-es-0.42" "$DEST"
# StorageService de Vosk usa este archivo para saber si ya copió el modelo al almacenamiento
uuidgen > "$DEST/uuid"
rm -rf "$TMP"
echo "✅ Modelo en $DEST"
