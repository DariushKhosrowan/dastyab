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
ACCOUNTS_URL = "https://accounts.google.com/"
AISTUDIO_BLOCKED_COUNTRIES = frozenset({"IR", "CN", "RU", "KP", "BY", "SY", "CU", "SD", "AF", "HK", "MO", "MM", "SS", "XX"})
AISTUDIO_GATE_MARKERS = (
    "your region may not be supported",
    "not available in your country",
    "isn't available in your country",
    "available-regions",
    "unsupported-country",
    "/unsupported",
    "country is not supported",
    "region is not supported",
)
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


def _curl(args, timeout=15):
    if _shim.is_android() or not _has_curl():
        return _shim.android_curl(args, timeout=timeout, default_socks_port=SOCK)
    try:
        return subprocess.run(
            ["curl", "-s", "--max-time", str(timeout), "--socks5-hostname", f"127.0.0.1:{SOCK}"] + args,
            capture_output=True,
            text=True,
            timeout=timeout + 5,
        )
    except (OSError, subprocess.SubprocessError):
        return _shim.android_curl(args, timeout=timeout, default_socks_port=SOCK)


def fetch_egress_geo(timeout=10):
    """Fetch public egress IP + ISO country + hosting/proxy flags through the active route (HTTPS-first)."""
    trace_ip = ""
    trace_cc = ""
    r_tr = _curl(["https://1.1.1.1/cdn-cgi/trace"], timeout=min(timeout, 6))
    if r_tr and r_tr.stdout:
        for line in r_tr.stdout.splitlines():
            if line.startswith("ip="):
                trace_ip = line[3:].strip()
            elif line.startswith("loc="):
                cc = line[4:].strip().upper()
                if len(cc) == 2 and cc != "XX":
                    trace_cc = cc

    providers = [
        "https://ipwho.is/",
        "https://ipapi.co/json/",
        "https://ipinfo.io/json",
    ]
    if not _shim.is_android():
        providers.append("http://ip-api.com/json/?fields=status,query,country,countryCode,proxy,hosting")

    for url in providers:
        r = _curl([url], timeout=min(timeout, 8))
        raw = (r.stdout or "").strip() if r else ""
        if not raw or not raw.startswith("{"):
            continue
        try:
            j = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if j.get("status") == "fail" or j.get("success") is False:
            continue
        ip = (j.get("ip") or j.get("query") or trace_ip or "").strip()
        json_cc = (
            j.get("countryCode")
            or j.get("country_code")
            or (j.get("country") if isinstance(j.get("country"), str) and len(j.get("country")) == 2 else "")
            or trace_cc
        ).strip().upper()
        if len(trace_cc) == 2 and trace_cc != json_cc and trace_cc in AISTUDIO_BLOCKED_COUNTRIES:
            cc = trace_cc
            cname = trace_cc
        else:
            cc = json_cc
            cname = (
                j.get("country_name")
                or (j.get("country") if isinstance(j.get("country"), str) and len(j.get("country")) > 2 else "")
                or cc
            )
        sec = j.get("security") if isinstance(j.get("security"), dict) else {}
        org = str(j.get("org") or "").lower()
        org_hosting = any(
            k in org
            for k in ("datacenter", "hosting", "cloud", "amazon", "digitalocean", "hetzner", "ovh", "linode", "vultr", "cdn77", "m247", "leaseweb")
        )
        hosting = bool(j.get("hosting") or sec.get("hosting") or sec.get("vpn") or sec.get("tor") or org_hosting)
        proxy_flag = bool(j.get("proxy") or sec.get("proxy") or sec.get("vpn") or sec.get("tor"))
        if ip and len(cc) == 2:
            return {
                "ip": ip,
                "country": cc,
                "country_name": cname,
                "proxy": proxy_flag,
                "hosting": hosting,
            }

    if trace_ip and len(trace_cc) == 2:
        return {
            "ip": trace_ip,
            "country": trace_cc,
            "country_name": trace_cc,
            "proxy": False,
            "hosting": False,
        }
    return None


