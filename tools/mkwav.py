"""mkwav.py out.wav part...   each part is a wav path or a number of seconds of silence; 16 kHz mono 16 bit."""
import sys, wave
out = wave.open(sys.argv[1], "wb"); out.setnchannels(1); out.setsampwidth(2); out.setframerate(16000)
for part in sys.argv[2:]:
    try:
        out.writeframes(b"\0\0" * int(float(part) * 16000))
    except ValueError:
        w = wave.open(part, "rb"); assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 16000), part
        out.writeframes(w.readframes(w.getnframes()))
out.close()
