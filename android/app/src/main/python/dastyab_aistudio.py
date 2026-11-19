#!/usr/bin/env python3
"""
dastyab aistudio — one-command clean-IP access to Google AI Studio.

USAGE:
  dastyab aistudio start [pool index]     -> start tunnel + open AI Studio
  dastyab aistudio watch [interval secs]  -> keep tunnel alive, rotating nodes
  dastyab aistudio verify                 -> check which server works + your clean IP
  dastyab aistudio stop                   -> stop the tunnel
  dastyab aistudio build-pool <uris.txt>  -> rebuild the pool from server cards
"""
import json
import os
import re
import signal
import socket
import subprocess
import sys
import time
import urllib.parse

import dastyab_android_shim as _shim

SOCK = int(os.environ.get("DASTYAB_AISTUDIO_SOCKS", "10850"))
AISTUDIO_URL = "https://aistudio.google.com/"
CHROME_CANDS = [
    "/opt/google/chrome/google-chrome",
    "/usr/bin/google-chrome",
    "/usr/bin/google-chrome-stable",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/snap/bin/chromium",
    "/usr/bin/microsoft-edge",
    "/usr/bin/brave-browser",
]


def _paths():
    home = os.path.expanduser("~")
    tmp = _shim.tmp_dir()
    return (
        home,
        os.path.join(home, ".dastyab", "profiles", "default", "fresh_vless.json"),
        os.path.join(tmp, "dastyab_aistudio.pid"),
        os.path.join(tmp, "dastyab_aistudio.json"),
        os.path.join(tmp, "dastyab_aistudio_idx"),
        os.path.join(tmp, "dastyab_aistudio_bridge.json"),
    )


def find_xray():
    """Locate an xray-compatible core (or raise SystemExit on desktop without xray)."""
    here = os.path.dirname(os.path.abspath(__file__))
    found = _shim.find_xray_android(extra_paths=(os.path.join(here, "xray"),))
    if found:
        return found
    raise SystemExit(
        "[aistudio] No xray core found. Install xray and put it on PATH, or set "
        "DASTYAB_XRAY=/path/to/xray"
    )


_XRAY_VERSION = None


def xray_supports_vision():
    """True if the resolved core is xray >= 1.7 (vision/XTLS support)."""
    global _XRAY_VERSION
    if _XRAY_VERSION is None:
        _XRAY_VERSION = ""
        try:
            out = subprocess.run(
                [find_xray(), "version"], capture_output=True, text=True, timeout=10
            ).stdout
            m = re.search(r"Xray\s+(\d+)\.(\d+)", out)
            if m:
                _XRAY_VERSION = f"{m.group(1)}.{m.group(2)}"
        except (SystemExit, OSError, subprocess.SubprocessError):
            _XRAY_VERSION = ""
    if not _XRAY_VERSION:
        return False
    major, minor = (int(x) for x in _XRAY_VERSION.split("."))
    return (major, minor) >= (1, 7)


