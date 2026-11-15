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
import json, os, re, subprocess, sys, time, socket, signal, urllib.parse

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
            if _is_real_xray(c):
                return c
    for name in ("xray", "v2ray"):
        for d in os.environ.get("PATH", "").split(os.pathsep):
            p = os.path.join(d, name) if d else ""
            if p and os.path.isfile(p) and os.access(p, os.X_OK):
                if _is_real_xray(p):
                    return p
    raise SystemExit(
        "[aistudio] No xray core found. Install xray and put it on PATH, or set "
        "DASTYAB_XRAY=/path/to/xray"
    )

def _is_real_xray(path):
    """True if `path version` identifies itself as a usable xray/v2ray core.

    An existing, executable file is not enough. `nekoray_core` is a GUI
    wrapper: it answers `version` with "Xray: <build> NekoRay: ..." and
    accepts `run -c <config>` as far as the OS is concerned, then exits 0 doing
    nothing — which surfaces later as "the proxy is up but no traffic is
    getting through". `nekobox_core` is sing-box, which has a different CLI
    entirely. Require a real version banner, not just the substring "Xray".
    """
    try:
        out = subprocess.run([path, "version"], capture_output=True, text=True,
                             timeout=8).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    if "NekoRay" in out or "NekoBox" in out:
        return False
    return bool(re.search(r"(Xray|V2Ray)\s+\d+\.\d+", out))

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
        # This is a success, not a failure: the tunnel the user asked for is
        # up and serving traffic. Returning False here made the CLI exit 3 and
        # report "Aistudio failed" right after opening the browser.
        print(f"[aistudio] Opening AI Studio through the existing tunnel...")
        launch_browser()
        return True, None
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

    # A pool entry is a *candidate*, not a promise: free-channel nodes die
    # between runs. Binding only pool[0] meant a single dead node made the
    # command useless even with healthy nodes sitting behind it, and the only
    # advice left was "try another node" with no way to choose one. Walk the
    # pool until traffic actually flows.
    first = server_index % len(pool)
    order = [first] + [(first + off) % len(pool) for off in range(1, len(pool))]
    dead = []
    for idx in order:
        egress = _bring_up(pool[idx])
        if egress:
            if dead:
                print(f"[aistudio] Skipped dead node(s): {', '.join(dead)}",
                      file=sys.stderr)
            open("/tmp/dastyab_aistudio_idx", "w").write(str(idx))
            print(f"[aistudio] Clean egress IP: {egress}")
            print(f"[aistudio] Opening AI Studio through the tunnel...")
            launch_browser()
            return True, idx
        dead.append(f"{pool[idx]['addr']}:{pool[idx]['port']}")
        stop(quiet=True)

    print("[aistudio] WARNING: every node in the pool is dead at the far end.",
          file=sys.stderr)
    print("[aistudio] Run `dastyab aistudio verify` to rebuild the pool from "
          "fresh server cards.", file=sys.stderr)
    return False, None

def _bring_up(srv, announce=True):
    """Start one core on one pool entry; return the egress IP, or None."""
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
    # Wait for the SOCKS port to accept. This must actually report failure:
    # the old loop broke out on success but never checked, so a core that
    # printed a banner and exited immediately (nekoray_core does exactly that)
    # was reported as "Tunnel started" while nothing was listening.
    up = False
    for _ in range(25):
        if proc.poll() is not None:
            break  # core exited; no point waiting
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
    # A listening SOCKS port only proves the local core is up. Traffic must
    # actually reach the far side, or the user gets a browser that silently
    # fails every request. Report the node only once egress is confirmed, so a
    # dead entry is never announced as a working tunnel.
    ip = probe_egress(timeout=20)
    if ip and announce:
        print(f"[aistudio] Tunnel started via {srv['addr']}:{srv['port']} (pid {proc.pid})")
        print(f"[aistudio] Local SOCKS5 proxy: 127.0.0.1:{SOCK}")
    return ip

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
    def load_pool():
        """Read the pool, or return None with a reason the caller can print.

        A watchdog is the one component that must never die: it is what makes
        `start` keep working overnight. The old version called
        `json.load(open(POOL))` with no guard, so a missing or half-written
        pool killed the watchdog on its first line.
        """
        try:
            with open(POOL) as fh:
                return json.load(fh), None
        except FileNotFoundError:
            return None, "no server pool yet"
        except (json.JSONDecodeError, OSError) as e:
            return None, f"pool unreadable ({e})"

    pool, why = load_pool()
    if not pool:
        raise SystemExit(
            f"[aistudio] Cannot watch: {why}. Run: dastyab aistudio verify"
        )
    fail = 0
    idx = 0
    if os.path.exists("/tmp/dastyab_aistudio_idx"):
        try:
            idx = int(open("/tmp/dastyab_aistudio_idx").read())
        except (OSError, ValueError):
            pass
    print(f"[aistudio] Watchdog started: probing every {interval}s (pool={len(pool)})")
    while True:
        time.sleep(interval)
        ip = probe_egress()
        if ip:
            fail = 0
            continue
        fail += 1
        if fail < max_fail:
            continue
        print(f"[aistudio] Tunnel down (probe {fail}). Rotating server...")
        stop(quiet=True)
        time.sleep(1)
        # Re-read the pool on every rotation: `build-pool` may have replaced
        # it while this watchdog was running, and a stale in-memory copy would
        # rotate through servers the user already discarded.
        fresh, why = load_pool()
        if fresh:
            pool = fresh
            idx %= len(pool)
        else:
            print(f"[aistudio] Cannot rotate: {why}. Keeping the watchdog alive.",
                  file=sys.stderr)
            time.sleep(interval)
            continue
        # Try each remaining entry until traffic actually flows.
        for off in range(1, len(pool) + 1):
            nxt = (idx + off) % len(pool)
            try:
                started, landed = start(nxt)
            except SystemExit as e:
                # `start` reports a broken core or unusable pool via SystemExit.
                # That is expected during rotation and must not kill the
                # watchdog — the old `except Exception` let it through, since
                # SystemExit inherits from BaseException, not Exception.
                print(f"[aistudio] Rotation attempt failed: {e}", file=sys.stderr)
                started, landed = False, None
            except OSError as e:
                print(f"[aistudio] Rotation attempt failed: {e}", file=sys.stderr)
                started, landed = False, None
            time.sleep(4)
            if started and probe_egress():
                idx = landed if landed is not None else nxt
                fail = 0
                print(f"[aistudio] Switched to {pool[idx]['addr']}:{pool[idx]['port']}")
                break
        else:
            print("[aistudio] No server in pool responded. Will retry.",
                  file=sys.stderr)