def probe_aistudio_gates(want_country=None, timeout=12):
    """Deep geo-restriction + reachability gate check for Google AI Studio."""
    res = {
        "egress": None,
        "country": None,
        "country_name": None,
        "proxy": False,
        "hosting": False,
        "aistudio_ok": False,
        "accounts_ok": False,
        "unsupported_region": False,
        "http_code": 0,
        "final_url": "",
        "latency_ms": None,
        "verdict": False,
        "reason": "",
    }
    t0 = time.monotonic()
    geo = fetch_egress_geo(timeout=min(timeout, 10))
    if not geo:
        ip = probe_egress(timeout=min(timeout, 8))
        res["egress"] = ip
        res["latency_ms"] = int((time.monotonic() - t0) * 1000)
        res["reason"] = (
            "geo country unverified (cannot confirm non-georestricted exit)"
            if ip
            else "no egress (tunnel/VPN unreachable)"
        )
        return res

    res["egress"] = geo["ip"]
    res["country"] = geo["country"]
    res["country_name"] = geo["country_name"]
    res["proxy"] = bool(geo["proxy"])
    res["hosting"] = bool(geo["hosting"])

    cc = (geo["country"] or "").upper()
    if len(cc) != 2 or cc in AISTUDIO_BLOCKED_COUNTRIES:
        res["unsupported_region"] = True
        res["latency_ms"] = int((time.monotonic() - t0) * 1000)
        res["reason"] = f"GEO-BLOCKED: egress {geo['ip']} is in {cc or '??'} ({geo['country_name']})"
        return res
    if want_country and cc != want_country.upper():
        res["latency_ms"] = int((time.monotonic() - t0) * 1000)
        res["reason"] = f"egress country {cc} != requested {want_country.upper()}"
        return res

    using_shim = _shim.is_android() or not _has_curl()
    r_ai = _curl(["-L", AISTUDIO_URL], timeout=timeout)
    body = (r_ai.stdout or "") if r_ai else ""
    if using_shim:
        code = int(_shim._LAST_HTTP_META.get("code") or 0)
        final_url = _shim.get_last_final_url() or AISTUDIO_URL
    else:
        r_meta = _curl(["-L", "-o", "/dev/null", "-w", "%{http_code}|%{url_effective}", AISTUDIO_URL], timeout=timeout)
        meta_parts = ((r_meta.stdout or "").strip() if r_meta else "").split("|", 1)
        code = int(meta_parts[0]) if meta_parts and meta_parts[0].isdigit() else 0
        final_url = meta_parts[1] if len(meta_parts) > 1 else AISTUDIO_URL

    res["http_code"] = code
    res["final_url"] = final_url
    final_low = final_url.lower()
    body_low = body.lower()

    geo_hit = (
        code in (403, 451)
        or "/unsupported" in final_low
        or "available-regions" in final_low
        or "country-not-supported" in final_low
        or "unsupported-country" in final_low
        or "/sorry/" in final_low
        or next((m for m in AISTUDIO_GATE_MARKERS if m in body_low), None)
    )
    if geo_hit:
        res["unsupported_region"] = True
        res["latency_ms"] = int((time.monotonic() - t0) * 1000)
        res["reason"] = f"REGION-GATE: AI Studio geo-restricted on {cc} ({geo['ip']}, HTTP {code}, {final_url})"
        return res

    if code == 0 or code >= 500 or not body.strip():
        res["latency_ms"] = int((time.monotonic() - t0) * 1000)
        res["reason"] = f"aistudio.google.com unreachable (HTTP {code})"
        return res

    # Secondary gate: verify generativelanguage API doesn't report location unsupported
    # and gemini.google.com doesn't redirect to /unsupported
    api_url = "https://generativelanguage.googleapis.com/v1beta/models"
    r_api = _curl([api_url], timeout=min(timeout, 8))
    api_body = (r_api.stdout or "").lower() if r_api else ""
    api_markers = (
        "location is not supported",
        "not available in your country",
        "not available in your region",
        "region is not supported",
        "country is not supported",
        "user location",
    )
    if any(m in api_body for m in api_markers):
        res["unsupported_region"] = True
        res["latency_ms"] = int((time.monotonic() - t0) * 1000)
        res["reason"] = f"REGION-GATE: generativelanguage API rejected location on {cc} ({geo['ip']})"
        return res

    gemini_url = "https://gemini.google.com/"
    r_gem = _curl(["-L", gemini_url], timeout=min(timeout, 8))
    gem_body = (r_gem.stdout or "") if r_gem else ""
    if using_shim:
        gem_code = int(_shim._LAST_HTTP_META.get("code") or 0)
        gem_final = (_shim.get_last_final_url() or gemini_url).lower()
    else:
        gem_code = 0
        gem_final = gemini_url
    gem_low = gem_body.lower()
    if (
        gem_code in (403, 451)
        or "/unsupported" in gem_final
        or "available-regions" in gem_final
        or "/sorry/" in gem_final
        or any(m in gem_low for m in AISTUDIO_GATE_MARKERS)
    ):
        res["unsupported_region"] = True
        res["latency_ms"] = int((time.monotonic() - t0) * 1000)
        res["reason"] = f"REGION-GATE: gemini.google.com geo-restricted on {cc} ({geo['ip']}, HTTP {gem_code})"
        return res

    res["aistudio_ok"] = True

    r_acc = _curl(["-o", "/dev/null", "-w", "%{http_code}", ACCOUNTS_URL], timeout=min(timeout, 10))
    acc_code = (r_acc.stdout or "").strip() if r_acc else "000"
    res["accounts_ok"] = acc_code not in ("", "000")
    res["latency_ms"] = int((time.monotonic() - t0) * 1000)
    if not res["accounts_ok"]:
        res["reason"] = f"accounts.google.com unreachable (HTTP {acc_code}; sign-in would fail)"
        return res

    res["verdict"] = True
    flags = []
    if res["proxy"]:
        flags.append("proxy-flagged")
    if res["hosting"]:
        flags.append("hosting")
    flag_str = f" [{', '.join(flags)}]" if flags else ""
    res["reason"] = f"egress {geo['ip']} in {cc} ({geo['country_name']}); AI Studio HTTP {code} + accounts OK{flag_str}"
    return res