def build_cfg(server):
    q = server.get("q", {})
    addr = server["addr"]
    port = server["port"]
    uuid = server["uuid"]
    sec = q.get("security", "none")
    net = q.get("type", "tcp")
    out = {
        "protocol": "vless",
        "settings": {
            "vnext": [
                {
                    "address": addr,
                    "port": port,
                    "users": [{"id": uuid, "encryption": "none"}],
                }
            ]
        },
    }
    flow = q.get("flow")
    if flow:
        if sec not in ("tls", "reality"):
            flow = None
        elif not xray_supports_vision():
            flow = None
    if flow:
        out["settings"]["vnext"][0]["users"][0]["flow"] = flow
    ss = {}
    if sec == "reality":
        ss.update(
            security="reality",
            realitySettings={
                "serverName": q.get("sni", addr),
                "fingerprint": q.get("fp", "chrome"),
                "publicKey": q.get("pbk", ""),
                "shortId": q.get("sid", ""),
                "spiderX": q.get("spx", "/"),
            },
        )
    elif sec == "tls":
        ss["security"] = "tls"
        ss["tlsSettings"] = {
            "serverName": q.get("sni", addr),
            "fingerprint": q.get("fp", "chrome"),
        }
        if q.get("alpn"):
            ss["tlsSettings"]["alpn"] = [x for x in q["alpn"].split(",") if x]
        if q.get("pinSHA256"):
            ss["tlsSettings"]["pinnedPeerCertSha256"] = [q["pinSHA256"]]
    else:
        ss["security"] = "none"
    if net == "ws":
        ss["network"] = "ws"
        ss["wsSettings"] = {
            "path": q.get("path", "/"),
            "headers": {"Host": q.get("host") or q.get("sni") or addr},
        }
    elif net == "xhttp":
        ss["network"] = "xhttp"
        ss["xhttpSettings"] = {
            "path": q.get("path", "/"),
            "host": q.get("host") or q.get("sni") or addr,
            "mode": q.get("mode", "auto"),
        }
    elif net == "grpc":
        ss["network"] = "grpc"
        ss["grpcSettings"] = {"serviceName": q.get("serviceName", "")}
    else:
        ss["network"] = "tcp"
    out["streamSettings"] = ss
    inb = {
        "listen": "127.0.0.1",
        "port": SOCK,
        "protocol": "socks",
        "settings": {"auth": "noauth", "udp": True},
        "tag": "socks-in",
    }
    return {
        "log": {"loglevel": "warning"},
        "inbounds": [inb],
        "outbounds": [out, {"protocol": "freedom", "tag": "direct"}],
    }


def is_running():
    _, _, pidfile, _, _, bridgefile = _paths()
    if os.path.exists(pidfile):
        try:
            pid = int(open(pidfile).read())
            os.kill(pid, 0)
            return True
        except (ProcessLookupError, ValueError, PermissionError, OSError):
            pass
    if os.path.exists(bridgefile):
        return True
    return False


def _find_xray_optional():
    here = os.path.dirname(os.path.abspath(__file__))
    return _shim.find_xray_android(extra_paths=(os.path.join(here, "xray"),))


def start(server_index=0):
    _, pool_path, _, _, idxfile, bridgefile = _paths()
    if is_running():
        print(f"[aistudio] Tunnel already running on 127.0.0.1:{SOCK}")
        print("[aistudio] Opening AI Studio through the existing tunnel...")
        launch_browser()
        return True, None

    if _find_xray_optional() is None and _shim.is_android():
        return _start_android_bridge(server_index, pool_path, idxfile, bridgefile)

    try:
        with open(pool_path) as fh:
            pool = json.load(fh)
    except FileNotFoundError:
        raise SystemExit("[aistudio] No server pool yet. Run: dastyab aistudio verify")
    except (json.JSONDecodeError, OSError) as e:
        raise SystemExit(
            f"[aistudio] Server pool is unreadable ({e}). Run: dastyab aistudio verify"
        )
    if not pool:
        raise SystemExit("[aistudio] Server pool is empty. Run: dastyab aistudio verify")

    first = server_index % len(pool)
    order = [first] + [(first + off) % len(pool) for off in range(1, len(pool))]
    dead = []
    for idx in order:
        egress = _bring_up(pool[idx])
        if egress:
            if dead:
                print(f"[aistudio] Skipped dead node(s): {', '.join(dead)}", file=sys.stderr)
            with open(idxfile, "w") as fh:
                fh.write(str(idx))
            print(f"[aistudio] Clean egress IP: {egress}")
            print("[aistudio] Opening AI Studio through the tunnel...")
            launch_browser()
            return True, idx
        dead.append(f"{pool[idx]['addr']}:{pool[idx]['port']}")
        stop(quiet=True)

    print("[aistudio] WARNING: every node in the pool is dead at the far end.", file=sys.stderr)
    print(
        "[aistudio] Run `dastyab aistudio verify` to rebuild the pool from fresh server cards.",
        file=sys.stderr,
    )
    return False, None