def stop(quiet=False):
    if os.path.exists(PIDFILE):
        try:
            pid=int(open(PIDFILE).read())
            os.kill(pid, signal.SIGTERM)
            time.sleep(0.5)
            os.kill(pid, signal.SIGKILL)
        except Exception:
            pass
        try:
            os.remove(PIDFILE)
        except OSError:
            pass
    # Also kill any core we own. Match on the config path we write, and skip
    # our own pid *and* the shell running this sweep: `pgrep -f` matches the
    # command line of the `bash -c` child itself, which contains the pattern,
    # so kill -9 on it could destroy the sweep mid-loop (and the caller's
    # shell if it shares the process group).
    subprocess.run(["bash", "-c",
        "for p in $(pgrep -f dastyab_aistudio.json); do "
        "[ \"$p\" = \"${1}\" ] || [ \"$p\" = \"$$\" ] || kill -9 \"$p\" 2>/dev/null; done",
        "dastyab-stop", str(os.getpid())],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not quiet:
        print("[aistudio] Tunnel stopped")

def launch_browser():
    """Open AI Studio in a browser that is pinned to the tunnel.

    Every candidate must be launched with `--proxy-server` set: opening the
    user's normal browser would send the request straight from the real IP,
    which is the exact thing this tool exists to avoid, and would silently
    expose the account to the region gate.
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
    ch = next((c for c in cands if c and os.path.isfile(c) and os.access(c, os.X_OK)), None)
    if ch is None:
        print("[aistudio] No Chromium-based browser found (Chrome/Chromium/Edge/Brave).")
        print(f"[aistudio] Open {AISTUDIO_URL} yourself through "
              f"socks5://127.0.0.1:{SOCK} — do NOT open it directly, that "
              f"would use your real IP.")
        return
    tmp = os.path.join(HOME, ".dastyab-aistudio-profile")
    os.makedirs(tmp, exist_ok=True)
    subprocess.Popen([ch, "--ozone-platform=x11",
                      f"--proxy-server=socks5://127.0.0.1:{SOCK}",
                      f"--user-data-dir={tmp}", AISTUDIO_URL],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("[aistudio] Opened the browser with the tunnel proxy.")

def verify():
    pool = json.load(open(POOL)) if os.path.exists(POOL) else []
    if not pool:
        print("[aistudio] No verified servers stored yet. Run with a server list to build the pool.")
        return
    # The stored `egress` value is a snapshot from the day the pool was built.
    # Printing it as-is told the user "6 verified working servers" while five
    # of them were long dead, so report what is true *now*.
    print(f"[aistudio] {len(pool)} servers in pool — probing each one now:")
    alive = 0
    was_running = is_running()
    if was_running:
        stop(quiet=True)
    for i, s in enumerate(pool):
        ip = _bring_up(s, announce=False)
        stop(quiet=True)
        mark = f"OK   egress {ip}" if ip else "DEAD"
        if ip:
            alive += 1
        print(f"   [{i}] {s['addr']}:{s['port']}  {mark}")
    print(f"[aistudio] {alive}/{len(pool)} reachable now. "
          f"`start` walks the pool and skips dead entries.")
    if alive == 0:
        print("[aistudio] Pool is dead. Rebuild it from fresh server cards "
              "with: dastyab aistudio build-pool <uris.txt>")

def parse_vless(line):
    """Parse one `vless://` share URI into a pool entry.

    Free channels post these as a single line each. Unknown keys are kept in
    `q` so `build_cfg` can use them; malformed lines return None instead of
    raising, because a channel export containing one bad line is normal.
    """
    line = line.split('#')[0].strip()
    m = re.match(r"vless://([^@]*)@([^:]+):(\d+)\?(.*)", line)
    if not m:
        return None
    uuid, host, port, qs = m.group(1), m.group(2), int(m.group(3)), m.group(4)
    if not uuid or not host:
        return None
    return {"uuid": uuid, "addr": host, "port": port,
            "q": dict(urllib.parse.parse_qsl(qs))}

def pop_pool(uris_txt, want=6, probe_port=10930):
    """Rebuild the pool from a file of `vless://` share URIs.

    This used to `import test_fresh_vless` from a hardcoded `/tmp` path, so it
    always died with ModuleNotFoundError on any machine (the module ships in
    the checkout, never in /tmp). It also wrote `[]` straight over the pool
    when the batch produced nothing, silently destroying a working pool. Both
    are fixed: parsing lives here, and the pool is only replaced when the
    rebuild actually finds live servers.
    """
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

    import random
    random.seed(1)
    random.shuffle(uris)
    print(f"[aistudio] {len(uris)} unique URI(s); testing up to 30 for a pool of {want}...")

    working = []
    for u in uris[:30]:
        ip = _probe_entry(u, probe_port)
        status = f"OK   egress {ip}" if ip else "DEAD"
        print(f"   {u['addr']}:{u['port']}  {status}", flush=True)
        if ip:
            working.append({**u, "egress": ip})
        if len(working) >= want:
            break

    if not working:
        # Never clobber a pool that might still be useful: an empty batch
        # means this channel's cards expired, not that the user wants no pool.
        raise SystemExit(
            "[aistudio] None of the supplied URIs are live; keeping the "
            f"existing pool at {POOL} unchanged."
        )

    os.makedirs(os.path.dirname(POOL), exist_ok=True)
    tmp = POOL + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(working, fh, indent=1)
    os.replace(tmp, POOL)
    print(f"[aistudio] Built pool: {len(working)} working server(s) -> {POOL}")

def _probe_entry(u, port):
    """Test one candidate on its own port; True with its egress IP if live."""
    srv = u
    cfg = build_cfg(srv)
    cfg["inbounds"][0]["port"] = port
    cfgpath = f"/tmp/dastyab_buildpool_{port}.json"
    with open(cfgpath, "w") as fh:
        json.dump(cfg, fh)
    proc = None
    try:
        proc = subprocess.Popen([find_xray(), "run", "-c", cfgpath],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
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
        r = subprocess.run(["curl", "-s", "--max-time", "12",
                            "--socks5-hostname", f"127.0.0.1:{port}",
                            "https://api.ipify.org"],
                           capture_output=True, text=True)
        out = (r.stdout or "").strip()
        return out if out and "curl" not in out.lower() and len(out) < 60 else None
    finally:
        if proc is not None:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except subprocess.SubprocessError:
                pass

USAGE = """[aistudio] Usage:
  dastyab aistudio start [pool index]     start the tunnel + open AI Studio
  dastyab aistudio watch [interval secs]  keep the tunnel alive, rotating nodes
  dastyab aistudio verify                 probe every node in the pool now
  dastyab aistudio stop                   stop the tunnel
  dastyab aistudio build-pool <uris.txt>  rebuild the pool from server cards"""

def _want_int(value, default, name):
    """Parse an optional integer argument, or explain what was expected."""
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        raise SystemExit(f"[aistudio] {name} must be a whole number, got {value!r}.\n{USAGE}")

if __name__=="__main__":
    cmd = sys.argv[1] if len(sys.argv)>1 else "start"
    cmd = cmd.strip().lower()
    if cmd in ("start", "on", "up"):
        idx = _want_int(sys.argv[2] if len(sys.argv)>2 else None, 0, "Pool index")
        ok, _ = start(idx)
        # Exit non-zero when egress never came up, so the CLI (and any script
        # wrapping it) can tell a working tunnel from a listening-but-dead one.
        sys.exit(0 if ok else 3)
    elif cmd=="stop":
        stop()
    elif cmd in ("verify", "status"):
        verify()
    elif cmd=="watch":
        interval = _want_int(sys.argv[2] if len(sys.argv)>2 else None, 20, "Watch interval")
        watch(interval=interval)
    elif cmd=="build-pool":
        # A missing argument used to reach pop_pool() and die with an
        # IndexError traceback, which the CLI then reported as a bare
        # "IndexError: list index out of range". Say what is actually wrong.
        if len(sys.argv) < 3:
            raise SystemExit(
                f"[aistudio] build-pool needs a file of vless:// cards.\n{USAGE}"
            )
        pop_pool(sys.argv[2])
    elif cmd in ("help", "--help", "-h"):
        print(__doc__)
    else:
        # An unknown action used to print the docstring and exit 0, so a typo
        # like `dastyab aistudio strat` looked like success.
        raise SystemExit(f"[aistudio] Unknown action: {cmd}\n{USAGE}")
