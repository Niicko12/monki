"""Simula el micrófono de la consola (apagado arriba de 1 kHz, zumbido 60/180 Hz) y compara filtros en Vosk.

Uso: uv run --with vosk --with numpy --with scipy python simular-microfono.py <carpeta-modelo> voz1.wav ...
"""
import sys, wave, json, numpy as np
from scipy.signal import butter, sosfilt, iirnotch, tf2sos
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
sr = 16000
m = Model(sys.argv[1])
G = ["hey monkey","monkey","hey mon qui","mon qui","hey mon ki","mon ki","hey moni","moni","hey qui","qui","hey","[unk]"]
def rd(f):
    w = wave.open(f); return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(float)
def rec(x):
    r = KaldiRecognizer(m, sr, json.dumps(G)); r.SetWords(True)
    pad = np.zeros(sr//2); y = np.clip(np.concatenate([pad, x, pad]), -32768, 32767).astype(np.int16)
    r.AcceptWaveform(y.tobytes()); return [(a["word"], round(a["conf"],2)) for a in json.loads(r.FinalResult()).get("result", [])]
def consola(x, rng):
    # micrófono de la consola: apagado arriba de ~1 kHz, zumbido 60/180 Hz y siseo
    x = sosfilt(butter(4, 1000, "low", fs=sr, output="sos"), x) * 0.5
    t = np.arange(len(x))/sr
    return x + 1500*np.sin(2*np.pi*60*t) + 700*np.sin(2*np.pi*180*t) + rng.normal(0, 400, len(x))
def filtro(x, enfasis):
    y = sosfilt(butter(4, 100, "high", fs=sr, output="sos"), x)
    for f0 in (120, 180, 240):
        b, a = iirnotch(f0, 8, sr); y = sosfilt(tf2sos(b, a), y)
    if enfasis:
        y = np.append(y[0], y[1:] - 0.95*y[:-1])   # realza consonantes
    return y * (8000/ max(1, np.percentile(np.abs(y), 99)))
rng = np.random.default_rng(1)
for f in sys.argv[2:]:
    x = rd(f); c = consola(x, rng)
    print(f, "| crudo:", rec(c), "| filtro:", rec(filtro(c, False)), "| filtro+énfasis:", rec(filtro(c, True)))
