"""dastyab_pip — Pure-Python runtime package manager (`pip`) for DastYab Android Terminal Mode.

Supports installing, upgrading, inspecting, verifying, and removing pure-Python
packages (`*-py3-none-any.whl` / `*-py2.py3-none-any.whl`) at runtime into
`~/.dastyab/site-packages`, using DastYab's active proxy/VPN route via
`dastyab_android_shim` (Lantern, v2rayNG, NekoBox, DastYab Bridge, or direct TLS).

Usage inside Terminal Mode:
  pip list
  pip check
  pip show <package>
  pip install <pkg> [<pkg2>...]
  pip install --recommended
  pip install -r <requirements.txt>
  pip uninstall [-y] <pkg>
"""
import importlib
import importlib.metadata
import io
import json
import os
import re
import shutil
import socket
import ssl
import sys
import urllib.parse
import zipfile

import dastyab_android_shim as _shim

# Curated pure-Python packages useful for Linux DastYab scripts, HTTP/SOCKS, DNS & HTML parsing.
RECOMMENDED_PACKAGES = [
    ("certifi", "certifi", "Mozilla CA root certificates"),
    ("charset-normalizer", "charset_normalizer<3,>=2", "Pure-Python HTTP charset detection"),
    ("idna", "idna", "Internationalized domain names"),
    ("urllib3", "urllib3", "HTTP connection pool library"),
    ("requests", "requests", "Linux-standard HTTP library"),
    ("PySocks", "PySocks", "SOCKS4/SOCKS5/HTTP proxy sockets"),
    ("dnspython", "dnspython", "DNS & DoH resolver toolkit"),
    ("soupsieve", "soupsieve", "CSS selector engine"),
    ("beautifulsoup4", "bs4", "HTML/XML parser (Telegram channel scraper)"),
    ("PyYAML", "yaml", "YAML config parser"),
    ("six", "six", "Python compatibility helpers"),
    ("typing-extensions", "typing_extensions", "Backported typing utilities"),
]

# Mapping PyPI distribution name -> primary importable module name for `pip check`
RECOMMENDED_IMPORTS = {
    "certifi": "certifi",
    "charset-normalizer": "charset_normalizer",
    "idna": "idna",
    "urllib3": "urllib3",
    "requests": "requests",
    "PySocks": "socks",
    "dnspython": "dns",
    "soupsieve": "soupsieve",
    "beautifulsoup4": "bs4",
    "PyYAML": "yaml",
    "six": "six",
    "typing-extensions": "typing_extensions",
}


def site_packages_dir():
    home = os.path.expanduser("~")
    d = os.path.join(home, ".dastyab", "site-packages")
    os.makedirs(d, exist_ok=True)
    return d


def ensure_site_packages_on_path():
    sp = site_packages_dir()
    if sp not in sys.path:
        sys.path.insert(0, sp)
    importlib.invalidate_caches()
    return sp


def _canonical_name(name):
    return re.sub(r"[-_.]+", "-", (name or "").strip()).lower()


def _split_requirement(spec):
    """Parse `'requests>=2.28,<3'` -> `('requests', '>=2.28,<3')` cleanly."""
    s = (spec or "").strip()
    if not s or s.startswith("#"):
        return None, ""
    # Drop inline environment marker after ';' for caller inspection
    base = s.split(";", 1)[0].strip()
    # Strip extras `[socks]`
    base = re.sub(r"\[[^\]]*\]", "", base).strip()
    m = re.match(r"^([A-Za-z0-9][A-Za-z0-9._-]*)\s*(.*)$", base)
    if not m:
        return None, ""
    return m.group(1), m.group(2).strip()


def _parse_ver_tuple(v):
    nums = []
    for part in re.split(r"[.\-+]", str(v or "0")):
        m = re.match(r"^(\d+)", part)
        if m:
            nums.append(int(m.group(1)))
    while len(nums) < 3:
        nums.append(0)
    return tuple(nums[:4])