def _start_android_bridge(server_index, pool_path, idxfile, bridgefile):
    """Android bridge when standalone `xray` ELF isn't installed: verify active proxy/VPN routes."""
    print("[aistudio] Android Python Core: resolving active proxy/VPN & VLESS pool...")
    proxies = _shim.detect_android_proxies()
    routes = [(p["scheme"], p["host"], p["port"], p["label"]) for p in proxies]
    routes.append(("direct", "", 0, "Device Network / Active External VPN (Lantern)"))

    chosen = None
    chosen_ip = None
    for route in routes:
        scheme, host, port, label = route
        _shim.set_active_proxy_override(route)
        try:
            ip = probe_egress(timeout=8)
            r_ai = _shim.android_curl(["-o", "/dev/null", "-w", "%{http_code}", AISTUDIO_URL], timeout=8)
            ai_ok = r_ai.stdout.strip() not in ("", "000", "403", "451")
            print(f"   route={label} -> egress={ip or 'NONE'} aistudio={r_ai.stdout.strip()}")
            if ip and ai_ok:
                chosen = route
                chosen_ip = ip
                break
        finally:
            _shim.set_active_proxy_override(None)

    if chosen is None:
        print(
            "[aistudio] No live proxy/VPN path reached aistudio.google.com.",
            file=sys.stderr,
        )
        print(
            "[aistudio] Turn on Lantern / v2rayNG (:10808/:10809) or DastYab VPN, or install ~/.dastyab/bin/xray.",
            file=sys.stderr,
        )
        return False, None

    scheme, host, port, label = chosen
    with open(bridgefile, "w") as fh:
        json.dump(
            {"scheme": scheme, "host": host, "port": port, "label": label, "egress": chosen_ip},
            fh,
        )
    print(f"[aistudio] Route ready via {label} | Clean egress IP: {chosen_ip}")
    print("[aistudio] Opening AI Studio in DastYab Browser...")
    proxy_port = port if scheme in ("http", "socks5") else None
    launch_browser(proxy_port=proxy_port)
    return True, server_index


