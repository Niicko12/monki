"""Mide ruido, saturación y reparto de frecuencias de un .wav (por ejemplo wake-debug.wav de la consola).

Uso: uv run --with numpy python analizar-audio.py wake-debug.wav
"""
import sys, wave, numpy as np
w = wave.open(sys.argv[1]); x = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(float); sr = w.getframerate()
f = 320
rms = np.sqrt([np.mean(x[i:i+f]**2) for i in range(0, len(x)-f, f)])
print(f"dur {len(x)/sr:.1f}s  rms p10 {np.percentile(rms,10):.0f}  p50 {np.percentile(rms,50):.0f}  p95 {np.percentile(rms,95):.0f}  max {np.abs(x).max():.0f}  clip {np.mean(np.abs(x)>=32000)*100:.2f}%")
spec = np.abs(np.fft.rfft(x * np.hanning(len(x)))); fr = np.fft.rfftfreq(len(x), 1/sr)
for lo, hi in [(0,80),(80,300),(300,1000),(1000,3000),(3000,8000)]:
    m = (fr>=lo)&(fr<hi); print(f"  {lo:>4}-{hi:<4} Hz energía {100*np.sum(spec[m]**2)/np.sum(spec**2):5.1f}%")
pk = fr[np.argsort(spec)[-5:]]; print("  picos Hz:", sorted(np.round(pk)))
