"""dastyab_android_shim — Linux compatibility & pure-Python net/proxy bridge for Android CPython.

Allows Linux `dastyab-aistudio.py` and `dastyab-flow.py` algorithms to run cleanly inside
embedded CPython 3.11 on non-rooted Android devices without requiring `/usr/bin/curl`,
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

_ACTIVE_PROXY_TARGET = None  # 4-tuple: (scheme|"chain", host|hops_list, port, label)
_LAST_HTTP_META = {"final_url": "", "code": 0, "headers": {}}


def get_last_final_url():
    return _LAST_HTTP_META.get("final_url", "")


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


def _read_route_doc():
    route_file = os.path.join(profile_dir(), "android_route.json")
    if os.path.isfile(route_file):
        try:
            with open(route_file) as fh:
                doc = json.load(fh)
            if isinstance(doc, dict):
                return doc
        except Exception:
            pass
    return {}


def _normalize_hop(raw):
    if not isinstance(raw, dict):
        return None
    scheme = str(raw.get("scheme", "")).lower().strip()
    host = str(raw.get("host", "")).strip()
    try:
        port = int(raw.get("port", 0))
    except (TypeError, ValueError):
        return None
    if scheme not in ("http", "socks5") or not host or not (1 <= port <= 65535):
        return None
    return (
        scheme,
        host,
        port,
        str(raw.get("username", "") or ""),
        str(raw.get("password", "") or ""),
    )


def _format_hop_label(hop):
    return f"{hop[0].upper()} {hop[1]}:{hop[2]}"


def detect_android_proxies(target_service=None):
    """Return list of currently listening local proxy dicts: {label, scheme, host, port}."""
    found = []
    doc = _read_route_doc()
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


def _socks5_connect(
    proxy_host,
    proxy_port,
    target_host,
    target_port,
    timeout,
    username="",
    password="",
    base_sock=None,
):
    """RFC 1928 (+ RFC 1929 USER/PASS) SOCKS5 TCP connect with ATYP=0x03."""
    sock = base_sock if base_sock is not None else socket.create_connection((proxy_host, int(proxy_port)), timeout=timeout)
    sock.settimeout(timeout)
    try:
        has_auth = bool(username or password)
        if has_auth:
            sock.sendall(b"\x05\x02\x00\x02")
        else:
            sock.sendall(b"\x05\x01\x00")
        resp = _recv_exact(sock, 2)
        if resp[0] != 5 or resp[1] not in (0, 2):
            raise OSError(f"SOCKS5 greeting rejected: {resp!r}")
        if resp[1] == 2:
            if not has_auth:
                raise OSError("SOCKS5 proxy requires authentication")
            ub = username.encode("utf-8")[:255]
            pb = password.encode("utf-8")[:255]
            sock.sendall(b"\x01" + bytes([len(ub)]) + ub + bytes([len(pb)]) + pb)
            aresp = _recv_exact(sock, 2)
            if aresp[1] != 0:
                raise OSError(f"SOCKS5 auth failed: {aresp!r}")

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
        try:
            sock.close()
        except OSError:
            pass
        raise


def _http_proxy_connect(
    proxy_host,
    proxy_port,
    target_host,
    target_port,
    timeout,
    username="",
    password="",
    base_sock=None,
):
    """Establish HTTP CONNECT tunnel through an HTTP proxy (optionally over an existing tunnel socket)."""
    import base64

    sock = base_sock if base_sock is not None else socket.create_connection((proxy_host, int(proxy_port)), timeout=timeout)
    sock.settimeout(timeout)
    try:
        auth_hdr = ""
        if username or password:
            token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
            auth_hdr = f"Proxy-Authorization: Basic {token}\r\n"
        req = (
            f"CONNECT {target_host}:{int(target_port)} HTTP/1.1\r\n"
            f"Host: {target_host}:{int(target_port)}\r\n"
            f"{auth_hdr}"
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
        try:
            sock.close()
        except OSError:
            pass
        raise


def _open_proxy_chain(hops, target_host, target_port, timeout):
    """Dial an arbitrary N-hop chain `[hop0 -> hop1 -> ... -> target_host:target_port]`."""
    if not hops:
        sock = socket.create_connection((target_host, int(target_port)), timeout=timeout)
        sock.settimeout(timeout)
        return sock

    first = hops[0]
    sock = socket.create_connection((first[1], int(first[2])), timeout=timeout)
    sock.settimeout(timeout)
    try:
        for i, hop in enumerate(hops):
            h_scheme, h_host, h_port, h_user, h_pass = hop
            next_host, next_port = (hops[i + 1][1], hops[i + 1][2]) if i + 1 < len(hops) else (target_host, target_port)
            if h_scheme == "socks5":
                sock = _socks5_connect(h_host, h_port, next_host, next_port, timeout, h_user, h_pass, base_sock=sock)
            elif h_scheme == "http":
                sock = _http_proxy_connect(h_host, h_port, next_host, next_port, timeout, h_user, h_pass, base_sock=sock)
            else:
                raise OSError(f"Unsupported chain hop scheme: {h_scheme}")
        return sock
    except Exception:
        try:
            sock.close()
        except OSError:
            pass
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


def _http_request_via_proxy(
    url,
    proxy_scheme,
    proxy_host,
    proxy_port,
    timeout=15,
    follow_redirects=False,
    _hops_remaining=6,
):
    """Execute HTTP/HTTPS request via SOCKS5, HTTP CONNECT, multi-hop chain, or Direct/OS VPN."""
    global _LAST_HTTP_META
    parsed = urllib.parse.urlparse(url)
    scheme = parsed.scheme.lower()
    host = parsed.hostname
    if not host:
        raise ValueError(f"Invalid URL: {url}")
    port = parsed.port or (443 if scheme == "https" else 80)
    path = parsed.path or "/"
    if parsed.query:
        path = f"{path}?{parsed.query}"

    if proxy_scheme == "chain":
        # proxy_host carries list of normalized hop tuples: (scheme, host, port, user, pass)
        raw_sock = _open_proxy_chain(proxy_host, host, port, timeout)
    elif proxy_scheme == "socks5":
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
            f"User-Agent: Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36\r\n"
            f"Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n"
            f"Accept-Language: en-US,en;q=0.9\r\n"
            f"Accept-Encoding: identity\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("ascii")
        conn.sendall(req)

        chunks = []
        max_bytes = 256 * 1024  # enough for IP JSON & Flow/AI Studio HTML markers
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
        _LAST_HTTP_META = {"final_url": url, "code": 0, "headers": {}}
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

    _LAST_HTTP_META = {"final_url": url, "code": code, "headers": headers}

    if follow_redirects and _hops_remaining > 0 and code in (301, 302, 303, 307, 308) and headers.get("location"):
        loc = urllib.parse.urljoin(url, headers["location"])
        return _http_request_via_proxy(
            loc,
            proxy_scheme,
            proxy_host,
            proxy_port,
            timeout=timeout,
            follow_redirects=True,
            _hops_remaining=_hops_remaining - 1,
        )

    return code, headers, body_bytes.decode("utf-8", "replace")


class CurlResult:
    def __init__(self, returncode, stdout="", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def android_curl(args, timeout=20, default_socks_port=10860):
    """Pure-Python drop-in for `curl -s [-L] --max-time N --socks5-hostname host:port ...`."""
    proxy_scheme = "socks5"
    proxy_host = "127.0.0.1"
    proxy_port = default_socks_port
    write_fmt = None
    out_dev_null = False
    follow_redirects = False
    url = None

    i = 0
    while i < len(args):
        a = args[i]
        if a in ("-s", "--silent"):
            i += 1
        elif a in ("-L", "--location"):
            follow_redirects = True
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

    # Determine actual route if override is active (including multi-hop chains!)
    if _ACTIVE_PROXY_TARGET is not None and (proxy_host == "127.0.0.1" and proxy_port == default_socks_port):
        proxy_scheme, proxy_host, proxy_port, _ = _ACTIVE_PROXY_TARGET
    elif not tcp_probe(proxy_host, proxy_port, 0.15):
        proxies = detect_android_proxies()
        if proxies:
            proxy_scheme = proxies[0]["scheme"]
            proxy_host = proxies[0]["host"]
            proxy_port = proxies[0]["port"]
        else:
            return CurlResult(7, "000" if write_fmt == "%{http_code}" else "", "proxy port not listening")

    try:
        code, headers, body = _http_request_via_proxy(
            url,
            proxy_scheme,
            proxy_host,
            proxy_port,
            timeout=timeout,
            follow_redirects=follow_redirects,
        )
        # If single-hop proxy target looks like HTTP rather than SOCKS5, retry as HTTP proxy
        if code == 0 and proxy_scheme == "socks5":
            try:
                code, headers, body = _http_request_via_proxy(
                    url,
                    "http",
                    proxy_host,
                    proxy_port,
                    timeout=timeout,
                    follow_redirects=follow_redirects,
                )
            except Exception:
                pass
        if write_fmt == "%{http_code}":
            return CurlResult(0, f"{code:03d}" if code > 0 else "000", "")
        if write_fmt == "%{url_effective}":
            return CurlResult(0, get_last_final_url() or url, "")
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
    """Set module override (`(scheme|\"chain\", host|hops_list, port, label)` or `None`)."""
    global _ACTIVE_PROXY_TARGET
    _ACTIVE_PROXY_TARGET = target


def _chain_to_route_tuple(hops, label_prefix="Chain"):
    if len(hops) == 1 and not hops[0][3] and not hops[0][4]:
        h = hops[0]
        return (h[0], h[1], h[2], f"{label_prefix} ({_format_hop_label(h)})")
    label = f"{label_prefix} {len(hops)}-Hop (" + " -> ".join(_format_hop_label(h) for h in hops) + ")"
    return ("chain", list(hops), 0, label)


def discover_best_android_route(probe_gates_fn, want_country=None, target_service=None, max_candidates=18):
    """
    Probe Layer 0 (External Lantern VPN), configured service-specific chains, local proxies,
    and 2-/3-hop cascades via `probe_gates_fn`, keeping AI Studio and Flow routes independent.
    """
    doc = _read_route_doc()
    ext_vpn = bool(doc.get("external_vpn_active"))
    dastyab_vpn = bool(doc.get("dastyab_vpn_active"))
    sys_label = (
        "External System VPN (Lantern / Android VPN)"
        if ext_vpn
        else ("DastYab VpnService" if dastyab_vpn else "Device Network / System VPN")
    )

    candidates = []
    seen_keys = set()

    def add_candidate(route_tuple):
        if len(candidates) >= max_candidates:
            return
        scheme, target, port, _ = route_tuple
        if scheme == "chain":
            key = "chain:" + "->".join(f"{h[0]}:{h[1]}:{h[2]}" for h in target)
        elif scheme == "direct":
            key = "direct"
        else:
            key = f"{scheme}:{target}:{port}"
        if key not in seen_keys:
            seen_keys.add(key)
            candidates.append(route_tuple)

    # 1. Service-specific configured chain (strictly separated for aistudio vs flow)
    svc_key = "flow_chain" if target_service == "flow" else ("aistudio_chain" if target_service == "aistudio" else None)
    if svc_key:
        svc_hops = [h for h in (_normalize_hop(x) for x in doc.get(svc_key, [])) if h]
        if svc_hops:
            add_candidate(_chain_to_route_tuple(svc_hops, f"{target_service.upper()} Saved"))
    global_hops = [h for h in (_normalize_hop(x) for x in doc.get("upstream_chain", [])) if h]
    if global_hops:
        add_candidate(_chain_to_route_tuple(global_hops, "Saved Chain"))

    # 2. Layer 0: Direct / External Lantern VPN
    add_candidate(("direct", "", 0, sys_label))

    # 3. Single-hop local listening proxies
    local_hops = []
    for p in detect_android_proxies(target_service=target_service):
        add_candidate((p["scheme"], p["host"], p["port"], p["label"]))
        local_hops.append((p["scheme"], p["host"], p["port"], "", ""))

    # 4. Alive harvested external SOCKS5/HTTP hops (synced from Android ConfigHarvester)
    raw_harvested = [h for h in (_normalize_hop(x) for x in doc.get("proxy_hops", [])) if h]
    alive_harvested = [h for h in raw_harvested[:16] if tcp_probe(h[1], h[2], 0.6)]
    for h in alive_harvested[:6]:
        add_candidate(_chain_to_route_tuple([h], "Harvested"))

    # 5. Two-hop cascades (local_proxy -> local_proxy, local_proxy -> harvested, harvested -> harvested)
    for a in local_hops[:3]:
        for b in local_hops[:3]:
            if (a[1], a[2]) != (b[1], b[2]):
                add_candidate(_chain_to_route_tuple([a, b], "2-Hop"))
        for b in alive_harvested[:4]:
            add_candidate(_chain_to_route_tuple([a, b], "2-Hop"))
    for a in alive_harvested[:3]:
        for b in alive_harvested[:3]:
            if (a[1], a[2]) != (b[1], b[2]):
                add_candidate(_chain_to_route_tuple([a, b], "2-Hop"))

    # 6. Three-hop cascades for stubborn geo-blocks (e.g. Google Flow on top of Lantern)
    for a in local_hops[:2]:
        for b in alive_harvested[:3]:
            for c in alive_harvested[:3]:
                if len({(a[1], a[2]), (b[1], b[2]), (c[1], c[2])}) == 3:
                    add_candidate(_chain_to_route_tuple([a, b, c], "3-Hop"))

    results = []
    for route in candidates:
        set_active_proxy_override(route)
        try:
            g = probe_gates_fn(want_country=want_country)
        finally:
            set_active_proxy_override(None)
        results.append((route, g))
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
