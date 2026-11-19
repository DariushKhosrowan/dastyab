"""dastyab_android_shim — Linux compatibility & pure-Python net/proxy bridge for Android CPython.

Allows Linux `dastyab-aistudio.py` and `dastyab-flow.py` algorithms to run cleanly inside
embedded CPython 3.12 on non-rooted Android devices without requiring `/usr/bin/curl`,
`/tmp` write access, or `/usr/bin/google-chrome`, while seamlessly using a real `xray`
binary whenever present (bundled, `~/.dastyab/bin/xray`, or Termux) OR falling back to
active Android local proxies (`v2rayNG`, `NekoBox`, `Hiddify`, `Clash`, `Orbot`, DastYab
LocalProxyServer) and system VPNs (Lantern).
"""
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse

# Event callback registered by Kotlin/Terminal shell to trigger Android UI actions
_EVENT_CALLBACK = None

KNOWN_ANDROID_PROXIES = (
    ("v2rayNG HTTP", "http", "127.0.0.1", 10809),
    ("v2rayNG SOCKS5", "socks5", "127.0.0.1", 10808),
    ("NekoBox SOCKS5", "socks5", "127.0.0.1", 2080),
    ("NekoBox HTTP", "http", "127.0.0.1", 2081),
    ("Hiddify Mixed", "http", "127.0.0.1", 12334),
    ("Clash / Mihomo", "http", "127.0.0.1", 7890),
    ("Orbot HTTP", "http", "127.0.0.1", 8118),
    ("Orbot SOCKS5", "socks5", "127.0.0.1", 9050),
    ("DastYab Bridge", "http", "127.0.0.1", 10850),
)

_ACTIVE_PROXY_TARGET = None  # tuple: (scheme, host, port, label) or ("direct", "", 0, label)


def set_event_callback(cb):
    global _EVENT_CALLBACK
    _EVENT_CALLBACK = cb


def emit_event(kind, payload):
    """Emit structured event to Android host (callback and JSON marker line fallback)."""
    if _EVENT_CALLBACK is not None:
        try:
            _EVENT_CALLBACK(kind, json.dumps(payload))
            return
        except Exception:
            pass
    print(f"[DASTYAB_EVENT] {kind} {json.dumps(payload)}", flush=True)


def is_android():
    return (
        os.environ.get("DASTYAB_ANDROID") == "1"
        or "ANDROID_ROOT" in os.environ
        or "ANDROID_DATA" in os.environ
        or os.path.isdir("/system/etc/security/cacerts")
    )


def tmp_dir():
    d = os.environ.get("TMPDIR") or os.environ.get("DASTYAB_TMPDIR") or "/tmp"
    os.makedirs(d, exist_ok=True)
    return d


def profile_dir():
    home = os.path.expanduser("~")
    d = os.path.join(home, ".dastyab", "profiles", "default")
    os.makedirs(d, exist_ok=True)
    return d


def make_ssl_context(verify=True):
    ctx = ssl.create_default_context()
    if not verify:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    # Android 14+ stores system CAs in Conscrypt APEX; Android 7-13 uses /system/etc/security/cacerts
    for capath in ("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts"):
        if os.path.isdir(capath):
            try:
                ctx.load_verify_locations(capath=capath)
            except Exception:
                pass
    try:
        import certifi  # optional if bundled
        ctx.load_verify_locations(cafile=certifi.where())
    except Exception:
        pass
    return ctx


def tcp_probe(host, port, timeout=0.25):
    s = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(timeout)
        s.connect((host, int(port)))
        return True
    except OSError:
        return False
    finally:
        if s is not None:
            try:
                s.close()
            except OSError:
                pass