def gate_score(g):
    return (
        0 if g.get("verdict") else 1,
        0 if not g.get("proxy") else 1,
        0 if not g.get("hosting") else 1,
        g.get("latency_ms") or 10_000,
    )


def start(server_index=0):
    _, pool_path, _, _, idxfile, bridgefile = _paths()
    if is_running():
        print(f"[aistudio] Tunnel already running on 127.0.0.1:{SOCK}")
        print("[aistudio] Opening AI Studio through the existing tunnel...")
        proxy_port = None
        if _shim.is_android() and os.path.exists(bridgefile):
            try:
                b = json.load(open(bridgefile))
                p = b.get("port")
                if isinstance(p, int) and p > 0:
                    proxy_port = p
            except Exception:
                pass
        launch_browser(proxy_port=proxy_port)
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


def _route_to_chain_hops(route):
    scheme, host, port, _ = route
    if scheme == "chain" and isinstance(host, list):
        return [
            {
                "scheme": h[0],
                "host": h[1],
                "port": int(h[2]),
                "username": h[3] if len(h) > 3 else "",
                "password": h[4] if len(h) > 4 else "",
            }
            for h in host
        ]
    if scheme in ("http", "socks5") and host and int(port or 0) > 0:
        return [{"scheme": scheme, "host": host, "port": int(port), "username": "", "password": ""}]
    return []


def _start_android_bridge(server_index, pool_path, idxfile, bridgefile):
    """Android bridge: verify external Lantern VPN and progressively auto-chain 1/2/3/4 proxy layers until AI Studio is non-georestricted."""
    print("[aistudio] Android Python Core: deep geo-gate probing & progressive multi-layer route chaining...")
    results = _shim.discover_best_android_route(
        probe_aistudio_gates, target_service="aistudio", max_candidates=36
    )
    best = None
    for route, g in results:
        scheme, host, port, label = route
        status_s = f"PASS {g['country']} {g['egress']} ({g['latency_ms']}ms)" if g["verdict"] else f"FAIL: {g['reason']}"
        print(f"   • {label} -> {status_s}", flush=True)
        if g["verdict"] and (best is None or gate_score(g) < gate_score(best[1])):
            best = (route, g)

    if best is None:
        print(
            "[aistudio] No route passed AI Studio's non-georestricted gate checks (country + HTTPS redirect + DOM + accounts).",
            file=sys.stderr,
        )
        print(
            "[aistudio] Connect a clean residential/v2rayNG/harvested proxy hop on top of Lantern, or switch Lantern region.",
            file=sys.stderr,
        )
        return False, None

    route, g = best
    scheme, host, port, label = route
    chain_hops = _route_to_chain_hops(route)
    with open(bridgefile, "w") as fh:
        json.dump(
            {
                "scheme": scheme,
                "host": host if scheme != "chain" else "",
                "port": port,
                "hops": len(chain_hops),
                "chain": chain_hops,
                "label": label,
                "egress": g["egress"],
                "country": g["country"],
            },
            fh,
        )
    _shim.emit_event(
        "SYNC_SERVICE_CHAIN",
        {
            "target_service": "aistudio",
            "chain": chain_hops,
            "label": label,
            "egress": g["egress"],
            "country": g["country"],
        },
    )
    print(f"[aistudio] Unlocked via {label} | {g['reason']}")
    print("[aistudio] Opening AI Studio in DastYab Browser...")
    proxy_port = port if scheme in ("http", "socks5") and port > 0 else None
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
        print("[aistudio] Checking Android Layer 0 (Lantern) & progressive multi-layer routes for AI Studio:")
        results = _shim.discover_best_android_route(
            probe_aistudio_gates, target_service="aistudio", max_candidates=36
        )
        passed_count = 0
        best = None
        for route, g in results:
            if g["verdict"]:
                passed_count += 1
                mark = f"OK   {g['country']} {g['egress']} (HTTP {g['http_code']}, {g['latency_ms']}ms)"
                if best is None or gate_score(g) < gate_score(best[1]):
                    best = (route, g)
            else:
                mark = f"FAIL ({g['reason']})"
            print(f"   • {route[3]}  {mark}", flush=True)
        if best is not None:
            b_route, b_g = best
            _shim.emit_event(
                "SYNC_SERVICE_CHAIN",
                {
                    "target_service": "aistudio",
                    "chain": _route_to_chain_hops(b_route),
                    "label": b_route[3],
                    "egress": b_g["egress"],
                    "country": b_g["country"],
                },
            )
        print(f"[aistudio] {passed_count}/{len(results)} route(s) passed AI Studio non-georestricted gates.")
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
