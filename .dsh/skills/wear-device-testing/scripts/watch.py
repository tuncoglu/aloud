#!/usr/bin/env python3
"""Drive the Aloud app on a Wear OS watch over adb.

Wraps the parts of on-device testing that are easy to get wrong: duplicate adb
devices after mdns, dumps taken while the screen is dozing, and uploads that
have to survive interruption. See ../SKILL.md for the workflow.
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PKG = "com.emre.aloud"
ACTIVITY = f"{PKG}/com.emre.aloud.MainActivity"


def sh(cmd: str, timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)


def adb(args: str, timeout: int = 120) -> str:
    return sh(f"adb {args}", timeout=timeout).stdout


# ── device ──────────────────────────────────────────────────────────────────

def devices() -> list[str]:
    out = adb("devices")
    return [l.split()[0] for l in out.splitlines()[1:] if l.strip() and "device" in l]


def ensure_single_device() -> str | None:
    """mdns often leaves the watch attached twice, which breaks `adb shell`."""
    ds = devices()
    if len(ds) > 1:
        # Drop the raw ip:port entry and keep the stable mdns name.
        for d in ds:
            if re.match(r"^\d+\.\d+\.\d+\.\d+:\d+$", d):
                sh(f"adb disconnect {d}")
        ds = devices()
    return ds[0] if ds else None


def wake() -> None:
    adb("shell input keyevent KEYCODE_WAKEUP")
    time.sleep(2)


# ── screen ──────────────────────────────────────────────────────────────────

def shot(path: str) -> str:
    wake()
    subprocess.run(f"adb exec-out screencap -p > {path}", shell=True, timeout=120)
    return path


def dump_xml() -> str:
    wake()
    adb("shell uiautomator dump /sdcard/_watch.xml")
    xml = adb("shell cat /sdcard/_watch.xml")
    adb("shell rm -f /sdcard/_watch.xml")
    return xml


def texts() -> list[str]:
    return [m.group(1) for m in re.finditer(r'text="([^"]*)"', dump_xml()) if m.group(1).strip()]


def tap(label: str) -> bool:
    xml = dump_xml()
    m = re.search(
        r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(label), xml
    )
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    adb(f"shell input tap {x} {y}")
    time.sleep(2)
    return True


def scroll(up: bool = False) -> None:
    """Only moves a verticalScroll (NowPlaying). Lazy lists ignore synthetic input."""
    a, b = (180, 400) if up else (400, 180)
    adb(f"shell input swipe 240 {a} 240 {b} 400")
    time.sleep(2)


# ── app ─────────────────────────────────────────────────────────────────────

def launch(cold: bool = True) -> None:
    if cold:
        adb(f"shell am force-stop {PKG}")
        time.sleep(1)
    adb(f"shell monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(9)


def log(seconds: int = 30) -> str:
    return adb(f'logcat -d -s Aloud:D -t {seconds}s -v time')


def doctor() -> int:
    dev = ensure_single_device()
    if not dev:
        print("no device. Enable wireless debugging, then:")
        print("  adb mdns services && adb connect <ip:port>")
        return 1
    print(f"device      : {dev}")
    info = adb(f"shell dumpsys package {PKG} | grep -E 'versionName|flags=\\['")
    ver = re.search(r"versionName=(\S+)", info)
    dbg = "DEBUGGABLE" in info
    print(f"installed   : {ver.group(1) if ver else '(not installed)'}"
          f"  [{'debug' if dbg else 'release'}]")
    if not dbg:
        print("              run-as unavailable -> books must go via the uploader")
    wl = adb("shell dumpsys power | grep -m1 mWakefulness=").strip()
    print(f"screen      : {wl or 'unknown'}")
    free = adb("shell df -h /data | tail -1").split()
    if len(free) >= 4:
        print(f"free space  : {free[3]}")
    return 0


def verify_apk(local: str) -> int:
    path = adb(f"shell pm path {PKG}").replace("package:", "").strip()
    if not path:
        print("not installed")
        return 1
    adb(f'pull "{path}" /tmp/_installed.apk')
    a = hashlib.sha256(open("/tmp/_installed.apk", "rb").read()).hexdigest()
    b = hashlib.sha256(open(local, "rb").read()).hexdigest()
    print(f"on device : {a}")
    print(f"local     : {b}")
    print("MATCH" if a == b else "DIFFERENT — the watch is not running this build")
    return 0 if a == b else 1


# ── audiobooks ──────────────────────────────────────────────────────────────

def chapters(folder: str) -> int:
    import json
    files = sorted(glob.glob(os.path.join(folder, "*.m4b")) + glob.glob(os.path.join(folder, "*.mp3")))
    for f in files:
        out = sh(f'ffprobe -v quiet -print_format json -show_chapters "{f}"', timeout=300).stdout
        try:
            n = len(json.loads(out).get("chapters", []))
        except Exception:
            n = -1
        print(f"  {n:3}  {os.path.basename(f)[:64]}")
    return 0


def upload(folder: str, pin: str, host: str = "192.168.0.15", port: int = 8080) -> int:
    base = f"http://{host}:{port}"
    chunk = 4 << 20

    def post(url: str, body: bytes):
        req = urllib.request.Request(url, data=body, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code
        except Exception as e:
            return str(e)

    try:
        listing = urllib.request.urlopen(f"{base}/books?pin={pin}", timeout=30).read().decode()
    except Exception as e:
        print(f"uploader unreachable at {base}: {e}")
        print("Start it on the watch: Uploader -> Start, and check the PIN.")
        return 1
    have = set(re.findall(r'"name":"((?:[^"\\]|\\.)*)"', listing))
    files = sorted(glob.glob(os.path.join(folder, "*.m4b")) + glob.glob(os.path.join(folder, "*.mp3")))
    todo = [f for f in files if os.path.basename(f) not in have]
    total_gb = sum(os.path.getsize(f) for f in todo) / 2**30
    print(f"on watch: {len(have)}   to upload: {len(todo)} ({total_gb:.1f} GB)\n", flush=True)

    for i, f in enumerate(todo, 1):
        name = urllib.parse.quote(os.path.basename(f))
        size = os.path.getsize(f)
        started = time.time()
        ok = True
        with open(f, "rb") as fh:
            off = 0
            while off < size:
                data = fh.read(chunk)
                st = post(f"{base}/book?name={name}&pin={pin}&offset={off}&total={size}", data)
                if st != 200:
                    print(f"[{i}/{len(todo)}] FAIL at {off}: {st}  {os.path.basename(f)[:40]}", flush=True)
                    ok = False
                    break
                off += len(data)
        if ok:
            d = time.time() - started
            mb = size / 2**20
            print(f"[{i}/{len(todo)}] {mb:7.0f} MB {d:5.0f}s {mb/max(d,1):4.1f} MB/s  "
                  f"{os.path.basename(f)[:42]}", flush=True)

    # verify sizes
    listing = urllib.request.urlopen(f"{base}/books?pin={pin}", timeout=30).read().decode()
    on = dict(zip(re.findall(r'"name":"((?:[^"\\]|\\.)*)"', listing),
                  (int(x) for x in re.findall(r'"size":(\d+)', listing))))
    bad = 0
    for f in files:
        b = os.path.basename(f)
        if on.get(b) != os.path.getsize(f):
            print(f"  MISMATCH: {b}")
            bad += 1
    print(f"\nverified {len(files)-bad}/{len(files)} byte-exact")
    return 1 if bad else 0


# ── cli ─────────────────────────────────────────────────────────────────────

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("doctor", help="check device, build type, screen state")
    sub.add_parser("launch", help="cold start the app")
    sub.add_parser("text", help="labels currently on screen (prefer a screenshot)")
    sub.add_parser("back", help="press back")
    s = sub.add_parser("shot", help="screenshot to PNG; view it with read_image")
    s.add_argument("path", nargs="?", default="/tmp/watch.png")
    s = sub.add_parser("tap", help="tap a visible label")
    s.add_argument("label")
    s = sub.add_parser("scroll", help="scroll NowPlaying (lazy lists ignore this)")
    s.add_argument("--up", action="store_true")
    s = sub.add_parser("log", help="Aloud log lines")
    s.add_argument("seconds", nargs="?", type=int, default=30)
    s = sub.add_parser("verify-apk", help="is the watch running this exact APK?")
    s.add_argument("apk")
    s = sub.add_parser("chapters", help="expected chapter counts via ffprobe")
    s.add_argument("folder", nargs="?", default=os.path.expanduser("~/Audiobooks"))
    s = sub.add_parser("upload", help="upload books through the app's HTTP uploader")
    s.add_argument("folder", nargs="?", default=os.path.expanduser("~/Audiobooks"))
    s.add_argument("--pin", required=True)
    s.add_argument("--host", default="192.168.0.15")
    a = p.parse_args()

    # `doctor` diagnoses a missing device, so it must run without one.
    if a.cmd not in ("chapters", "doctor") and not ensure_single_device():
        print("no device attached — run `doctor`")
        return 1

    if a.cmd == "doctor":
        return doctor()
    if a.cmd == "launch":
        launch(); print("\n".join(texts())); return 0
    if a.cmd == "text":
        print("\n".join(texts())); return 0
    if a.cmd == "back":
        adb("shell input keyevent KEYCODE_BACK"); time.sleep(2); print("\n".join(texts())); return 0
    if a.cmd == "shot":
        print(shot(a.path)); return 0
    if a.cmd == "tap":
        if not tap(a.label):
            print(f"no visible label {a.label!r}. On screen: {texts()}")
            return 1
        print("\n".join(texts())); return 0
    if a.cmd == "scroll":
        scroll(a.up); print("\n".join(texts())); return 0
    if a.cmd == "log":
        print(log(a.seconds)); return 0
    if a.cmd == "verify-apk":
        return verify_apk(a.apk)
    if a.cmd == "chapters":
        return chapters(a.folder)
    if a.cmd == "upload":
        return upload(a.folder, a.pin, a.host)
    return 1


if __name__ == "__main__":
    sys.exit(main())