def _version_matches(version_str, constraint_str):
    if not constraint_str:
        return True
    v = _parse_ver_tuple(version_str)
    for clause in constraint_str.split(","):
        clause = clause.strip()
        if not clause:
            continue
        m = re.match(r"^(==|!=|>=|<=|>|<|~=)\s*([^\s]+)$", clause)
        if not m:
            continue
        op, raw_target = m.group(1), m.group(2)
        t = _parse_ver_tuple(raw_target)
        if op == "==" and v != t:
            return False
        if op == "!=" and v == t:
            return False
        if op == ">=" and v < t:
            return False
        if op == "<=" and v > t:
            return False
        if op == ">" and v <= t:
            return False
        if op == "<" and v >= t:
            return False
        if op == "~=" and (v < t or v[:1] != t[:1]):
            return False
    return True


def _marker_allows(raw_req):
    if ";" not in raw_req:
        return True
    marker = raw_req.split(";", 1)[1].strip()
    if "extra" in marker:
        return False
    # Check simple sys_platform / os_name exclusions
    m_plat = re.search(r"sys_platform\s*==\s*['\"]([^'\"]+)['\"]", marker)
    if m_plat and m_plat.group(1) not in ("linux", sys.platform):
        return False
    m_os = re.search(r"os_name\s*==\s*['\"]([^'\"]+)['\"]", marker)
    if m_os and m_os.group(1) != os.name:
        return False
    m_py_lt = re.search(r"python_version\s*<\s*['\"](\d+\.\d+)['\"]", marker)
    if m_py_lt:
        cur = (sys.version_info.major, sys.version_info.minor)
        req = _parse_ver_tuple(m_py_lt.group(1))[:2]
        if cur >= req:
            return False
    return True


def _pick_transport():
    """Choose best outbound route for PyPI: active override -> detected local proxy -> direct OS VPN."""
    if _shim._ACTIVE_PROXY_TARGET is not None:
        return _shim._ACTIVE_PROXY_TARGET[:3]
    proxies = _shim.detect_android_proxies()
    if proxies:
        p = proxies[0]
        return p["scheme"], p["host"], p["port"]
    return "direct", "", 0


def _http_get_bytes(url, timeout=25, max_bytes=25 * 1024 * 1024, redirects_left=5):
    """Stream HTTPS/HTTP GET bytes through active Android proxy or OS VPN."""
    parsed = urllib.parse.urlparse(url)
    scheme = parsed.scheme.lower()
    host = parsed.hostname
    if not host:
        raise ValueError(f"Invalid URL: {url}")
    port = parsed.port or (443 if scheme == "https" else 80)
    path = parsed.path or "/"
    if parsed.query:
        path = f"{path}?{parsed.query}"

    proxy_scheme, proxy_host, proxy_port = _pick_transport()
    if proxy_scheme == "socks5":
        raw_sock = _shim._socks5_connect(proxy_host, proxy_port, host, port, timeout)
    elif proxy_scheme == "http":
        if scheme == "https":
            raw_sock = _shim._http_proxy_connect(proxy_host, proxy_port, host, port, timeout)
        else:
            raw_sock = socket.create_connection((proxy_host, int(proxy_port)), timeout=timeout)
            raw_sock.settimeout(timeout)
            path = url
    else:
        raw_sock = socket.create_connection((host, int(port)), timeout=timeout)
        raw_sock.settimeout(timeout)

    conn = raw_sock
    try:
        if scheme == "https":
            ctx = _shim.make_ssl_context(verify=True)
            conn = ctx.wrap_socket(raw_sock, server_hostname=host)
            conn.settimeout(timeout)

        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}\r\n"
            f"User-Agent: dastyab-pip/1.0 Python/{sys.version_info.major}.{sys.version_info.minor}\r\n"
            f"Accept: application/json, application/octet-stream, */*\r\n"
            f"Accept-Encoding: identity\r\n"
            f"Connection: close\r\n\r\n"
        ).encode("ascii")
        conn.sendall(req)

        chunks = []
        total = 0
        while total < max_bytes:
            data = conn.recv(16384)
            if not data:
                break
            chunks.append(data)
            total += len(data)
        raw = b"".join(chunks)
    finally:
        try:
            conn.close()
        except OSError:
            pass

    sep = raw.find(b"\r\n\r\n")
    if sep < 0:
        raise OSError(f"Malformed HTTP response from {host}")
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
        body_bytes = _shim._decode_chunked(body_bytes)

    if code in (301, 302, 303, 307, 308) and headers.get("location"):
        if redirects_left <= 0:
            raise OSError("Too many HTTP redirects")
        loc = urllib.parse.urljoin(url, headers["location"])
        return _http_get_bytes(loc, timeout=timeout, max_bytes=max_bytes, redirects_left=redirects_left - 1)

    return code, headers, body_bytes


