#!/usr/bin/env python3
"""
dastyab aistudio — one-command clean-IP access to Google AI Studio.

WHAT THIS DOES (very simply):
  You have a bunch of VPN "server cards" (vless URLs) that some free channels post
  on Telegram. Most are old/broken. A few are alive and give you a real foreign IP.
  This tool:
    1. Reads your saved working server cards (from the pool we tested).
    2. Starts a tiny local "proxy tunnel" (xray) that routes your traffic through
       the working foreign server, so the internet sees YOU as being in that country.
    3. Opens Google AI Studio through that tunnel — so it thinks you're in a
       supported country and lets you in.

USAGE:
  dastyab-aistudio.py start    -> start tunnel + open AI Studio
  dastyab-aistudio.py verify   -> just check which server works + your clean IP
  dastyab-aistudio.py stop     -> stop the tunnel
"""
import json, os, re, subprocess, sys, time, socket, glob, random, signal

HOME = os.path.expanduser("~")
POOL = os.path.join(HOME, ".dastyab/profiles/default/fresh_vless.json")
SOCK = 10850
PIDFILE = "/tmp/dastyab_aistudio.pid"
AISTUDIO_URL = "https://aistudio.google.com/"

def find_xray():
    """Locate an xray-compatible core.

    Search order: an explicit override, the binary's own helper dir, anything
    on PATH, then the known third-party install locations. The old code used a
    single /tmp path, which is wiped on reboot, so `start` died with a
    traceback on a fresh boot even when xray was installed.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    cands = [
        os.environ.get("DASTYAB_XRAY", ""),
        os.path.join(here, "xray"),
        os.path.join(HOME, ".dastyab/bin/xray"),
        "/usr/local/bin/xray", "/usr/bin/xray", "/opt/xray/xray",
        "/tmp/xraybin/xray",
        "/opt/v2rayN/bin/xray/xray",
        "/opt/nekoray/nekoray_core", "/opt/nekoray/nekobox_core",
    ]
    for c in cands:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    for name in ("xray", "v2ray"):
        for d in os.environ.get("PATH", "").split(os.pathsep):
            p = os.path.join(d, name) if d else ""
            if p and os.path.isfile(p) and os.access(p, os.X_OK):
                return p
    raise SystemExit(
        "[aistudio] No xray core found. Install xray and put it on PATH, or set "
        "DASTYAB_XRAY=/path/to/xray"
    )

_XRAY_VERSION = None

def xray_supports_vision():
    """True if the resolved core is xray >= 1.7 (vision/XTLS support).

    Unknown cores (nekoray wrappers, v2ray) report False: sending them a
    `flow` field makes them reject the entire config, and a plain VLESS TCP
    connection still works without it.
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

def build_cfg(server):
    q = server.get('q', {})
    addr = server['addr']; port = server['port']
    uuid = server['uuid']
    sec = q.get('security','none'); net = q.get('type','tcp')
    out = {"protocol":"vless","settings":{"vnext":[{"address":addr,"port":port,
            "users":[{"id":uuid,"encryption":"none"}]}]}}
    # `flow` (xtls-rprx-vision) is only valid over TLS/REALITY and only on
    # xray >= 1.7. Older cores reject the whole config with
    # "flow doesn't support xtls-rprx-vision in this version", which used to
    # surface as an opaque exit code. Drop it when it cannot work.
    flow = q.get('flow')
    if flow:
        if sec not in ('tls', 'reality'):
            flow = None
        elif not xray_supports_vision():
            flow = None
    if flow:
        out['settings']['vnext'][0]['users'][0]['flow'] = flow
    ss = {}
    if sec == 'reality':
        ss.update(security="reality",
                  realitySettings={"serverName":q.get('sni',addr),
                    "fingerprint":q.get('fp','chrome'),"publicKey":q.get('pbk',''),
                    "shortId":q.get('sid',''),"spiderX":q.get('spx','/')})
    elif sec == 'tls':
        ss["security"]="tls"
        ss["tlsSettings"]={"serverName":q.get('sni',addr),"fingerprint":q.get('fp','chrome')}
        if q.get('alpn'):
            ss["tlsSettings"]["alpn"]=[x for x in q['alpn'].split(',') if x]
        if q.get('pinSHA256'):
            ss["tlsSettings"]["pinnedPeerCertSha256"]=[q['pinSHA256']]
    else:
        ss["security"]="none"
    if net == 'ws':
        ss["network"]="ws"
        ss["wsSettings"]={"path":q.get('path','/'),"headers":{"Host":q.get('host') or q.get('sni') or addr}}
    elif net == 'xhttp':
        ss["network"]="xhttp"
        ss["xhttpSettings"]={"path":q.get('path','/'),"host":q.get('host') or q.get('sni') or addr,"mode":q.get('mode','auto')}
    elif net == 'grpc':
        ss["network"]="grpc"
        ss["grpcSettings"]={"serviceName":q.get('serviceName','')}
    else:
        ss["network"]="tcp"
    out["streamSettings"]=ss
    inb={"listen":"127.0.0.1","port":SOCK,"protocol":"socks","settings":{"auth":"noauth","udp":True},"tag":"socks-in"}
    return {"log":{"loglevel":"warning"},"inbounds":[inb],
            "outbounds":[out,{"protocol":"freedom","tag":"direct"}]}