def detect_android_proxies():
    """Return list of currently listening local proxy dicts: {label, scheme, host, port}."""
    found = []
    # Respect Android route state exported by Kotlin side if present
    route_file = os.path.join(profile_dir(), "android_route.json")
    if os.path.isfile(route_file):
        try:
            with open(route_file) as fh:
                doc = json.load(fh)
            bport = doc.get("bridge_port")
            if isinstance(bport, int) and bport > 0 and tcp_probe("127.0.0.1", bport, 0.2):
                found.append({
                    "label": f"DastYab Bridge (:{bport})",
                    "scheme": "http",
                    "host": "127.0.0.1",
                    "port": bport,
                })
            uhost = doc.get("upstream_host")
            uport = doc.get("upstream_port")
            uscheme = (doc.get("upstream_scheme") or "http").lower()
            if uhost and isinstance(uport, int) and uport > 0 and tcp_probe(uhost, uport, 0.2):
                found.append({
                    "label": f"Configured Upstream ({uscheme.upper()} {uhost}:{uport})",
                    "scheme": uscheme if uscheme in ("http", "socks5") else "http",
                    "host": uhost,
                    "port": uport,
                })
        except Exception:
            pass

    seen = {(x["host"], x["port"]) for x in found}
    for label, scheme, host, port in KNOWN_ANDROID_PROXIES:
        if (host, port) in seen:
            continue
        if tcp_probe(host, port, 0.15):
            found.append({"label": label, "scheme": scheme, "host": host, "port": port})
            seen.add((host, port))
    return found


def find_xray_android(extra_paths=()):
    """Locate a real xray/v2ray ELF binary on Android or Linux, or return None."""
    home = os.path.expanduser("~")
    native_dir = os.environ.get("DASTYAB_NATIVE_DIR", "")
    cands = [
        os.environ.get("DASTYAB_XRAY", ""),
        os.path.join(native_dir, "libxray.so") if native_dir else "",
        os.path.join(home, ".dastyab/bin/xray"),
        os.path.join(home, "bin/xray"),
        "/data/data/com.termux/files/usr/bin/xray",
        "/data/data/com.termux/files/usr/bin/v2ray",
        "/usr/local/bin/xray",
        "/usr/bin/xray",
        "/opt/xray/xray",
        "/tmp/xraybin/xray",
        "/opt/v2rayN/bin/xray/xray",
    ] + list(extra_paths)

    for c in cands:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            if _is_real_xray_binary(c):
                return c
    for name in ("xray", "v2ray"):
        for d in os.environ.get("PATH", "").split(os.pathsep):
            p = os.path.join(d, name) if d else ""
            if p and os.path.isfile(p) and os.access(p, os.X_OK):
                if _is_real_xray_binary(p):
                    return p
    return None


def _is_real_xray_binary(path):
    try:
        out = subprocess.run([path, "version"], capture_output=True, text=True, timeout=6).stdout
    except (OSError, subprocess.SubprocessError):
        return False
    if "NekoRay" in out or "NekoBox" in out:
        return False
    return bool(re.search(r"(Xray|V2Ray)\s+\d+\.\d+", out))


def _socks5_connect(proxy_host, proxy_port, target_host, target_port, timeout):
    """RFC 1928 SOCKS5 NOAUTH TCP connect with ATYP=0x03 (remote DNS, --socks5-hostname)."""
    sock = socket.create_connection((proxy_host, int(proxy_port)), timeout=timeout)
    sock.settimeout(timeout)
    try:
        # Greeting: VER=5, NMETHODS=1, METHOD=0x00 (NO AUTH)
        sock.sendall(b"\x05\x01\x00")
        resp = _recv_exact(sock, 2)
        if resp != b"\x05\x00":
            raise OSError(f"SOCKS5 greeting rejected: {resp!r}")

        host_bytes = target_host.encode("utf-8")
        if len(host_bytes) > 255:
            raise OSError("Hostname too long for SOCKS5")
        req = (
            b"\x05\x01\x00\x03"
            + bytes([len(host_bytes)])
            + host_bytes
            + int(target_port).to_bytes(2, "big")
        )
        sock.sendall(req)
        hdr = _recv_exact(sock, 4)
        if hdr[0] != 5 or hdr[1] != 0:
            raise OSError(f"SOCKS5 CONNECT failed (REP={hdr[1]})")
        atyp = hdr[3]
        if atyp == 1:
            _recv_exact(sock, 4 + 2)
        elif atyp == 3:
            alen = _recv_exact(sock, 1)[0]
            _recv_exact(sock, alen + 2)
        elif atyp == 4:
            _recv_exact(sock, 16 + 2)
        else:
            raise OSError(f"SOCKS5 unknown ATYP={atyp}")
        return sock
    except Exception:
        sock.close()
        raise


