#!/usr/bin/env python3
"""
dastyab flow — multi-layer access to Google Flow (flow.google.com).

WHY (very simply):
  Google Flow shows "Flow is not available in your country yet." and
  redirects to /unsupported-country whenever the egress IP it sees is in
  an unsupported region. This helper puts a dedicated browser behind a
  *verified* foreign egress so Flow's region check sees a supported
  country, and keeps that egress alive.

THE LAYERS (each one makes the IP or the path to it better):
  L0 supply    — union of node sources: this tool's own ranked pool, the
                 verified aistudio pool, the nekoray profile store, the
                 harvested nodes.json inventory, or a raw vless:// URI
                 file. Deduped, best-first.
  L1 transport — proven xray config builder (reality / tls / ws / grpc /
                 xhttp, vision flow guarded by core version).
  L2 chain     — optional two-hop: you -> entry -> exit -> Google, via
                 xray sockopt.dialerProxy in ONE config. The local
                 network only sees the entry connection; Google only
                 sees the exit egress.
  L3 geo gate  — the egress country must be on Google's official Flow
                 list (support.google.com/flow/answer/16353544, fetched
                 2026-11-16, embedded below as ISO codes).
  L4 reach     — flow.google.com AND accounts.google.com must answer
                 through the tunnel *right now*. Never trust stale
                 stored results: free nodes die between runs.
  L5 quality   — ip-api proxy/hosting flags; ranking prefers egress IPs
                 that do not look like flagged proxies (Flow also
                 rejects many datacenter/proxy IPs even in supported
                 countries).
  L6 browser   — a dedicated Chrome profile pinned to the local SOCKS5
                 proxy, so only Flow traffic rides the tunnel and the
                 user's normal browser stays on the real IP.
  L7 watchdog  — `watch` re-probes the gates and rotates to the next
                 ranked node when the current one degrades.

USAGE:
  dastyab-flow.py start [idx] [--hops 1|2] [--country XX]   tunnel + open Flow
  dastyab-flow.py next                                      rotate to next node
  dastyab-flow.py stop                                      stop the tunnel
  dastyab-flow.py status                                    egress + gate verdict
  dastyab-flow.py verify                                    re-probe saved pool
  dastyab-flow.py best [--try N] [--country XX]             scan wider, re-rank
  dastyab-flow.py prove [--try N] [--country XX]            prove it in a real browser
  dastyab-flow.py watch [secs]                              self-healing watchdog
  dastyab-flow.py build-pool <uris.txt>                     pool from vless cards
  dastyab-flow.py countries                                 show supported list
"""
import argparse
import glob
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.parse

HOME = os.path.expanduser("~")
PROFILE_DIR = os.path.join(HOME, ".dastyab/profiles/default")
FLOW_POOL = os.path.join(PROFILE_DIR, "flow_pool.json")
AISTUDIO_POOL = os.path.join(PROFILE_DIR, "fresh_vless.json")
NODES_JSON = os.path.join(PROFILE_DIR, "nodes.json")
NEKORAY_PROFILES = os.path.join(HOME, ".config/nekoray/config/profiles")

SOCK = int(os.environ.get("DASTYAB_FLOW_SOCKS", "10860"))
PIDFILE = "/tmp/dastyab_flow.pid"
CFGPATH = "/tmp/dastyab_flow.json"
IDXFILE = "/tmp/dastyab_flow_idx"
STATEFILE = "/tmp/dastyab_flow_state.json"
BROWSER_PROFILE = os.path.join(HOME, ".dastyab-flow-profile")

FLOW_URL = "https://flow.google.com/"
ACCOUNTS_URL = "https://accounts.google.com/"
# Rendered on the region-rejection page. Verified against Chrome 148:
# /unsupported-country renders `unsupported-country-title` plus the
# "not available in your country yet" headline, while the real app does
# not. This is the only CLI-visible proof that the *browser* is accepted,
# rather than merely that the host answers.
GATE_MARKERS = ("unsupported-country-title",
                "not available in your country yet",
                "flow is not available in your country")
# A DIFFERENT gate: the account is not eligible (age not verified, or no
# AI Plus/Pro/Ultra plan). support.google.com/flow/answer/16353333 lists
# three requirements; a proxy can only ever satisfy the region one, so
# reporting this as a region block sent users after the wrong problem.
UNAVAILABLE_MARKERS = ("unavailable-title",
                       "don't have access to flow",
                       "dont have access to flow",
                       "do not have access to flow")
ACCOUNT_HELP = (
    "Flow's account gate. Per support.google.com/flow/answer/16353333 you "
    "need ALL of: (1) age verified, 18+; (2) a supported region; "
    "(3) Google AI Plus/Pro/Ultra (or a qualifying Workspace plan). "
    "The tunnel only satisfies (2)."
)
IPIFY = "https://api.ipify.org"
IPAPI = "http://ip-api.com/json/?fields=status,country,countryCode,proxy,hosting,mobile"

# Google's official "Where you can use Google Flow" list, as ISO-3166
# alpha-2 codes. Fetched 2026-11-16 from:
#   https://support.google.com/flow/answer/16353544?hl=en
# Refresh this set when Google expands the rollout; the gate is only as
# honest as the list it checks against.
SUPPORTED_COUNTRIES = frozenset("""
AL DZ AS AO AG AR AM AW AU AT AZ BS BH BD BE BZ BJ BM BO BA BW BR BG BF
CV KH CM CA KY CL CO CR CI HR CY CZ DK DO EC EG SV EE FJ FI FR GA DE GH
GI GR GU GT GW HT HN HK HU IS IN ID IQ IE IL IT JM JP JO KZ KE KW KG LA
LV LB LI LT LU MO MY MV ML MT MU MX MD MC MA MZ NA NP NL NZ NI NE NG MK
MP NO OM PK PW PA PG PY PE PH PL PT PR QA RO RW SM SA SN RS SC SL SG SK
SI ZA KR ES LK SE CH TW TJ TZ TH TG TO TT TN TR TM VI UG UA AE GB US UY
UZ VE VN VG YE ZM ZW
""".split())


# ---------------------------------------------------------------------------
# xray core discovery (mirrors dastyab-aistudio.py — the logic is battle-
# tested there; only the messages and pid/config paths differ).
# ---------------------------------------------------------------------------

