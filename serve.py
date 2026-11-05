#!/usr/bin/env python3
"""Hand the phone its builds and its data, over the LAN or Tailscale.

    python3 serve.py [port]            # default 8099

Open http://<this-machine>:8099 on the phone to install the APK. The app uses the same
server to fetch its optional data layers and to check for a newer build:

    GET /manifest.json   what is available, with sizes and checksums
    GET /app.apk         the current build
    GET /data/<name>.gz  a data layer, gzipped

Serving a build to a phone is the whole job, so this stays a single file with no deps.
"""
import gzip, hashlib, http.server, json, pathlib, re, shutil, socket, socketserver, sys, time

HERE = pathlib.Path(__file__).parent
APK = HERE / "app/app/build/outputs/apk/debug/app-debug.apk"
DATA = HERE / "data"
PUB = HERE / "public"          # what is actually served; built on start
LAYERS = ["bible-study.db", "bible-commentary.db"]
BEDS = HERE / "data/beds"      # the music beds, fetched by the app one file at a time

_sums: dict[str, str] = {}


def sha(path: pathlib.Path) -> str:
    key = f"{path}:{path.stat().st_mtime_ns}"
    if key not in _sums:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        _sums.clear()
        _sums[key] = h.hexdigest()
    return _sums[key]


def version_of(apk: pathlib.Path) -> str:
    """versionName from the APK, so the app can tell whether this build is newer."""
    try:
        import zipfile
        with zipfile.ZipFile(apk) as z:
            raw = z.read("AndroidManifest.xml")
        # Binary XML: pull the printable version string without a full parser.
        text = raw.decode("utf-16-le", "ignore")
        m = re.search(r"\b(\d+\.\d+(?:\.\d+)?)\b", text)
        if m:
            return m.group(1)
    except Exception:
        pass
    return time.strftime("%Y%m%d", time.localtime(apk.stat().st_mtime))


def prepare():
    """Copy the APK in and gzip any data layer that has changed."""
    PUB.mkdir(exist_ok=True)
    (PUB / "data").mkdir(exist_ok=True)
    # Opus is already compressed, so the beds are copied rather than gzipped.
    if BEDS.is_dir():
        (PUB / "beds").mkdir(exist_ok=True)
        for src in sorted(BEDS.glob("*.opus")):
            dest = PUB / "beds" / src.name
            if not dest.exists() or dest.stat().st_mtime_ns < src.stat().st_mtime_ns:
                shutil.copy2(src, dest)
    if APK.exists():
        dest = PUB / "app.apk"
        if not dest.exists() or dest.stat().st_mtime_ns < APK.stat().st_mtime_ns:
            shutil.copy2(APK, dest)
    for name in LAYERS:
        src = DATA / name
        if not src.exists():
            continue
        dest = PUB / "data" / (name + ".gz")
        if dest.exists() and dest.stat().st_mtime_ns >= src.stat().st_mtime_ns:
            continue
        print(f"  gzip {name} …", flush=True)
        with open(src, "rb") as a, gzip.open(dest, "wb", 6) as b:
            shutil.copyfileobj(a, b, 1 << 20)


def manifest():
    m = {"generated": int(time.time()), "layers": {}}
    apk = PUB / "app.apk"
    if apk.exists():
        m["app"] = {"url": "/app.apk", "bytes": apk.stat().st_size,
                    "version": version_of(apk), "sha256": sha(apk)}
    for name in LAYERS:
        f = PUB / "data" / (name + ".gz")
        if f.exists():
            m["layers"][name] = {"url": f"/data/{name}.gz", "bytes": f.stat().st_size,
                                 "sha256": sha(f)}
    beds = sorted((PUB / "beds").glob("*.opus")) if (PUB / "beds").is_dir() else []
    if beds:
        m["beds"] = {b.stem: {"url": f"/beds/{b.name}", "bytes": b.stat().st_size}
                     for b in beds}
    return m


def human(n):
    return f"{n/1e6:.1f} MB" if n >= 1e6 else f"{n/1e3:.0f} KB"


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BSB Bible</title>
<style>
  :root {{ color-scheme: light dark; }}
  * {{ box-sizing: border-box; }}
  html {{ width:100%; overflow-x:hidden; }}
  body {{ margin:0 auto; padding:40px 22px; width:100%; max-width:480px;
         font:16px/1.6 -apple-system,Roboto,system-ui,sans-serif; }}
  h1 {{ font-size:1.5rem; margin:0 0 .2em; }}
  p.sub {{ color:#888; margin:0 0 2em; }}
  a.get {{ display:block; width:100%; text-align:center; background:#a8762c; color:#fff;
          text-decoration:none; padding:18px 12px; border-radius:12px; font-weight:600;
          font-size:1.05rem; }}
  a.get small {{ display:block; font-weight:400; opacity:.85; font-size:.8rem; margin-top:3px; }}
  table {{ width:100%; table-layout:fixed; border-collapse:collapse; margin-top:2em; font-size:.9rem; }}
  td {{ padding:9px 0; border-bottom:1px solid #8884; }}
  td:last-child {{ text-align:right; color:#888; font-variant-numeric:tabular-nums; }}
  .note {{ margin-top:2em; font-size:.85rem; color:#888; }}
</style></head><body>
<h1>BSB Bible</h1>
<p class="sub">BSB study Bible &middot; build {version}</p>
{apk}
<table>{rows}</table>
<p class="note">Android asks twice: once to allow installs from the browser, once to install.
The study and commentary layers are downloaded by the app itself, not here.</p>
</body></html>"""


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(PUB), **kw)

    def do_GET(self):
        if self.path == "/":
            return self.index()
        if self.path == "/manifest.json":
            body = json.dumps(manifest(), indent=1).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        return super().do_GET()

    def index(self):
        prepare()
        m = manifest()
        apk = m.get("app")
        btn = (f'<a class="get" href="/app.apk" download>Install the app'
               f'<small>{human(apk["bytes"])} &middot; Android 10 and up</small></a>'
               if apk else "<p>No build yet. Run <code>./gradlew assembleDebug</code>.</p>")
        rows = "".join(
            f"<tr><td>{n.replace('bible-','').replace('.db','').title()} layer</td>"
            f"<td>{human(v['bytes'])}</td></tr>" for n, v in m["layers"].items())
        body = PAGE.format(version=(apk or {}).get("version", "—"), apk=btn, rows=rows).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def end_headers(self):
        # An APK must arrive as bytes the package installer accepts, never cached stale.
        if self.path.endswith(".apk"):
            self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        print(f"  {self.address_string()}  {fmt % args}", flush=True)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def addresses():
    out = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80)); out.append(s.getsockname()[0]); s.close()
    except Exception:
        pass
    try:
        import subprocess
        r = subprocess.run(["/Applications/Tailscale.app/Contents/MacOS/Tailscale", "status", "--json"],
                           capture_output=True, text=True, timeout=5)
        name = json.loads(r.stdout)["Self"]["DNSName"].rstrip(".")
        if name: out.append(name)
    except Exception:
        pass
    return out


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    print("preparing…", flush=True)
    prepare()
    m = manifest()
    if "app" in m:
        print(f"  app.apk  {human(m['app']['bytes'])}  build {m['app']['version']}")
    for n, v in m["layers"].items():
        print(f"  {n}.gz  {human(v['bytes'])}")
    print("\nopen on the phone:")
    for a in addresses():
        print(f"  http://{a}:{port}")
    print()
    Server(("", port), Handler).serve_forever()