def _http_proxy_connect(proxy_host, proxy_port, target_host, target_port, timeout):
    """Establish HTTP CONNECT tunnel through an HTTP proxy (v2rayNG :10809, DastYab Bridge)."""
    sock = socket.create_connection((proxy_host, int(proxy_port)), timeout=timeout)
    sock.settimeout(timeout)
    try:
        req = (
            f"CONNECT {target_host}:{int(target_port)} HTTP/1.1\r\n"
            f"Host: {target_host}:{int(target_port)}\r\n"
            f"Proxy-Connection: keep-alive\r\n\r\n"
        ).encode("ascii")
        sock.sendall(req)
        buf = bytearray()
        while b"\r\n\r\n" not in buf and len(buf) < 8192:
            chunk = sock.recv(1024)
            if not chunk:
                break
            buf.extend(chunk)
        status_line = buf.split(b"\r\n", 1)[0].decode("latin1", "replace")
        if " 200 " not in status_line and not status_line.endswith(" 200"):
            raise OSError(f"HTTP CONNECT rejected: {status_line}")
        return sock
    except Exception:
        sock.close()
        raise


def _recv_exact(sock, n):
    out = bytearray()
    while len(out) < n:
        chunk = sock.recv(n - len(out))
        if not chunk:
            raise OSError("Unexpected EOF on socket")
        out.extend(chunk)
    return bytes(out)


def _decode_chunked(data):
    out = bytearray()
    pos = 0
    total = len(data)
    while pos < total:
        line_end = data.find(b"\r\n", pos)
        if line_end < 0:
            break
        size_hex = data[pos:line_end].split(b";", 1)[0].strip()
        if not size_hex:
            pos = line_end + 2
            continue
        chunk_len = int(size_hex, 16)
        pos = line_end + 2
        if chunk_len == 0:
            break
        out.extend(data[pos:pos + chunk_len])
        pos += chunk_len + 2
    return bytes(out)


