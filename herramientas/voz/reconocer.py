"""Pasa .wav por Vosk con una gramática y muestra palabras y seguridad.

Uso: uv run --with vosk python reconocer.py <carpeta-modelo> '["hey monkey","hey","[unk]"]' audio1.wav ...
"""
import json, sys, wave
from vosk import Model, KaldiRecognizer, SetLogLevel
SetLogLevel(-1)
m = Model(sys.argv[1]); g = json.loads(sys.argv[2])
for f in sys.argv[3:]:
    r = KaldiRecognizer(m, 16000, json.dumps(g)); r.SetWords(True)
    w = wave.open(f); r.AcceptWaveform(w.readframes(w.getnframes()))
    res = json.loads(r.FinalResult()).get("result", [])
    print(f, [(x["word"], round(x["conf"],2)) for x in res])