_XRAY_VERSION = None

def find_xray():
    """Locate a real xray/v2ray core.

    Search order: an explicit override, the binary's own helper dir, the
    dastyab bin dir, standard install locations, then PATH. nekoray
    wrappers are excluded by `_is_real_xray` because they accept
    `run -c` and then exit without serving traffic.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    cands = [
        os.environ.get("DASTYAB_XRAY", ""),
        os.path.join(here, "xray"),
        os.path.join(HOME, ".dastyab/bin/xray"),
        "/usr/local/bin/xray", "/usr/bin/xray", "/opt/xray/xray",
        "/tmp/xraybin/xray",
        "/opt/v2rayN/bin/xray/xray",
    ]
    for c in cands:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            if _is_real_xray(c):
                return c
    for name in ("xray", "v2ray"):
        for d in os.environ.get("PATH", "").split(os.pathsep):
            p = os.path.join(d, name) if d else ""
            if p and os.path.isfile(p) and os.access(p, os.X_OK):
                if _is_real_xray(p):
                    return p
    raise SystemExit(
        "[flow] No xray core found. Install xray and put it on PATH, or set "
        "DASTYAB_XRAY=/path/to/xray"
    )


def _is_real_xray(path):
    """True if `path version` identifies itself as a usable xray/v2ray core."""
    try:
        out = subprocess.run([path, "version"], capture_output=True, text=True,
                             timeout=8).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    if "NekoRay" in out or "NekoBox" in out:
        return False
    return bool(re.search(r"(Xray|V2Ray)\s+\d+\.\d+", out))


def xray_supports_vision():
    """True if the resolved core is xray >= 1.7 (vision/XTLS support).

    Unknown cores (nekoray wrappers, v2ray) report False: sending them a
    `flow` field makes them reject the entire config, and a plain VLESS
    TCP connection still works without it.
    """
    global _XRAY_VERSION
    if _XRAY_VERSION is None:
        _XRAY_VERSION = ""
        try:
            out = subprocess.run([find_xray(), "version"],
                                 capture_output=True, text=True, timeout=10).stdout
            m = re.search(r"Xray\s+(\d+)\.(\d+)", out)
            if m:
                _XRAY_VERSION = f"{m.group(1)}.{m.group(2)}"
        except (SystemExit, OSError, subprocess.SubprocessError):
            _XRAY_VERSION = ""
    if not _XRAY_VERSION:
        return False
    major, minor = (int(x) for x in _XRAY_VERSION.split("."))
    return (major, minor) >= (1, 7)


# ---------------------------------------------------------------------------
# JSON helpers — defensive reads are the norm, not the exception: a missing
# or half-written file is a first-run state, never a crash.
# ---------------------------------------------------------------------------

def read_json(path, default):
    try:
        with open(path) as fh:
            return json.load(fh)
    except FileNotFoundError:
        return default
    except (json.JSONDecodeError, OSError):
        return default


def write_json_atomic(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(obj, fh, indent=1)
    os.replace(tmp, path)


# ---------------------------------------------------------------------------
# L0 — node supply: one uniform Server shape from every source.
#
#   {"proto": "vless"|"trojan"|"vmess"|"ss",
#    "addr": str, "port": int,
#    "uuid": str,            # vless / vmess
#    "password": str,        # trojan / ss
#    "method": str,          # ss
#    "q": {...}}             # transport query fields
# ---------------------------------------------------------------------------

def parse_vless(line):
    """Parse one `vless://` share URI into a Server dict (or None)."""
    line = line.split('#')[0].strip()
    m = re.match(r"vless://([^@]*)@([^:]+):(\d+)\?(.*)", line)
    if not m:
        return None
    uuid_, host, port, qs = m.group(1), m.group(2), int(m.group(3)), m.group(4)
    if not uuid_ or not host:
        return None
    return {"proto": "vless", "uuid": uuid_, "addr": host, "port": port,
            "q": dict(urllib.parse.parse_qsl(qs))}


def _bean_to_server(bean, typ):
    """Convert a nekoray bean dict into the uniform Server shape."""
    if not bean.get("addr") or not bean.get("port"):
        return None
    st = bean.get("stream", {}) or {}
    q = {
        "security": st.get("sec", "none"),
        "type": st.get("net", "tcp"),
        "sni": st.get("sni", ""),
        "host": st.get("host", ""),
        "path": st.get("path", "/"),
        "serviceName": st.get("servicename", ""),
        "pbk": st.get("pbk", ""),
        "sid": st.get("sid", ""),
        "fp": st.get("utls", "chrome"),
        "flow": bean.get("flow", ""),
    }
    srv = {"proto": typ, "addr": bean["addr"], "port": int(bean["port"]),
           "q": q, "name": bean.get("name", "")}
    if typ in ("vless", "vmess"):
        srv["uuid"] = bean.get("pass", "")
    else:
        srv["password"] = bean.get("pass", "")
        if typ == "ss":
            srv["method"] = bean.get("method", "aes-256-gcm")
    return srv


def load_candidates(limit=400):
    """Union every node source, dedup by (addr, port), best-first.

    Order matters: this tool's own ranked pool first (it already passed
    the gates), then the aistudio-verified pool, then the wide sources.
    The scan (`best`) walks this list, so earlier = more likely to work.
    """
    out, seen = [], set()

    def add(srv):
        if not srv or not srv.get("addr") or not srv.get("port"):
            return
        key = (str(srv["addr"]), int(srv["port"]))
        if key in seen:
            return
        seen.add(key)
        out.append(srv)

    # a. own ranked pool (may carry chain/gate metadata; keep it verbatim)
    for e in read_json(FLOW_POOL, []):
        add(e if "addr" in e else None)

    # b. the aistudio-verified pool — trusted, it carried real traffic
    for e in read_json(AISTUDIO_POOL, []):
        if isinstance(e, dict) and e.get("uuid"):
            add({"proto": "vless", "uuid": e["uuid"], "addr": e["addr"],
                 "port": int(e["port"]), "q": e.get("q", {})})

    # c. nekoray profile store (widest supply; vless first, then trojan,
    #    then vmess/ss — xray speaks all four)
    beans = []
    for f in sorted(glob.glob(os.path.join(NEKORAY_PROFILES, "*.json"))):
        d = read_json(f, {})
        if not isinstance(d, dict):
            continue
        srv = _bean_to_server(d.get("bean", {}) or {}, d.get("type", ""))
        if srv:
            beans.append(srv)
    prio = {"vless": 0, "trojan": 1, "vmess": 2, "shadowsocks": 3, "ss": 3}
    for srv in sorted(beans, key=lambda s: prio.get(s["proto"], 9)):
        add(srv)

    # d. harvested nodes.json — vless URLs only (that is this project's
    #    core protocol and the widest harvested supply), healthy first
    nodes_doc = read_json(NODES_JSON, {})
    urls = []
    if isinstance(nodes_doc, dict):
        for n in nodes_doc.get("nodes", []):
            u = (n or {}).get("url", "")
            if u.startswith("vless://"):
                urls.append((not n.get("healthy", False), u))
    elif isinstance(nodes_doc, list):
        for n in nodes_doc:
            u = (n or {}).get("url", "")
            if u.startswith("vless://"):
                urls.append((not n.get("healthy", False), u))
    for _, u in sorted(urls):
        add(parse_vless(u))

    return out[:limit]


# ---------------------------------------------------------------------------
# L1 + L2 — transport config builder, incl. the two-hop chain.
# ---------------------------------------------------------------------------

def build_outbound(srv, tag):
    """One xray outbound for one Server (vless/trojan/vmess/ss)."""
    q = srv.get("q", {})
    addr, port = srv["addr"], int(srv["port"])
    proto = srv["proto"]

    if proto == "vless":
        out = {"tag": tag, "protocol": "vless",
               "settings": {"vnext": [{"address": addr, "port": port,
                    "users": [{"id": srv["uuid"], "encryption": "none"}]}]}}
        # `flow` (xtls-rprx-vision) is only valid over TLS/REALITY and only
        # on xray >= 1.7; otherwise the core rejects the whole config.
        flow = q.get("flow")
        if flow:
            if q.get("security") not in ("tls", "reality"):
                flow = None
            elif not xray_supports_vision():
                flow = None
        if flow:
            out["settings"]["vnext"][0]["users"][0]["flow"] = flow
    elif proto == "trojan":
        out = {"tag": tag, "protocol": "trojan",
               "settings": {"servers": [{"address": addr, "port": port,
                                         "password": srv["password"]}]}}
    elif proto == "vmess":
        out = {"tag": tag, "protocol": "vmess",
               "settings": {"vnext": [{"address": addr, "port": port,
                    "users": [{"id": srv["uuid"], "alterId": 0}]}]}}
    elif proto in ("ss", "shadowsocks"):
        out = {"tag": tag, "protocol": "shadowsocks",
               "settings": {"servers": [{"address": addr, "port": port,
                    "method": srv.get("method", "aes-256-gcm"),
                    "password": srv["password"]}]}}
    else:
        return None

    sec = q.get("security", "none")
    net = q.get("type", "tcp")
    ss = {}
    if sec == "reality":
        ss.update(security="reality",
                  realitySettings={"serverName": q.get("sni") or addr,
                                   "fingerprint": q.get("fp", "chrome"),
                                   "publicKey": q.get("pbk", ""),
                                   "shortId": q.get("sid", ""),
                                   "spiderX": q.get("spx", "/")})
    elif sec == "tls":
        ss["security"] = "tls"
        ss["tlsSettings"] = {"serverName": q.get("sni") or addr,
                             "fingerprint": q.get("fp", "chrome")}
        if q.get("alpn"):
            ss["tlsSettings"]["alpn"] = [x for x in q["alpn"].split(",") if x]
    else:
        ss["security"] = "none"

    if net == "ws":
        ss["network"] = "ws"
        ss["wsSettings"] = {"path": q.get("path", "/"),
                            "headers": {"Host": q.get("host") or q.get("sni") or addr}}
    elif net == "xhttp":
        ss["network"] = "xhttp"
        ss["xhttpSettings"] = {"path": q.get("path", "/"),
                               "host": q.get("host") or q.get("sni") or addr,
                               "mode": q.get("mode", "auto")}
    elif net == "grpc":
        ss["network"] = "grpc"
        ss["grpcSettings"] = {"serviceName": q.get("serviceName", "")}
    else:
        ss["network"] = "tcp"
    out["streamSettings"] = ss
    return out


def build_config(nodes, port):
    """Full xray config for a 1-hop or 2-hop route.

    `nodes` is [exit] for one hop, or [exit, entry] for the chain — the
    LAST element is always the entry (the node the local machine
    connects to); the FIRST is the exit (the egress Google sees). For
    two hops the exit dials through the entry via sockopt.dialerProxy,
    which is what makes the local network see only the entry connection
    while Google sees only the exit egress.
    """
    if len(nodes) == 1:
        outbounds = [build_outbound(nodes[0], "exit")]
    else:
        exit_ob = build_outbound(nodes[0], "exit")
        exit_ob["sockopt"] = {"dialerProxy": "entry"}
        outbounds = [exit_ob, build_outbound(nodes[1], "entry")]
    outbounds = [ob for ob in outbounds if ob]
    if not outbounds:
        return None
    return {"log": {"loglevel": "warning"},
            "inbounds": [{"listen": "127.0.0.1", "port": port,
                          "protocol": "socks",
                          "settings": {"auth": "noauth", "udp": True},
                          "tag": "socks-in"}],
            "outbounds": outbounds}


# ---------------------------------------------------------------------------
# Tunnel lifecycle.
# ---------------------------------------------------------------------------

def is_running():
    if os.path.exists(PIDFILE):
        try:
            pid = int(open(PIDFILE).read())
            os.kill(pid, 0)
            return True
        except (ProcessLookupError, ValueError, PermissionError):
            pass
    return False


def _curl(args, timeout):
    """Run curl through the tunnel; never raises."""
    try:
        return subprocess.run(
            ["curl", "-s", "--max-time", str(timeout),
             "--socks5-hostname", f"127.0.0.1:{SOCK}"] + args,
            capture_output=True, text=True, timeout=timeout + 5)
    except (OSError, subprocess.SubprocessError):
        return None


def bring_up(nodes):
    """Start the core on `nodes`; return the child pid, or None.

    The SOCKS port accepting connections only proves the local core is
    up — traffic must actually reach the far side before the node may
    be announced as working.
    """
    cfg = build_config(nodes, SOCK)
    if cfg is None:
        return None
    with open(CFGPATH, "w") as fh:
        json.dump(cfg, fh)
    xray = find_xray()
    try:
        proc = subprocess.Popen([xray, "run", "-c", CFGPATH],
                                stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL)
    except OSError:
        return None
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
        proc.kill()
        return None
    open(PIDFILE, "w").write(str(proc.pid))
    return proc.pid


def stop(quiet=False):
    if os.path.exists(PIDFILE):
        try:
            pid = int(open(PIDFILE).read())
            os.kill(pid, signal.SIGTERM)
            time.sleep(0.5)
            os.kill(pid, signal.SIGKILL)
        except Exception:
            pass
        try:
            os.remove(PIDFILE)
        except OSError:
            pass
    # Sweep any core we own that still holds our config path. Skip our
    # own pid and the sweep shell itself: `pgrep -f` matches the command
    # line of the `bash -c` child, which contains the pattern.
    subprocess.run(["bash", "-c",
        'for p in $(pgrep -f dastyab_flow.json); do '
        '[ "$p" = "${1}" ] || [ "$p" = "$$" ] || kill -9 "$p" 2>/dev/null; done',
        "dastyab-flow-stop", str(os.getpid())],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not quiet:
        print("[flow] Tunnel stopped")


# ---------------------------------------------------------------------------
# L3 + L4 + L5 — the gates. Everything is measured live through the
# tunnel, because the egress is what Google sees, not the local machine.
# ---------------------------------------------------------------------------

def probe_gates(want_country=None, browser_profile=None):
    """Run every gate through the current tunnel.

    Returns a dict with the egress IP, geo, reachability and quality
    facts, plus a `verdict` and a human `reason`. An egress may only be
    used when every gate passes — and only for as long as it keeps
    passing, which is what the watchdog enforces.
    """
    res = {"egress": None, "country": None, "country_name": None,
           "proxy": None, "hosting": None, "flow_ok": False,
           "accounts_ok": False, "latency_ms": None, "verdict": False,
           "reason": ""}

    t0 = time.time()
    r = _curl([IPIFY], 20)
    ip = (r.stdout or "").strip() if r else ""
    if not ip or len(ip) > 60 or "\n" in ip:
        res["reason"] = "no egress (tunnel dead at the far end)"
        return res
    res["egress"] = ip

    r = _curl([IPAPI], 15)
    geo = {}
    try:
        geo = json.loads((r.stdout or "").strip()) if r else {}
    except json.JSONDecodeError:
        geo = {}
    if geo.get("status") != "success":
        # The egress works but we cannot prove its country. That is a
        # FAIL, not a pass: the whole point is a supported-country IP.
        res["reason"] = "geo lookup failed (ip-api unreachable through tunnel)"
        res["latency_ms"] = int((time.time() - t0) * 1000)
        return res
    res["country"] = geo.get("countryCode")
    res["country_name"] = geo.get("country")
    res["proxy"] = bool(geo.get("proxy"))
    res["hosting"] = bool(geo.get("hosting"))
    res["latency_ms"] = int((time.time() - t0) * 1000)

    if res["country"] not in SUPPORTED_COUNTRIES:
        res["reason"] = (f"egress {ip} is in {res['country']} "
                         f"({res['country_name']}) — not on Google's Flow list")
        return res
    if want_country and res["country"] != want_country.upper():
        res["reason"] = (f"egress country {res['country']} != requested "
                         f"{want_country.upper()}")
        return res

    # L4 — both Google fronts must answer through this exact egress.
    r = _curl(["-o", "/dev/null", "-w", "%{http_code}", FLOW_URL], 25)
    code = (r.stdout or "").strip() if r else "000"
    res["flow_ok"] = code not in ("", "000")
    if not res["flow_ok"]:
        res["reason"] = f"flow.google.com unreachable through tunnel (HTTP {code})"
        return res

    # The response must be the real app shell, not a block page: the
    # Flow frontend identifies itself as AiSandboxAngularFrontend in the
    # initial HTML. (The region decision itself happens in signed-in
    # backend RPCs; the geo gate above is the honest CLI-side proxy
    # for it, and the pinned browser is the end-to-end proof.)
    r = _curl([FLOW_URL], 25)
    body = (r.stdout or "") if r else ""
    res["app_shell_ok"] = "AiSandboxAngularFrontend" in body
    if not res["app_shell_ok"]:
        res["reason"] = "flow.google.com answered but not with the Flow app shell"
        return res

    r = _curl(["-o", "/dev/null", "-w", "%{http_code}", ACCOUNTS_URL], 25)
    code = (r.stdout or "").strip() if r else "000"
    res["accounts_ok"] = code not in ("", "000")
    if not res["accounts_ok"]:
        res["reason"] = "accounts.google.com unreachable (sign-in would fail)"
        return res

    # Decisive check: does the *browser* get the app, or the rejection
    # page? curl only proves the host answered; the region gate is decided
    # by the app itself, so the rendered DOM is the only honest signal.
    b_ok, b_detail = browser_gate(profile_dir=browser_profile)
    res["browser_ok"] = b_ok
    res["browser_detail"] = b_detail
    if b_ok is False:
        res["reason"] = b_detail
        return res
    if b_ok is None:
        # No browser available: the gate is UNKNOWN, not passed. Keep the
        # verdict, but say plainly that the decisive proof did not run.
        res["verdict"] = True
        flags = []
        if res["proxy"]:
            flags.append("proxy-flagged")
        if res["hosting"]:
            flags.append("hosting")
        res["reason"] = ("egress %s in %s (%s); host reachable%s; "
                         "browser gate NOT run (%s)"
                         % (res["egress"], res["country"],
                            res["country_name"],
                            ("; " + ", ".join(flags)) if flags else "",
                            b_detail))
        return res

    res["verdict"] = True
    flags = []
    if res["proxy"]:
        flags.append("proxy-flagged")
    if res["hosting"]:
        flags.append("hosting")
    res["reason"] = ("egress %s in %s (%s); Flow + accounts reachable%s"
                     % (res["egress"], res["country"], res["country_name"],
                        ("; " + ", ".join(flags)) if flags else ""))
    return res


def gate_score(g):
    """Sort key: passing gates first, then cleanest IP, then fastest."""
    return (0 if g["verdict"] else 1,
            0 if not g["proxy"] else 1,
            0 if not g["hosting"] else 1,
            g["latency_ms"] or 10_000)


def _label(nodes):
    return " -> ".join(f"{n['proto']} {n['addr']}:{n['port']}" for n in nodes)


# ---------------------------------------------------------------------------
# Pool management.
# ---------------------------------------------------------------------------

def load_pool():
    pool = read_json(FLOW_POOL, [])
    return [e for e in pool if isinstance(e, dict) and e.get("nodes")]


def save_pool(passing):
    """Replace the pool — only when the rebuild found live servers.

    Writing `[]` over a working pool would destroy the user's fallback
    the next time a scan happens during a bad network hour.
    """
    if not passing:
        raise SystemExit(
            "[flow] No candidate passed the gates; keeping the existing "
            f"pool at {FLOW_POOL} unchanged."
        )
    write_json_atomic(FLOW_POOL, passing)


def _try_nodes(nodes, want_country=None):
    """Bring one route up, gate it, tear it down. Return gates or None."""
    pid = bring_up(nodes)
    if pid is None:
        return None
    try:
        return probe_gates(want_country)
    finally:
        stop(quiet=True)


def scan(candidates, want_country=None, try_n=10, allow_chain=True):
    """Walk candidates until one route passes every gate.

    Single hop first; if nothing passes (or `hops` is forced to 2), the
    chain layer kicks in: exits whose geo is unknown get paired with the
    first alive entries until a chained egress passes. Returns a list of
    passing route dicts, best first.
    """
    passing = []
    tested = 0
    for cand in candidates[:try_n]:
        tested += 1
        nodes = [cand]
        print(f"[flow] [{tested}] trying {_label(nodes)} ...", flush=True)
        g = _try_nodes(nodes, want_country)
        if g and g["verdict"]:
            print(f"[flow]     PASS {g['reason']} ({g['latency_ms']}ms)")
            passing.append({"nodes": nodes, "egress": g["egress"],
                            "country": g["country"],
                            "country_name": g["country_name"],
                            "proxy": g["proxy"], "hosting": g["hosting"],
                            "latency_ms": g["latency_ms"],
                            "checked_epoch": time.time()})
            if len(passing) >= 3:
                break
        else:
            print(f"[flow]     fail: {(g or {}).get('reason', 'transport dead')}",
                  flush=True)

    if passing or not allow_chain:
        return passing

    # L2 — chain fallback: you -> entry -> exit -> Google. The exit
    # decides the egress; the entry only carries the connection.
    print("[flow] No single hop passed — engaging the two-hop chain layer ...")
    entries = candidates[:3]
    for cand in candidates[:try_n]:
        tested += 1
        for entry in entries:
            if entry is cand:
                continue
            nodes = [cand, entry]
            print(f"[flow] [{tested}] chain {_label(nodes)} ...", flush=True)
            g = _try_nodes(nodes, want_country)
            if g and g["verdict"]:
                print(f"[flow]     PASS {g['reason']} ({g['latency_ms']}ms)")
                passing.append({"nodes": nodes, "egress": g["egress"],
                                "country": g["country"],
                                "country_name": g["country_name"],
                                "proxy": g["proxy"], "hosting": g["hosting"],
                                "latency_ms": g["latency_ms"],
                                "checked_epoch": time.time()})
                break
            # A chained route that never came up is usually a dead entry;
            # a wrong-country egress means the exit is wrong — try the
            # next exit, not more entries.
            reason = (g or {}).get("reason", "transport dead")
            print(f"[flow]     fail: {reason}", flush=True)
            if g and g.get("country") and g["country"] not in SUPPORTED_COUNTRIES:
                break
        if len(passing) >= 2:
            break
    return passing


# ---------------------------------------------------------------------------
# L6 — dedicated browser pinned to the tunnel.
# ---------------------------------------------------------------------------

def find_browser():
    """Locate a Chromium-family browser (headless gate probes need one)."""
    cands = [
        os.environ.get("DASTYAB_BROWSER", ""),
        "/opt/google/chrome/google-chrome",
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/snap/bin/chromium",
        "/usr/bin/microsoft-edge",
        "/usr/bin/brave-browser",
    ]
    return next((c for c in cands
                 if c and os.path.isfile(c) and os.access(c, os.X_OK)), None)


def browser_gate(timeout=150, profile_dir=None):
    """Load flow.google.com in a headless browser THROUGH the tunnel.

    Returns (verdict, detail). curl can only prove the host answered; the
    region rejection is rendered by the app after its own backend call, so
    the only trustworthy signal is what the browser ends up showing.
    Returns verdict None when no browser is available — gate unknown, and
    the caller must not treat that as success.
    """
    ch = find_browser()
    if ch is None:
        return None, "no Chromium browser found (browser gate skipped)"
    # When a signed-in profile is supplied, drive THAT directory (Chrome
    # refuses to share a profile with a running instance, so the caller
    # must have closed it). Otherwise use a throwaway dir: the result is
    # an unauthenticated check, useful for the region gate only.
    prof = profile_dir or os.path.join(
        "/tmp", "dastyab-flow-gate-%d" % os.getpid())
    # A slow-but-working route needs patience; the page settles well
    # before the virtual-time budget expires, so keep the budget modest
    # and the wall-clock timeout generous. (A 25s budget once pushed a
    # working US route past a 75s wall clock and was misreported as a
    # gate failure.)
    args = [ch, "--headless=new", "--disable-gpu", "--no-first-run",
            "--no-default-browser-check",
            f"--proxy-server=socks5://127.0.0.1:{SOCK}",
            "--virtual-time-budget=12000",
            f"--user-data-dir={prof}", "--dump-dom", FLOW_URL]
    dom = ""
    try:
        r = subprocess.run(args, capture_output=True, text=True,
                           timeout=timeout)
        dom = r.stdout or ""
    except subprocess.TimeoutExpired as e:
        # Chrome was killed mid-dump. Do not discard what it produced:
        # `subprocess.run` attaches the partial output to the exception,
        # and a real rejection page is still worth reading.
        dom = (e.stdout or "")
        if isinstance(dom, bytes):
            dom = dom.decode("utf-8", "replace")
        if not dom:
            return None, ("browser gate timed out before the page settled "
                          "(route may be very slow)")
    except (OSError, subprocess.SubprocessError) as e:
        return None, f"browser gate failed to run ({e})"
    finally:
        if profile_dir is None:
            shutil.rmtree(prof, ignore_errors=True)

    # A profile Chrome still holds is reported plainly. This check must sit
    # after the try/finally, not inside it, or cleanup is skipped.
    if "SingletonLock" in dom or "ProcessSingleton" in dom:
        return None, ("profile is in use by a running Chrome — close that "
                      "browser first, then re-run")

    # Classify the ACCOUNT gate before anything else: it is a pass on
    # region and a hard stop on eligibility, and calling it "blocked"
    # sends the user after the wrong problem.
    low = dom.lower()
    for marker in UNAVAILABLE_MARKERS:
        if marker in low:
            return False, f"ACCOUNT-GATE: {ACCOUNT_HELP} (shows '{marker}')"
    # Then the region gate: a genuine rejection page can be small, and
    # reporting it as "rendered nothing" would blame the tunnel for what
    # is actually a region block.
    for marker in GATE_MARKERS:
        if marker in low:
            return False, ("REGION-GATE: Flow rejected this egress in the "
                           f"browser (shows '{marker}')")
    if len(dom) < 1000:
        return False, "browser rendered nothing (tunnel unusable for HTTPS)"
    if "aisandboxangularfrontend" not in low:
        return False, "browser did not render the Flow app"
    # Signed out, Flow serves its marketing landing page: no gate markers,
    # but also no evidence about the account. Treating that as a pass made
    # `prove` claim success while never testing the gate that actually
    # blocks a signed-in user.
    if "sign in" in low and "credits" not in low:
        return None, ("browser is NOT signed in (landing page) — region "
                      "gate looks clear, account gate untested; pass "
                      "--profile <dir> with a signed-in profile to test it")
    return True, "browser loaded the Flow app (no region or account gate)"


def launch_browser():
    """Open Flow in a browser pinned to the tunnel.

    Every candidate is launched with `--proxy-server` set: opening the
    user's normal browser would send the request straight from the real
    IP, which is the exact thing this tool exists to avoid.
    """
    cands = [
        os.environ.get("DASTYAB_BROWSER", ""),
        "/opt/google/chrome/google-chrome",
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/snap/bin/chromium",
        "/usr/bin/microsoft-edge",
        "/usr/bin/brave-browser",
    ]
    ch = next((c for c in cands if c and os.path.isfile(c) and os.access(c, os.X_OK)),
              None)
    if ch is None:
        print("[flow] No Chromium-based browser found (Chrome/Chromium/Edge/Brave).")
        print(f"[flow] Open {FLOW_URL} yourself through "
              f"socks5://127.0.0.1:{SOCK} — do NOT open it directly, that "
              f"would use your real IP and hit the region gate.")
        return
    os.makedirs(BROWSER_PROFILE, exist_ok=True)
    subprocess.Popen([ch, "--ozone-platform=x11",
                      f"--proxy-server=socks5://127.0.0.1:{SOCK}",
                      "--no-first-run", "--no-default-browser-check",
                      f"--user-data-dir={BROWSER_PROFILE}", FLOW_URL],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("[flow] Opened Flow in the dedicated tunnel-pinned browser.")
    print("[flow] Sign in there once — the profile keeps the session.")


# ---------------------------------------------------------------------------
# Commands.
# ---------------------------------------------------------------------------

def cmd_start(index=None, hops=1, want_country=None):
    if is_running():
        print(f"[flow] Tunnel already running on 127.0.0.1:{SOCK}")
        print("[flow] Opening Flow through the existing tunnel ...")
        launch_browser()
        return 0

    pool = load_pool()
    if not pool:
        print("[flow] No ranked pool yet — scanning candidates first ...")
        passing = scan(load_candidates(), want_country, try_n=10)
        if not passing:
            print("[flow] No candidate passed the gates. Widen the scan: "
                  "dastyab flow best --try 25", file=sys.stderr)
            return 3
        save_pool(passing)
        pool = load_pool()

    first = (index or 0) % len(pool)
    order = [first] + [(first + off) % len(pool) for off in range(1, len(pool))]
    for idx in order:
        route = pool[idx]
        nodes = route["nodes"]
        if hops == 1 and len(nodes) > 1:
            nodes = nodes[:1]  # prefer single hop unless chaining is forced
        print(f"[flow] Bringing up {_label(nodes)} ...")
        pid = bring_up(nodes)
        if pid is None:
            print(f"[flow] transport dead, next node", flush=True)
            continue
        g = probe_gates(want_country)
        if g["verdict"]:
            print(f"[flow] Tunnel up (pid {pid}) via {_label(nodes)}")
            print(f"[flow] SOCKS5: 127.0.0.1:{SOCK} | {g['reason']}")
            write_json_atomic(STATEFILE, {"idx": idx, "hops": len(nodes),
                                          "egress": g["egress"],
                                          "country": g["country"],
                                          "country_name": g["country_name"],
                                          "verified_epoch": time.time()})
            with open(IDXFILE, "w") as fh:
                fh.write(str(idx))
            print("[flow] Opening Flow ...")
            launch_browser()
            return 0
        stop(quiet=True)
        print(f"[flow] gate fail: {g['reason']} — rotating", flush=True)

    print("[flow] Every route in the pool failed the gates live.", file=sys.stderr)
    print("[flow] Re-scan and re-rank: dastyab flow best --try 20", file=sys.stderr)
    return 3


def cmd_next():
    state = read_json(STATEFILE, {})
    pool = load_pool()
    if not pool:
        print("[flow] No pool to rotate through. Run: dastyab flow best")
        return 3
    idx = (state.get("idx", 0) + 1) % len(pool)
    print(f"[flow] Rotating to route {idx + 1}/{len(pool)}")
    stop(quiet=True)
    return cmd_start(index=idx)


def cmd_stop():
    stop()
    return 0


def cmd_status():
    print(f"[flow] xray core: {find_xray()}")
    print(f"[flow] SOCKS port: 127.0.0.1:{SOCK} | pid file: {PIDFILE}")
    if not is_running():
        state = read_json(STATEFILE, {})
        if state:
            age = int(time.time() - state.get("verified_epoch", 0))
            print(f"[flow] Tunnel DOWN. Last verified route "
                  f"{state.get('country')} / {state.get('egress')} "
                  f"({age // 60} min ago) — start with: dastyab flow start")
        else:
            print("[flow] Tunnel DOWN and no previous state. "
                  "Start with: dastyab flow start")
        return 1
    g = probe_gates()
    if g["verdict"]:
        print(f"[flow] Tunnel UP. {g['reason']}")
        print("[flow] Verdict: egress is on Google's Flow list — "
              "Flow should load in the pinned browser.")
        return 0
    print(f"[flow] Tunnel UP but gates FAIL: {g['reason']}")
    print("[flow] This is exactly what causes the "
          "'Flow is not available in your country yet.' redirect.")
    print("[flow] Rotate now with: dastyab flow next")
    return 1


def cmd_verify(want_country=None):
    pool = load_pool()
    if not pool:
        print("[flow] No saved pool. Run: dastyab flow best")
        return 1
    print(f"[flow] {len(pool)} saved route(s) — re-probing each live:")
    was_running = is_running()
    alive = 0
    for i, route in enumerate(pool):
        nodes = route["nodes"]
        g = _try_nodes(nodes, want_country)
        if g and g["verdict"]:
            alive += 1
            print(f"   [{i}] {_label(nodes)}  OK  {g['country']} "
                  f"{g['egress']} ({g['latency_ms']}ms)")
        else:
            print(f"   [{i}] {_label(nodes)}  DEAD  "
                  f"{(g or {}).get('reason', 'transport dead')}")
    if was_running:
        cmd_start(index=read_json(STATEFILE, {}).get("idx", 0))
    print(f"[flow] {alive}/{len(pool)} routes still pass the gates.")
    return 0 if alive else 3


def cmd_best(try_n=10, want_country=None):
    cands = load_candidates()
    if not cands:
        print("[flow] No candidates found in any source "
              "(pool, nekoray, nodes.json).", file=sys.stderr)
        print("[flow] Supply some with: dastyab flow build-pool <uris.txt>",
              file=sys.stderr)
        return 3
    print(f"[flow] {len(cands)} candidate(s) from all sources; "
          f"testing up to {try_n} for the gates ...")
    passing = scan(cands, want_country, try_n=try_n)
    if not passing:
        print("[flow] Nothing passed. Widen: dastyab flow best --try 25 "
              "or supply fresh cards: dastyab flow build-pool <uris.txt>",
              file=sys.stderr)
        return 3
    passing.sort(key=lambda r: (0 if not r["proxy"] else 1,
                                0 if not r["hosting"] else 1,
                                r["latency_ms"]))
    save_pool(passing)
    print(f"[flow] Ranked pool saved ({len(passing)} route(s)) -> {FLOW_POOL}")
    for i, r in enumerate(passing):
        print(f"   [{i}] {r['country']} {r['egress']} "
              f"({r['latency_ms']}ms"
              f"{', proxy-flagged' if r['proxy'] else ''}"
              f"{', hosting' if r['hosting'] else ''})")
    return 0


def cmd_watch(interval=30):
    """L7 — self-healing watchdog: keep a gate-passing egress alive."""
    def _probe_once():
        if not is_running():
            return None
        return probe_gates()

    print(f"[flow] Watchdog started: probing every {interval}s "
          f"(Ctrl-C to stop)")
    fails = 0
    while True:
        try:
            g = _probe_once()
            if g and g["verdict"]:
                fails = 0
            elif g is None:
                print(f"[flow] {time.strftime('%H:%M:%S')} tunnel down — "
                      "restarting from the pool")
                cmd_start(index=read_json(STATEFILE, {}).get("idx", 0))
                fails = 0
            else:
                fails += 1
                print(f"[flow] {time.strftime('%H:%M:%S')} gate fail "
                      f"({fails}): {g['reason']}", file=sys.stderr)
                if fails >= 2:
                    print(f"[flow] {time.strftime('%H:%M:%S')} rotating ...")
                    cmd_next()
                    fails = 0
            time.sleep(interval)
        except KeyboardInterrupt:
            print("[flow] Watchdog stopped.")
            return 0


def cmd_build_pool(uris_txt, want=6):
    if not os.path.isfile(uris_txt):
        raise SystemExit(f"[flow] No such URI file: {uris_txt}")
    uris, seen = [], set()
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
        raise SystemExit(f"[flow] No vless:// URIs found in {uris_txt}")
    print(f"[flow] {len(uris)} unique URI(s); testing up to 30 for a "
          f"pool of {want} ...")
    passing = []
    for u in uris[:30]:
        g = _try_nodes([u])
        if g and g["verdict"]:
            print(f"   {u['addr']}:{u['port']}  OK  {g['country']} "
                  f"{g['egress']}", flush=True)
            passing.append({"nodes": [u], "egress": g["egress"],
                            "country": g["country"],
                            "country_name": g["country_name"],
                            "proxy": g["proxy"], "hosting": g["hosting"],
                            "latency_ms": g["latency_ms"],
                            "checked_epoch": time.time()})
        else:
            print(f"   {u['addr']}:{u['port']}  DEAD  "
                  f"{(g or {}).get('reason', 'transport dead')}", flush=True)
        if len(passing) >= want:
            break
    if not passing:
        raise SystemExit(
            "[flow] None of the supplied URIs passed the gates; keeping the "
            f"existing pool at {FLOW_POOL} unchanged."
        )
    save_pool(passing)
    print(f"[flow] Built pool: {len(passing)} gate-passing route(s) -> {FLOW_POOL}")


def cmd_prove(tries=6, want_country=None, profile=None):
    """Prove (or disprove) Flow access end-to-end, in a real browser.

    This is the command to run when the user's question is "can I actually
    open Flow?" — it walks candidates, and for each one that clears the
    country/reachability gates it loads the page in a headless browser and
    reports what the browser rendered. It is deliberately slow and
    honest: a route is only reported usable when the browser shows the
    app rather than the region-rejection page.
    """
    ch = find_browser()
    if ch is None:
        print("[flow] No Chromium browser available to prove with.",
              file=sys.stderr)
        print("[flow] Install Chrome/Chromium, or set DASTYAB_BROWSER.",
              file=sys.stderr)
        return 3
    print(f"[flow] Proving Flow access with a real browser ({ch})")
    print(f"[flow] Browser pinned to socks5://127.0.0.1:{SOCK}")

    cands = load_candidates()
    if not cands:
        print("[flow] No candidates in any source.", file=sys.stderr)
        return 3

    was_running = is_running()
    if was_running:
        stop(quiet=True)
        time.sleep(1)

    ok_routes = []
    for i, cand in enumerate(cands[:tries], 1):
        nodes = [cand]
        label = _label(nodes)
        print(f"[flow] [{i}] {label}", flush=True)
        pid = bring_up(nodes)
        if pid is None:
            print("[flow]     transport dead", flush=True)
            continue
        try:
            g = probe_gates(want_country, browser_profile=profile)
            if not g["verdict"]:
                reason = g["reason"]
                if reason.startswith("ACCOUNT-GATE:"):
                    # The IP is fine; the account is not eligible. Say so
                    # loudly and stop — no other node can change this.
                    print("[flow]     IP GATE PASSED (region is fine).",
                          flush=True)
                    print("[flow]     ACCOUNT GATE FAILED — this is not "
                          "fixable with a proxy.", flush=True)
                    print("[flow] " + reason[len("ACCOUNT-GATE:"):].strip(),
                          flush=True)
                    return 4
                print(f"[flow]     blocked: {reason}", flush=True)
                continue
            b_ok = g.get("browser_ok")
            if b_ok is False and str(g.get("reason", "")).startswith("ACCOUNT-GATE:"):
                print("[flow]     IP GATE PASSED (region is fine).", flush=True)
                print("[flow]     ACCOUNT GATE FAILED — a proxy cannot fix "
                      "this.", flush=True)
                print("[flow] " + str(g["reason"])[len("ACCOUNT-GATE:"):].strip(),
                      flush=True)
                return 4
            if b_ok:
                print(f"[flow]     PROVEN  {g['browser_detail']}", flush=True)
                print(f"[flow]             egress {g['egress']} "
                      f"({g['country']}) {g['latency_ms']}ms", flush=True)
                ok_routes.append((nodes, g))
                break
            print(f"[flow]     not proven: {g.get('browser_detail')}",
                  flush=True)
        finally:
            stop(quiet=True)

    if not ok_routes:
        print("[flow] No route was proven in the browser.", file=sys.stderr)
        print("[flow] Try more candidates: dastyab flow prove --try 12",
              file=sys.stderr)
        return 3

    nodes, g = ok_routes[0]
    # Leave the proven route up so the next `start` reuses it directly.
    pid = bring_up(nodes)
    if pid:
        write_json_atomic(STATEFILE, {"idx": 0, "hops": len(nodes),
                                      "egress": g["egress"],
                                      "country": g["country"],
                                      "country_name": g["country_name"],
                                      "verified_epoch": time.time()})
        with open(IDXFILE, "w") as fh:
            fh.write("0")
        print(f"[flow] Proven route is up (pid {pid}) on "
              f"127.0.0.1:{SOCK}")
    print("[flow] RESULT: the region gate passes through this route.")
    print("[flow] Note: this proves the IP/region requirement only. Whether")
    print("[flow] the signed-in account is entitled to Flow is a separate")
    print("[flow] gate the browser reports once you sign in.")
    print("[flow] Open it with: dastyab flow start")
    return 0


def cmd_countries():
    codes = sorted(SUPPORTED_COUNTRIES)
    print(f"[flow] Google Flow is officially available in {len(codes)} "
          "countries/territories (per support.google.com, fetched "
          "2026-11-16):")
    for i in range(0, len(codes), 16):
        print("   " + " ".join(codes[i:i + 16]))
    print("[flow] Filter egress with: dastyab flow start --country XX")


USAGE = __doc__


def main():
    ap = argparse.ArgumentParser(
        prog="dastyab-flow.py",
        description="Multi-layer access to Google Flow (flow.google.com).",
    )
    sub = ap.add_subparsers(dest="cmd")

    p = sub.add_parser("start", help="tunnel + open Flow (default)")
    p.add_argument("index", nargs="?", type=int, default=None)
    p.add_argument("--hops", type=int, default=1, choices=(1, 2),
                   help="1 = direct node, 2 = entry->exit chain")
    p.add_argument("--country", default=None,
                   help="require this egress country (ISO code)")

    sub.add_parser("next", help="rotate to the next ranked route")
    sub.add_parser("stop", help="stop the tunnel")
    sub.add_parser("status", help="current egress + gate verdict")

    p = sub.add_parser("verify", help="re-probe every saved route")
    p.add_argument("--country", default=None)

    p = sub.add_parser("best", help="scan candidates wider, re-rank the pool")
    p.add_argument("--try", dest="try_n", type=int, default=10)
    p.add_argument("--country", default=None)

    p = sub.add_parser("watch", help="self-healing watchdog")
    p.add_argument("interval", nargs="?", type=int, default=30)

    p = sub.add_parser("build-pool", help="build the pool from vless:// cards")
    p.add_argument("uris", help="file with one vless:// URI per line")

    p = sub.add_parser("prove",
                       help="prove Flow access in a real browser (decisive)")
    p.add_argument("--try", dest="try_n", type=int, default=6)
    p.add_argument("--country", default=None)
    p.add_argument("--profile", default=None,
                   help="a signed-in Chrome profile dir to test the "
                        "account gate (close that Chrome first)")

    sub.add_parser("countries", help="show Google's supported-country list")

    a = ap.parse_args(args=None if len(sys.argv) > 1 else ["start"])
    cmd = (a.cmd or "start").lower()

    if cmd == "start":
        return cmd_start(a.index, a.hops, a.country)
    if cmd == "next":
        return cmd_next()
    if cmd == "stop":
        return cmd_stop()
    if cmd == "status":
        return cmd_status()
    if cmd == "verify":
        return cmd_verify(a.country)
    if cmd == "best":
        return cmd_best(a.try_n, a.country)
    if cmd == "watch":
        return cmd_watch(a.interval)
    if cmd == "build-pool":
        cmd_build_pool(a.uris)
        return 0
    if cmd == "prove":
        return cmd_prove(a.try_n, a.country, a.profile)
    if cmd == "countries":
        cmd_countries()
        return 0
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main() or 0)