def _http_request_via_proxy(url, proxy_scheme, proxy_host, proxy_port, timeout=15, follow_redirects=False):
    """Execute one HTTP/HTTPS request via SOCKS5, HTTP CONNECT, or Direct/OS VPN."""
    parsed = urllib.parse.urlparse(url)
    scheme = parsed.scheme.lower()
    host = parsed.hostname
    if not host:
        raise ValueError(f"Invalid URL: {url}")
    port = parsed.port or (443 if scheme == "https" else 80)
    path = parsed.path or "/"
    if parsed.query:
        path = f"{path}?{parsed.query}"

    if proxy_scheme == "socks5":
        raw_sock = _socks5_connect(proxy_host, proxy_port, host, port, timeout)
    elif proxy_scheme == "http":
        if scheme == "https":
            raw_sock = _http_proxy_connect(proxy_host, proxy_port, host, port, timeout)
        else:
            raw_sock = socket.create_connection((proxy_host, int(proxy_port)), timeout=timeout)
            raw_sock.settimeout(timeout)
            path = url  # full URL for plain HTTP via HTTP proxy
    else:
        raw_sock = socket.create_connection((host, int(port)), timeout=timeout)
        raw_sock.settimeout(timeout)

    conn = raw_sock
    try:
        if scheme == "https":
            ctx = make_ssl_context(verify=True)
            conn = ctx.wrap_socket(raw_sock, server_hostname=host)
            conn.settimeout(timeout)

        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}\r\n"
            f"User-Agent: curl/8.5.0 DastYab-Android/3.2\r\n"
            f"Accept: */*\r\n"
            f"Accept-Encoding: identity\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("ascii")
        conn.sendall(req)

        chunks = []
        max_bytes = 256 * 1024  # enough for IP JSON & Flow HTML markers
        read_total = 0
        while read_total < max_bytes:
            try:
                data = conn.recv(8192)
            except socket.timeout:
                break
            if not data:
                break
            chunks.append(data)
            read_total += len(data)
        raw = b"".join(chunks)
    finally:
        try:
            conn.close()
        except OSError:
            pass

    sep = raw.find(b"\r\n\r\n")
    if sep < 0:
        return 0, {}, ""
    hdr_bytes = raw[:sep]
    body_bytes = raw[sep + 4:]
    lines = hdr_bytes.decode("latin1", "replace").split("\r\n")
    status_line = lines[0] if lines else ""
    m = re.search(r"HTTP/\d(?:\.\d)?\s+(\d{3})", status_line)
    code = int(m.group(1)) if m else 0
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()

    if "chunked" in headers.get("transfer-encoding", "").lower():
        try:
            body_bytes = _decode_chunked(body_bytes)
        except Exception:
            pass

    if follow_redirects and code in (301, 302, 303, 307, 308) and headers.get("location"):
        loc = urllib.parse.urljoin(url, headers["location"])
        return _http_request_via_proxy(
            loc, proxy_scheme, proxy_host, proxy_port, timeout=timeout, follow_redirects=False
        )

    return code, headers, body_bytes.decode("utf-8", "replace")