def _is_pure_python_wheel(file_info):
    if file_info.get("packagetype") != "bdist_wheel":
        return False
    if file_info.get("yanked"):
        return False
    fn = file_info.get("filename", "")
    # Pure Python wheels end in -none-any.whl (e.g. py3-none-any.whl or py2.py3-none-any.whl)
    return fn.endswith("-none-any.whl") and ("py3" in fn or "py2.py3" in fn)


def list_distributions():
    """Return sorted list of dicts `{name, version, source, location}`."""
    sp = os.path.realpath(site_packages_dir())
    seen = {}
    for dist in importlib.metadata.distributions():
        try:
            name = dist.metadata.get("Name") or dist.name
            version = dist.version or "?"
            loc = os.path.realpath(str(dist.locate_file("")))
            source = "user" if loc.startswith(sp) else "bundled"
            canon = _canonical_name(name)
            # Prefer user entry if shadowed in site-packages
            if canon not in seen or source == "user":
                seen[canon] = {
                    "name": name,
                    "version": version,
                    "source": source,
                    "location": loc,
                }
        except Exception:
            continue
    return sorted(seen.values(), key=lambda x: x["name"].lower())


def get_packages_summary():
    """Return structured JSON-serializable summary for Terminal UI Package Manager."""
    ensure_site_packages_on_path()
    dists = list_distributions()
    by_canon = {_canonical_name(d["name"]): d for d in dists}
    rec_status = []
    missing = []
    for pkg_name, _spec, desc in RECOMMENDED_PACKAGES:
        canon = _canonical_name(pkg_name)
        mod_name = RECOMMENDED_IMPORTS.get(pkg_name, pkg_name.replace("-", "_"))
        import_ok = False
        try:
            importlib.import_module(mod_name)
            import_ok = True
        except Exception:
            pass
        entry = by_canon.get(canon)
        installed = bool(entry or import_ok)
        if not installed:
            missing.append(pkg_name)
        rec_status.append({
            "name": pkg_name,
            "module": mod_name,
            "description": desc,
            "installed": installed,
            "version": entry["version"] if entry else ("builtin" if import_ok else ""),
            "source": entry["source"] if entry else ("bundled" if import_ok else "missing"),
        })
    return {
        "site_packages": site_packages_dir(),
        "total_installed": len(dists),
        "installed": dists,
        "recommended": rec_status,
        "missing_recommended": missing,
    }