def _bring_up(srv, announce=True):
    """Start one core on one pool entry; return the egress IP, or None."""
    _, _, pidfile, cfgpath, _, _ = _paths()
    cfg = build_cfg(srv)
    with open(cfgpath, "w") as fh:
        json.dump(cfg, fh)
    xray = find_xray()
    try:
        proc = subprocess.Popen(
            [xray, "run", "-c", cfgpath],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError as e:
        raise SystemExit(f"[aistudio] Cannot launch {xray}: {e}")
    with open(pidfile, "w") as fh:
        fh.write(str(proc.pid))
    up = False
    for _ in range(25):
        if proc.poll() is not None:
            break
        s = None
        try:
            s = socket.socket()
            s.settimeout(0.5)
            s.connect(("127.0.0.1", SOCK))
            up = True
            break
        except Exception:
            time.sleep(0.3)
        finally:
            if s is not None:
                s.close()
    if not up:
        rc = proc.poll()
        proc.kill()
        try:
            os.remove(pidfile)
        except OSError:
            pass
        detail = (
            f"{xray} exited immediately (code {rc}) — does not accept `run -c <config>`"
            if rc is not None
            else f"{xray} never opened 127.0.0.1:{SOCK}"
        )
        raise SystemExit(
            f"[aistudio] Tunnel failed to start: {detail}.\n"
            f"[aistudio] Set DASTYAB_XRAY=/path/to/xray (a real xray core)."
        )
    ip = probe_egress(timeout=20)
    if ip and announce:
        print(f"[aistudio] Tunnel started via {srv['addr']}:{srv['port']} (pid {proc.pid})")
        print(f"[aistudio] Local SOCKS5 proxy: 127.0.0.1:{SOCK}")
    return ip


def probe_egress(timeout=12):
    """Return current egress IP via tunnel, or None if tunnel dead."""
    if _shim.is_android() or not _has_curl():
        r = _shim.android_curl(
            ["-s", "--max-time", str(timeout), "--socks5-hostname", f"127.0.0.1:{SOCK}", "https://api.ipify.org"],
            timeout=timeout,
            default_socks_port=SOCK,
        )
        out = (r.stdout or "").strip()
        if out and "\n" not in out and len(out) < 60:
            return out
        return None
    try:
        r = subprocess.run(
            ["curl", "-s", "--max-time", str(timeout), "--socks5-hostname", f"127.0.0.1:{SOCK}", "https://api.ipify.org"],
            capture_output=True,
            text=True,
            timeout=timeout + 3,
        )
        out = r.stdout.strip()
        return out if out and not out.startswith("curl") else None
    except Exception:
        return None


def _has_curl():
    for d in os.environ.get("PATH", "").split(os.pathsep):
        p = os.path.join(d, "curl") if d else ""
        if p and os.path.isfile(p) and os.access(p, os.X_OK):
            return True
    return False


def stop(quiet=False):
    _, _, pidfile, _, _, bridgefile = _paths()
    if os.path.exists(pidfile):
        try:
            pid = int(open(pidfile).read())
            os.kill(pid, signal.SIGTERM)
            time.sleep(0.3)
            os.kill(pid, signal.SIGKILL)
        except Exception:
            pass
        try:
            os.remove(pidfile)
        except OSError:
            pass
    if os.path.exists(bridgefile):
        try:
            os.remove(bridgefile)
        except OSError:
            pass
    if not _shim.is_android():
        subprocess.run(
            [
                "bash",
                "-c",
                'for p in $(pgrep -f dastyab_aistudio.json); do '
                '[ "$p" = "${1}" ] || [ "$p" = "$$" ] || kill -9 "$p" 2>/dev/null; done',
                "dastyab-stop",
                str(os.getpid()),
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    if not quiet:
        print("[aistudio] Tunnel stopped")


def launch_browser(proxy_port=None):
    """Open AI Studio pinned to the tunnel (InAppBrowserActivity on Android, Chrome on Linux)."""
    if _shim.is_android():
        _shim.emit_event(
            "OPEN_BROWSER",
            {"title": "Google AI Studio", "url": AISTUDIO_URL, "proxy_port": proxy_port or SOCK},
        )
        print("[aistudio] Requested DastYab Browser for AI Studio.")
        return
    home, _, _, _, _, _ = _paths()
    cands = [os.environ.get("DASTYAB_BROWSER", "")] + CHROME_CANDS
    ch = next((c for c in cands if c and os.path.isfile(c) and os.access(c, os.X_OK)), None)
    if ch is None:
        print("[aistudio] No Chromium-based browser found (Chrome/Chromium/Edge/Brave).")
        print(
            f"[aistudio] Open {AISTUDIO_URL} yourself through socks5://127.0.0.1:{SOCK}."
        )
        return
    tmp = os.path.join(home, ".dastyab-aistudio-profile")
    os.makedirs(tmp, exist_ok=True)
    subprocess.Popen(
        [
            ch,
            "--ozone-platform=x11",
            f"--proxy-server=socks5://127.0.0.1:{SOCK}",
            f"--user-data-dir={tmp}",
            AISTUDIO_URL,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    print("[aistudio] Opened the browser with the tunnel proxy.")


def verify():
    _, pool_path, _, _, _, _ = _paths()
    pool = json.load(open(pool_path)) if os.path.exists(pool_path) else []
    has_xray = _find_xray_optional() is not None

    if not has_xray and _shim.is_android():
        print("[aistudio] Checking Android active routes for AI Studio:")
        proxies = _shim.detect_android_proxies()
        routes = [(p["scheme"], p["host"], p["port"], p["label"]) for p in proxies]
        routes.append(("direct", "", 0, "Device Network / Active External VPN (Lantern)"))
        for route in routes:
            _shim.set_active_proxy_override(route)
            try:
                ip = probe_egress(timeout=6)
                r_ai = _shim.android_curl(["-o", "/dev/null", "-w", "%{http_code}", AISTUDIO_URL], timeout=8)
                code = r_ai.stdout.strip()
                mark = f"OK   egress {ip} (HTTP {code})" if (ip and code not in ("000", "403", "451")) else f"FAIL (egress={ip}, HTTP {code})"
                print(f"   • {route[3]}  {mark}")
            finally:
                _shim.set_active_proxy_override(None)
        if pool:
            print(f"[aistudio] Probing TCP/TLS latency for {len(pool)} stored VLESS server(s):")
            alive_tcp = 0
            for i, s in enumerate(pool[:15]):
                ms = _shim.probe_vless_node_transport(s, timeout=2.5)
                if ms is not None:
                    alive_tcp += 1
                mark = f"OK ({ms}ms)" if ms is not None else "DEAD"
                print(f"   [{i}] {s['addr']}:{s['port']}  {mark}")
            print(f"[aistudio] {alive_tcp}/{min(len(pool), 15)} pool endpoints reachable at transport layer.")
        return

    if not pool:
        print("[aistudio] No verified servers stored yet. Run with a server list to build the pool.")
        return
    print(f"[aistudio] {len(pool)} servers in pool — probing each one now:")
    alive = 0
    if is_running():
        stop(quiet=True)
    for i, s in enumerate(pool):
        ip = _bring_up(s, announce=False)
        stop(quiet=True)
        mark = f"OK   egress {ip}" if ip else "DEAD"
        if ip:
            alive += 1
        print(f"   [{i}] {s['addr']}:{s['port']}  {mark}")
    print(f"[aistudio] {alive}/{len(pool)} reachable now. `start` walks the pool and skips dead entries.")


def parse_vless(line):
    line = line.split("#")[0].strip()
    m = re.match(r"vless://([^@]*)@([^:]+):(\d+)\?(.*)", line)
    if not m:
        return None
    uuid, host, port, qs = m.group(1), m.group(2), int(m.group(3)), m.group(4)
    if not uuid or not host:
        return None
    return {"uuid": uuid, "addr": host, "port": port, "q": dict(urllib.parse.parse_qsl(qs))}


def pop_pool(uris_txt, want=6, probe_port=10930):
    _, pool_path, _, _, _, _ = _paths()
    if not os.path.isfile(uris_txt):
        raise SystemExit(f"[aistudio] No such URI file: {uris_txt}")
    uris = []
    seen = set()
    with open(uris_txt, errors="replace") as fh:
        for line in fh:
            if not line.strip().startswith("vless://"):
                continue
            u = parse_vless(line)
            if not u:
                continue
            key = (u["addr"], u["port"])
            if key in seen:
                continue
            seen.add(key)
            uris.append(u)
    if not uris:
        raise SystemExit(f"[aistudio] No vless:// URIs found in {uris_txt}")

    has_xray = _find_xray_optional() is not None
    working = []
    for u in uris[:30]:
        if has_xray:
            ip = _probe_entry(u, probe_port)
            status = f"OK   egress {ip}" if ip else "DEAD"
            print(f"   {u['addr']}:{u['port']}  {status}", flush=True)
            if ip:
                working.append({**u, "egress": ip})
        else:
            ms = _shim.probe_vless_node_transport(u, timeout=2.5)
            status = f"OK   tls {ms}ms" if ms is not None else "DEAD"
            print(f"   {u['addr']}:{u['port']}  {status}", flush=True)
            if ms is not None:
                working.append({**u, "latency_ms": ms})
        if len(working) >= want:
            break

    if not working:
        raise SystemExit(
            f"[aistudio] None of the supplied URIs are live; keeping existing pool at {pool_path} unchanged."
        )
    os.makedirs(os.path.dirname(pool_path), exist_ok=True)
    tmp = pool_path + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(working, fh, indent=1)
    os.replace(tmp, pool_path)
    print(f"[aistudio] Built pool: {len(working)} working server(s) -> {pool_path}")


def _probe_entry(u, port):
    cfg = build_cfg(u)
    cfg["inbounds"][0]["port"] = port
    cfgpath = os.path.join(_shim.tmp_dir(), f"dastyab_buildpool_{port}.json")
    with open(cfgpath, "w") as fh:
        json.dump(cfg, fh)
    proc = None
    try:
        proc = subprocess.Popen(
            [find_xray(), "run", "-c", cfgpath],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError:
        return None
    try:
        for _ in range(20):
            if proc.poll() is not None:
                return None
            s = None
            try:
                s = socket.socket()
                s.settimeout(0.4)
                s.connect(("127.0.0.1", port))
                break
            except Exception:
                time.sleep(0.3)
            finally:
                if s is not None:
                    s.close()
        else:
            return None
        r = _shim.android_curl(
            ["-s", "--max-time", "12", "--socks5-hostname", f"127.0.0.1:{port}", "https://api.ipify.org"],
            timeout=12,
            default_socks_port=port,
        )
        out = (r.stdout or "").strip()
        return out if out and "\n" not in out and len(out) < 60 else None
    finally:
        if proc is not None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except subprocess.SubprocessError:
                pass


def watch(interval=20, max_fail=2):
    _, pool_path, _, _, idxfile, _ = _paths()
    def load_pool():
        try:
            with open(pool_path) as fh:
                return json.load(fh), None
        except FileNotFoundError:
            return None, "no server pool yet"
        except (json.JSONDecodeError, OSError) as e:
            return None, f"pool unreadable ({e})"

    pool, why = load_pool()
    if not pool and _find_xray_optional() is not None:
        raise SystemExit(f"[aistudio] Cannot watch: {why}. Run: dastyab aistudio verify")
    fail = 0
    idx = 0
    if os.path.exists(idxfile):
        try:
            idx = int(open(idxfile).read())
        except (OSError, ValueError):
            pass
    print(f"[aistudio] Watchdog started: probing every {interval}s")
    while True:
        time.sleep(interval)
        ip = probe_egress()
        if ip:
            fail = 0
            continue
        fail += 1
        if fail < max_fail:
            continue
        print(f"[aistudio] Tunnel down (probe {fail}). Rotating...")
        stop(quiet=True)
        time.sleep(1)
        try:
            start(idx + 1)
            fail = 0
        except SystemExit as e:
            print(f"[aistudio] Rotation attempt failed: {e}", file=sys.stderr)


USAGE = """[aistudio] Usage:
  dastyab aistudio start [pool index]     start the tunnel + open AI Studio
  dastyab aistudio watch [interval secs]  keep the tunnel alive, rotating nodes
  dastyab aistudio verify                 probe every node in the pool now
  dastyab aistudio stop                   stop the tunnel
  dastyab aistudio build-pool <uris.txt>  rebuild the pool from server cards"""


def _want_int(value, default, name):
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        raise SystemExit(f"[aistudio] {name} must be a whole number, got {value!r}.\n{USAGE}")


def main(argv=None):
    args = sys.argv[1:] if argv is None else list(argv)
    cmd = args[0] if args else "start"
    cmd = cmd.strip().lower()
    if cmd in ("start", "on", "up"):
        idx = _want_int(args[1] if len(args) > 1 else None, 0, "Pool index")
        ok, _ = start(idx)
        return 0 if ok else 3
    if cmd == "stop":
        stop()
        return 0
    if cmd in ("verify", "status"):
        verify()
        return 0
    if cmd == "watch":
        interval = _want_int(args[1] if len(args) > 1 else None, 20, "Watch interval")
        watch(interval=interval)
        return 0
    if cmd == "build-pool":
        if len(args) < 2:
            raise SystemExit(f"[aistudio] build-pool needs a file of vless:// cards.\n{USAGE}")
        pop_pool(args[1])
        return 0
    if cmd in ("help", "--help", "-h"):
        print(USAGE)
        return 0
    raise SystemExit(f"[aistudio] Unknown action: {cmd}\n{USAGE}")


if __name__ == "__main__":
    sys.exit(main() or 0)
