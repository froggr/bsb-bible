#!/usr/bin/env python3
"""ANDROID_SERIAL=emulator-5556 python3 tools/tap.py list
   ANDROID_SERIAL=emulator-5556 python3 tools/tap.py "<text>" [nth]

Taps the centre of the element whose text contains <text>; "=<text>" wants the whole text.
"list" prints every text on the screen with its bounds. Something drawn with no text, such
as the microphone mark, is not listed: find it on a screenshot and use `adb shell input tap`.
A dump that fails leaves the last one in place, so a list that does not match the screen
means the app is not in front."""
import os, re, subprocess, sys
if not os.environ.get('ANDROID_SERIAL'):
    sys.exit('set ANDROID_SERIAL: a real phone may be plugged in')
ADB = '/opt/homebrew/share/android-commandlinetools/platform-tools/adb'
subprocess.run([ADB, 'shell', 'uiautomator', 'dump', '/sdcard/u.xml'], capture_output=True)
x = subprocess.run([ADB, 'shell', 'cat', '/sdcard/u.xml'], capture_output=True, text=True).stdout
els = [(m[1].replace('&amp;', '&'), [int(n) for n in re.findall(r'\d+', m[2])])
       for m in re.finditer(r'text="([^"]+)"[^>]*bounds="([^"]+)"', x)]
if len(sys.argv) < 2:
    sys.exit(__doc__)
if sys.argv[1] == 'list':
    for t, b in els:
        print(t[:70], b)
    sys.exit()
q = sys.argv[1]
hits = [e for e in els if (e[0] == q[1:] if q.startswith('=') else q in e[0])]
if not hits:
    print('NOT FOUND:', q)
    sys.exit(1)
t, b = hits[int(sys.argv[2]) if len(sys.argv) > 2 else 0]
subprocess.run([ADB, 'shell', 'input', 'tap', str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2)])
print('tapped', t[:50], b)