def _select_release_and_wheel(meta_doc, constraint_str):
    """Select newest compatible version with a pure-Python wheel from PyPI metadata JSON."""
    releases = meta_doc.get("releases") or {}
    latest_ver = (meta_doc.get("info") or {}).get("version")
    latest_files = meta_doc.get("urls") or []

    # Fast path: no version constraint and latest release has pure-Python wheel
    if not constraint_str and latest_ver:
        for f in latest_files:
            if _is_pure_python_wheel(f):
                return latest_ver, f

    candidates = []
    for ver, files in releases.items():
        if not files:
            continue
        # Skip pre-releases unless constraint explicitly mentions them
        if re.search(r"[a-zA-Z]", ver) and not re.search(r"[a-zA-Z]", constraint_str or ""):
            continue
        if not _version_matches(ver, constraint_str):
            continue
        wheels = [f for f in files if _is_pure_python_wheel(f)]
        if wheels:
            candidates.append((_parse_ver_tuple(ver), ver, wheels[0]))

    if not candidates:
        return None, None
    candidates.sort(key=lambda item: item[0], reverse=True)
    _, best_ver, best_file = candidates[0]
    return best_ver, best_file


def _install_wheel_bytes(pkg_name, ver, whl_bytes, sp_dir):
    buf = io.BytesIO(whl_bytes)
    with zipfile.ZipFile(buf, "r") as zf:
        bad = zf.testzip()
        if bad is not None:
            raise OSError(f"Corrupt wheel ZIP entry: {bad}")
        real_sp = os.path.realpath(sp_dir)
        installed_files = []
        requires_dist = []
        for info in zf.infolist():
            if info.is_dir():
                continue
            rel = info.filename.lstrip("/")
            if ".." in rel.split("/"):
                continue
            target = os.path.realpath(os.path.join(real_sp, rel))
            if not target.startswith(real_sp + os.sep):
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            data = zf.read(info)
            with open(target, "wb") as out:
                out.write(data)
            installed_files.append(rel)
            if rel.endswith(".dist-info/METADATA"):
                for line in data.decode("utf-8", "replace").splitlines():
                    if line.startswith("Requires-Dist:"):
                        requires_dist.append(line.split(":", 1)[1].strip())
        # Record manifest for clean uninstall
        manifest_dir = os.path.join(real_sp, ".dastyab_manifests")
        os.makedirs(manifest_dir, exist_ok=True)
        with open(os.path.join(manifest_dir, f"{_canonical_name(pkg_name)}.json"), "w") as mf:
            json.dump({"name": pkg_name, "version": ver, "files": installed_files}, mf)
        return len(installed_files), requires_dist


def install_packages(specs, upgrade=False, no_deps=False):
    sp = ensure_site_packages_on_path()
    installed_map = {_canonical_name(d["name"]): d for d in list_distributions()}
    queue = list(specs)
    visited = set()
    failures = 0

    while queue:
        raw_spec = queue.pop(0).strip()
        if not raw_spec or raw_spec.startswith("#"):
            continue
        pkg_name, constraint = _split_requirement(raw_spec)
        if not pkg_name:
            print(f"[pip] Ignoring invalid requirement: {raw_spec}")
            continue
        canon = _canonical_name(pkg_name)
        if canon in visited:
            continue
        visited.add(canon)

        existing = installed_map.get(canon)
        if existing and not upgrade and _version_matches(existing["version"], constraint):
            print(f"[pip] Already satisfied: {existing['name']}=={existing['version']} ({existing['source']})")
            continue

        pypi_url = f"https://pypi.org/pypi/{urllib.parse.quote(pkg_name)}/json"
        print(f"[pip] Fetching metadata: {pkg_name}{constraint} …")
        try:
            code, _, body = _http_get_bytes(pypi_url, timeout=20, max_bytes=4 * 1024 * 1024)
        except Exception as e:
            print(f"[pip] ERROR contacting PyPI for {pkg_name}: {e}")
            print("      Hint: Turn on DastYab VPN, Lantern, or v2rayNG/NekoBox local proxy, then retry.")
            failures += 1
            continue

        if code == 404:
            print(f"[pip] ERROR: Package '{pkg_name}' not found on PyPI.")
            failures += 1
            continue
        if code != 200:
            print(f"[pip] ERROR: PyPI returned HTTP {code} for {pkg_name}.")
            failures += 1
            continue

        try:
            meta = json.loads(body.decode("utf-8", "replace"))
        except Exception as e:
            print(f"[pip] ERROR: Invalid PyPI JSON for {pkg_name}: {e}")
            failures += 1
            continue

        ver, wheel_info = _select_release_and_wheel(meta, constraint)
        if not wheel_info:
            print(
                f"[pip] ERROR: '{pkg_name}{constraint}' has no pure-Python universal wheel (*-none-any.whl) on PyPI.\n"
                "      On-device runtime pip supports pure-Python wheels; packages with C/Rust native extensions\n"
                "      (like numpy or cryptography) must be bundled in the APK build via Chaquopy."
            )
            failures += 1
            continue

        whl_url = wheel_info["url"]
        whl_name = wheel_info["filename"]
        print(f"[pip] Downloading {whl_name} …")
        try:
            wcode, _, whl_bytes = _http_get_bytes(whl_url, timeout=45, max_bytes=25 * 1024 * 1024)
            if wcode != 200 or not whl_bytes:
                raise OSError(f"HTTP {wcode}")
            nfiles, reqs = _install_wheel_bytes(pkg_name, ver, whl_bytes, sp)
            importlib.invalidate_caches()
            installed_map[canon] = {"name": pkg_name, "version": ver, "source": "user", "location": sp}
            print(f"[pip] Installed {pkg_name}=={ver} ({nfiles} file(s) -> {sp})")
            if not no_deps:
                meta_reqs = reqs or ((meta.get("info") or {}).get("requires_dist") or [])
                for req in meta_reqs:
                    if _marker_allows(req):
                        queue.append(req.split(";", 1)[0].strip())
        except Exception as e:
            print(f"[pip] ERROR installing {pkg_name}: {e}")
            failures += 1

    return 0 if failures == 0 else 1