def is_running():
    if os.path.exists(PIDFILE):
        try:
            pid = int(open(PIDFILE).read())
            os.kill(pid, 0)
            return True
        except (ProcessLookupError, ValueError, PermissionError):
            pass
    return False

def start(server_index=0):
    if is_running():
        print(f"[aistudio] Tunnel already running on 127.0.0.1:{SOCK}")
        # Still honor the user's intent: they asked to open AI Studio.
        print(f"[aistudio] Opening AI Studio through the existing tunnel...")
        launch_browser()
        return False, None
    # Read the pool defensively: a missing or corrupt file is the normal
    # first-run state, not a crash. The guard must come before the load, or a
    # user who has never run `verify` gets a Python traceback instead of the
    # one line telling them what to do.
    try:
        with open(POOL) as fh:
            pool = json.load(fh)
    except FileNotFoundError:
        raise SystemExit(
            "[aistudio] No server pool yet. Run: dastyab aistudio verify"
        )
    except (json.JSONDecodeError, OSError) as e:
        raise SystemExit(f"[aistudio] Server pool is unreadable ({e}). Run: dastyab aistudio verify")
    if not pool:
        raise SystemExit(
            "[aistudio] Server pool is empty. Run: dastyab aistudio verify"
        )
    srv = pool[server_index % len(pool)]
    cfg = build_cfg(srv)
    cfgpath = "/tmp/dastyab_aistudio.json"
    open(cfgpath,"w").write(json.dumps(cfg))
    xray = find_xray()
    try:
        proc = subprocess.Popen([xray,"run","-c",cfgpath],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError as e:
        raise SystemExit(f"[aistudio] Cannot launch {xray}: {e}")
    open(PIDFILE,"w").write(str(proc.pid))
    open("/tmp/dastyab_aistudio_idx","w").write(str(server_index % len(pool)))
    # Wait for the SOCKS port to accept. This must actually report failure:
    # the old loop broke out on success but never checked, so a core that
    # printed a banner and exited immediately (nekoray_core does exactly that)
    # was reported as "Tunnel started" while nothing was listening.
    up = False
    for _ in range(25):
        if proc.poll() is not None:
            break  # core exited; no point waiting
        try:
            s = socket.socket()
            s.settimeout(0.5)
            s.connect(("127.0.0.1", SOCK))
            s.close()
            up = True
            break
        except Exception:
            time.sleep(0.3)
            s.close()
    if not up:
        rc = proc.poll()
        proc.kill()
        try:
            os.remove(PIDFILE)
        except OSError:
            pass
        detail = (
            f"{xray} exited immediately (code {rc}) — it does not accept "
            f"`run -c <config>`; that is a nekoray wrapper, not an xray core"
            if rc is not None
            else f"{xray} never opened 127.0.0.1:{SOCK}"
        )
        raise SystemExit(
            f"[aistudio] Tunnel failed to start: {detail}.\n"
            f"[aistudio] Set DASTYAB_XRAY=/path/to/xray (a real xray core)."
        )
    print(f"[aistudio] Tunnel started via {srv['addr']}:{srv['port']} (pid {proc.pid})")
    print(f"[aistudio] Local SOCKS5 proxy: 127.0.0.1:{SOCK}")

    # A listening SOCKS port only proves the local core is up. Traffic must
    # actually reach the far side, or the user gets a browser that silently
    # fails every request. Verify egress and say so plainly when it is broken.
    egress = probe_egress(timeout=20)
    if egress:
        print(f"[aistudio] Clean egress IP: {egress}")
    else:
        print(
            "[aistudio] WARNING: the proxy is up but no traffic is getting "
            "through — this node looks dead at the far end.",
            file=sys.stderr,
        )
        print(
            f"[aistudio] Try another node: dastyab aistudio stop && "
            f"dastyab aistudio verify, or re-run verify to rebuild the pool.",
            file=sys.stderr,
        )
    print(f"[aistudio] Opening AI Studio through the tunnel...")
    launch_browser()
    return bool(egress), server_index % len(pool)

def probe_egress(timeout=12):
    """Return current egress IP via tunnel, or None if tunnel dead."""
    try:
        r = subprocess.run(["curl","-s","--max-time",str(timeout),
            "--socks5-hostname",f"127.0.0.1:{SOCK}","https://api.ipify.org"],
            capture_output=True, text=True, timeout=timeout+3)
        out = r.stdout.strip()
        return out if out and not out.startswith("curl") else None
    except Exception:
        return None

def watch(interval=20, max_fail=2):
    """Self-healing daemon: probe the tunnel; if it dies, swap to the next
    live server from the pool. Runs in the background."""
    import random
    pool = json.load(open(POOL))
    if not pool:
        raise SystemExit("Empty pool")
    fail = 0
    idx = 0
    if os.path.exists("/tmp/dastyab_aistudio_idx"):
        try: idx = int(open("/tmp/dastyab_aistudio_idx").read())
        except: pass
    print(f"[aistudio] Watchdog started: probing every {interval}s (pool={len(pool)})")
    while True:
        time.sleep(interval)
        ip = probe_egress()
        if ip:
            fail = 0
            continue
        fail += 1
        if fail >= max_fail:
            print(f"[aistudio] Tunnel down (probe {fail}). Rotating server...")
            # stop current
            stop()
            time.sleep(1)
            # try next servers until one works
            for off in range(1, len(pool)+1):
                nxt = (idx + off) % len(pool)
                try:
                    started, _ = start(nxt)
                except Exception:
                    started = False
                time.sleep(4)
                if started and probe_egress():
                    idx = nxt
                    fail = 0
                    print(f"[aistudio] Switched to {pool[idx]['addr']}:{pool[idx]['port']}")
                    break
            else:
                print("[aistudio] No server in pool responded. Will retry.")

def stop():
    if os.path.exists(PIDFILE):
        try:
            pid=int(open(PIDFILE).read())
            os.kill(pid, signal.SIGTERM)
            time.sleep(0.5)
            os.kill(pid, signal.SIGKILL)
        except Exception:
            pass
        os.remove(PIDFILE)
    # also kill any xray we own
    subprocess.run(["bash","-c",f"for p in $(pgrep -f 'dastyab_aistudio.json'); do kill -9 $p 2>/dev/null; done"])
    print("[aistudio] Tunnel stopped")

def launch_browser():
    # The desktop browser (Chrome) with our SOCKS5 proxy.
    ch = "/opt/google/chrome/google-chrome"
    if os.path.exists(ch):
        tmp = os.path.join(HOME, ".dastyab-aistudio-profile")
        os.makedirs(tmp, exist_ok=True)
        subprocess.Popen([ch,"--ozone-platform=x11",
                          f"--proxy-server=socks5://127.0.0.1:{SOCK}",
                          f"--user-data-dir={tmp}", AISTUDIO_URL],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"[aistudio] Opened Chrome with the tunnel proxy.")
    else:
        print("[aistudio] Chrome not found at /opt/google/chrome/google-chrome")

def verify():
    pool = json.load(open(POOL)) if os.path.exists(POOL) else []
    if not pool:
        print("[aistudio] No verified servers stored yet. Run with a server list to build the pool.")
        return
    print(f"[aistudio] {len(pool)} verified working servers in pool:")
    for s in pool:
        print(f"   {s['addr']}:{s['port']}  egress {s.get('egress','?')}")
    if is_running():
        print(f"[aistudio] Tunnel is running — check your IP through it:")
        subprocess.run(["curl","-s","--max-time","15","--socks5-hostname",
                        f"127.0.0.1:{SOCK}","https://api.ipify.org"]); print()

def pop_pool(uris_txt):
    sys.path.insert(0,"/tmp")
    import test_fresh_vless as tfv
    uris=[tfv.parse_vless(l.strip()) for l in open(uris_txt) if l.strip().startswith("vless://")]
    uris=[u for u in uris if u]
    import random; random.seed(1); random.shuffle(uris)
    working=[]
    for u in uris[:30]:
        ip = tfv.test(u)
        if ip:
            working.append({**u,"egress":ip})
        if len(working)>=6: break
    os.makedirs(os.path.dirname(POOL), exist_ok=True)
    json.dump(working, open(POOL,"w"), indent=1)
    print(f"Built pool: {len(working)} working servers -> {POOL}")

if __name__=="__main__":
    cmd = sys.argv[1] if len(sys.argv)>1 else "start"
    if cmd=="start":
        idx = int(sys.argv[2]) if len(sys.argv)>2 else 0
        ok, _ = start(idx)
        # Exit non-zero when egress never came up, so the CLI (and any script
        # wrapping it) can tell a working tunnel from a listening-but-dead one.
        sys.exit(0 if ok else 3)
    elif cmd=="stop":
        stop()
    elif cmd=="verify":
        verify()
    elif cmd=="watch":
        interval = int(sys.argv[2]) if len(sys.argv)>2 else 20
        watch(interval=interval)
    elif cmd=="build-pool":
        pop_pool(sys.argv[2])
    else:
        print(__doc__)