class CurlResult:
    def __init__(self, returncode, stdout="", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def android_curl(args, timeout=20, default_socks_port=10860):
    """Pure-Python drop-in for `curl -s --max-time N --socks5-hostname host:port ...`."""
    proxy_scheme = "socks5"
    proxy_host = "127.0.0.1"
    proxy_port = default_socks_port
    write_fmt = None
    out_dev_null = False
    url = None

    i = 0
    while i < len(args):
        a = args[i]
        if a in ("-s", "-L", "--silent"):
            i += 1
        elif a == "--max-time" and i + 1 < len(args):
            try:
                timeout = max(2, int(float(args[i + 1])))
            except ValueError:
                pass
            i += 2
        elif a == "--socks5-hostname" and i + 1 < len(args):
            hp = args[i + 1]
            if ":" in hp:
                h, p = hp.rsplit(":", 1)
                proxy_host = h
                proxy_port = int(p)
            i += 2
        elif a == "-o" and i + 1 < len(args):
            out_dev_null = args[i + 1] == "/dev/null"
            i += 2
        elif a == "-w" and i + 1 < len(args):
            write_fmt = args[i + 1]
            i += 2
        elif a.startswith("http://") or a.startswith("https://"):
            url = a
            i += 1
        else:
            i += 1

    if not url:
        return CurlResult(2, "", "no URL provided")

    # Determine actual route if override is active (e.g. Android HTTP proxy or External VPN)
    if _ACTIVE_PROXY_TARGET is not None and (proxy_host == "127.0.0.1" and proxy_port == default_socks_port):
        proxy_scheme, proxy_host, proxy_port, _ = _ACTIVE_PROXY_TARGET
    elif not tcp_probe(proxy_host, proxy_port, 0.15):
        # If explicit local SOCKS port isn't open, check active Android proxy preset
        proxies = detect_android_proxies()
        if proxies:
            proxy_scheme = proxies[0]["scheme"]
            proxy_host = proxies[0]["host"]
            proxy_port = proxies[0]["port"]
        else:
            return CurlResult(7, "000" if write_fmt == "%{http_code}" else "", "proxy port not listening")

    try:
        code, headers, body = _http_request_via_proxy(
            url, proxy_scheme, proxy_host, proxy_port, timeout=timeout, follow_redirects=False
        )
        # If proxy target looks like HTTP (e.g. mixed/HTTP proxy on same port), retry as HTTP proxy
        if code == 0 and proxy_scheme == "socks5":
            try:
                code, headers, body = _http_request_via_proxy(
                    url, "http", proxy_host, proxy_port, timeout=timeout, follow_redirects=False
                )
            except Exception:
                pass
        if write_fmt == "%{http_code}":
            return CurlResult(0, f"{code:03d}" if code > 0 else "000", "")
        if out_dev_null:
            return CurlResult(0, "", "")
        return CurlResult(0 if code > 0 else 52, body, "")
    except Exception as exc:
        if write_fmt == "%{http_code}":
            return CurlResult(28, "000", str(exc))
        return CurlResult(28, "", str(exc))


def probe_vless_node_transport(srv, timeout=2.5):
    """Real TCP (+ TLS SNI) handshake latency probe for a VLESS/Trojan/VMess/SS server card."""
    host = srv.get("addr", "")
    port = int(srv.get("port", 443))
    if not host or port <= 0:
        return None
    q = srv.get("q", {}) or {}
    sec = q.get("security", "none").lower()
    sni = q.get("sni") or q.get("host") or host
    t0 = time.monotonic()
    s = None
    try:
        s = socket.create_connection((host, port), timeout=timeout)
        s.settimeout(timeout)
        if sec == "tls":
            ctx = make_ssl_context(verify=False)  # CDN edge cert matches SNI; check TLS handshake alive
            ssock = ctx.wrap_socket(s, server_hostname=sni)
            ssock.close()
        else:
            s.close()
        return max(1, int((time.monotonic() - t0) * 1000))
    except Exception:
        if s is not None:
            try:
                s.close()
            except OSError:
                pass
        return None


def set_active_proxy_override(target):
    """Set module override (`(scheme, host, port, label)` or `None`)."""
    global _ACTIVE_PROXY_TARGET
    _ACTIVE_PROXY_TARGET = target


def discover_best_android_route(probe_gates_fn, want_country=None):
    """Probe active Android proxy apps + active system VPN (e.g. Lantern) via `probe_gates_fn`."""
    candidates = []
    for p in detect_android_proxies():
        candidates.append((p["scheme"], p["host"], p["port"], p["label"]))
    # Always include active system network/VPN (e.g. Lantern or DastYab VpnService) as a route candidate
    route_info_path = os.path.join(profile_dir(), "android_route.json")
    ext_vpn = False
    dastyab_vpn = False
    if os.path.isfile(route_info_path):
        try:
            with open(route_info_path) as fh:
                doc = json.load(fh)
            ext_vpn = bool(doc.get("external_vpn_active"))
            dastyab_vpn = bool(doc.get("dastyab_vpn_active"))
        except Exception:
            pass
    sys_label = (
        "External System VPN (Lantern / Android VPN)"
        if ext_vpn
        else ("DastYab VpnService" if dastyab_vpn else "Device Network / System VPN")
    )
    candidates.append(("direct", "", 0, sys_label))

    results = []
    for scheme, host, port, label in candidates:
        set_active_proxy_override((scheme, host, port, label))
        try:
            g = probe_gates_fn(want_country=want_country)
        finally:
            set_active_proxy_override(None)
        results.append(((scheme, host, port, label), g))
    return results


def vless_dict_to_uri(srv):
    """Reconstruct a canonical `vless://` share URI from a Server dict."""
    if not isinstance(srv, dict) or srv.get("proto", "vless") != "vless":
        return None
    uuid = srv.get("uuid")
    addr = srv.get("addr")
    port = srv.get("port")
    if not uuid or not addr or not port:
        return None
    q = srv.get("q", {}) or {}
    qs = urllib.parse.urlencode([(k, v) for k, v in q.items() if v is not None and v != ""])
    name = urllib.parse.quote(str(srv.get("name") or f"dastyab-{addr}"), safe="")
    return f"vless://{uuid}@{addr}:{int(port)}?{qs}#{name}"