def uninstall_packages(names):
    sp = ensure_site_packages_on_path()
    manifest_dir = os.path.join(sp, ".dastyab_manifests")
    rc = 0
    for raw in names:
        pkg_name, _ = _split_requirement(raw)
        if not pkg_name:
            continue
        canon = _canonical_name(pkg_name)
        mfile = os.path.join(manifest_dir, f"{canon}.json")
        if not os.path.isfile(mfile):
            dists = {_canonical_name(d["name"]): d for d in list_distributions()}
            if canon in dists and dists[canon]["source"] == "bundled":
                print(f"[pip] '{pkg_name}' is bundled inside the APK (read-only) and cannot be uninstalled.")
            else:
                print(f"[pip] WARNING: Skipping '{pkg_name}' as it is not user-installed.")
            rc = 1
            continue
        with open(mfile) as fh:
            doc = json.load(fh)
        removed = 0
        for rel in doc.get("files", []):
            fp = os.path.join(sp, rel)
            if os.path.isfile(fp):
                os.remove(fp)
                removed += 1
        os.remove(mfile)
        # Prune empty directories under site-packages
        for cur, dirs, files in os.walk(sp, topdown=False):
            if cur != sp and not dirs and not files:
                try:
                    os.rmdir(cur)
                except OSError:
                    pass
        importlib.invalidate_caches()
        print(f"[pip] Uninstalled {doc.get('name', pkg_name)} ({removed} file(s) removed)")
    return rc


def cmd_check():
    ensure_site_packages_on_path()
    print("[pip] Checking core & recommended Python modules for Terminal Mode:")
    core_mods = ["sys", "ssl", "json", "socket", "sqlite3", "urllib.request", "dastyab_flow", "dastyab_aistudio"]
    for mod in core_mods:
        try:
            importlib.import_module(mod)
            print(f"  OK       {mod} (core)")
        except Exception as e:
            print(f"  FAIL     {mod} ({e})")
    missing = []
    for pkg_name, _, desc in RECOMMENDED_PACKAGES:
        mod = RECOMMENDED_IMPORTS.get(pkg_name, pkg_name)
        try:
            importlib.import_module(mod)
            print(f"  OK       {pkg_name:20s} -> import {mod} ({desc})")
        except Exception:
            print(f"  MISSING  {pkg_name:20s} -> run: pip install {pkg_name}")
            missing.append(pkg_name)
    if missing:
        print(f"\nMissing {len(missing)} optional/recommended package(s). Run: pip install --recommended")
    else:
        print("\nAll recommended Terminal Mode packages are ready.")
    return 0


def main(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    ensure_site_packages_on_path()
    if not args or args[0] in ("-h", "--help", "help"):
        print(
            "DastYab Terminal `pip` — Pure-Python Package Manager\n"
            "Usage:\n"
            "  pip list                         List bundled & user-installed packages\n"
            "  pip check                        Verify all core & recommended modules import cleanly\n"
            "  pip show <package>               Show package metadata\n"
            "  pip install <pkg> [<pkg2>...]    Install pure-Python packages from PyPI\n"
            "  pip install --recommended        Install all recommended Terminal Mode packages\n"
            "  pip install -r requirements.txt  Install packages from a requirements file\n"
            "  pip uninstall [-y] <pkg>         Remove a user-installed package"
        )
        return 0

    sub = args[0].lower()
    rest = args[1:]

    if sub in ("-V", "--version"):
        print(f"dastyab-pip 1.0 (site-packages: {site_packages_dir()})")
        return 0

    if sub == "list":
        dists = list_distributions()
        if not dists:
            print("No distributions discovered.")
            return 0
        print(f"{'Package':<24} {'Version':<14} {'Source'}")
        print(f"{'-'*24} {'-'*14} {'-'*10}")
        for d in dists:
            print(f"{d['name']:<24} {d['version']:<14} {d['source']}")
        return 0

    if sub == "show":
        if not rest:
            print("Usage: pip show <package>")
            return 1
        target = _canonical_name(rest[0])
        for d in list_distributions():
            if _canonical_name(d["name"]) == target:
                print(f"Name: {d['name']}")
                print(f"Version: {d['version']}")
                print(f"Source: {d['source']}")
                print(f"Location: {d['location']}")
                return 0
        print(f"WARNING: Package(s) not found: {rest[0]}")
        return 1

    if sub == "check":
        return cmd_check()

    if sub in ("install-required", "install-recommended"):
        return install_packages([spec for _, spec, _ in RECOMMENDED_PACKAGES], upgrade=False)

    if sub == "install":
        upgrade = False
        no_deps = False
        specs = []
        i = 0
        while i < len(rest):
            a = rest[i]
            if a in ("-U", "--upgrade"):
                upgrade = True
                i += 1
            elif a == "--no-deps":
                no_deps = True
                i += 1
            elif a in ("--recommended", "--required", "--all"):
                specs.extend([spec for _, spec, _ in RECOMMENDED_PACKAGES])
                i += 1
            elif a == "-r" and i + 1 < len(rest):
                req_path = os.path.abspath(rest[i + 1])
                if not os.path.isfile(req_path):
                    print(f"[pip] ERROR: Could not open requirements file: {rest[i + 1]}")
                    return 1
                with open(req_path) as fh:
                    for line in fh:
                        cleaned = line.split("#", 1)[0].strip()
                        if cleaned and not cleaned.startswith("-"):
                            specs.append(cleaned)
                i += 2
            elif a.startswith("-"):
                i += 1
            else:
                specs.append(a)
                i += 1
        if not specs:
            print("Usage: pip install [--upgrade] [--recommended | <pkg>... | -r <file>]")
            return 1
        return install_packages(specs, upgrade=upgrade, no_deps=no_deps)

    if sub == "uninstall":
        pkgs = [a for a in rest if a != "-y" and a != "--yes"]
        if not pkgs:
            print("Usage: pip uninstall [-y] <pkg>")
            return 1
        return uninstall_packages(pkgs)

    print(f"pip: unknown subcommand '{sub}' (try 'pip help')")
    return 1
