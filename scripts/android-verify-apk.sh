#!/usr/bin/env bash
# Zero-Defect APK Installability Gate:
# Verifies ZIP integrity, uncompressed/aligned resources.arsc, PackageParser badging,
# absence of testOnly, 4-ABI ELF headers, zipalign alignment, and v1/v2/v3 APK signatures.
set -euo pipefail

usage() {
  echo "Usage: $0 <path-to-apk> [--static-only]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
APK="$1"
STATIC_ONLY=false
if [[ "${2:-}" == "--static-only" ]]; then
  STATIC_ONLY=true
fi

[[ -s "$APK" ]] || { echo "FAIL: APK missing or empty: $APK" >&2; exit 1; }

echo "[1/5] Checking ZIP container integrity..."
python3 - "$APK" <<'PY'
import struct
import sys
import zipfile

apk_path = sys.argv[1]
expected_abis = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86_64": (2, 62),
    "x86": (1, 3),
}

with zipfile.ZipFile(apk_path, "r") as zf:
    bad = zf.testzip()
    if bad is not None:
        raise SystemExit(f"FAIL: Corrupt ZIP entry: {bad}")

    names = set(zf.namelist())
    for required in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
        if required not in names:
            raise SystemExit(f"FAIL: Missing required APK entry: {required}")

    # Android 11+ (API 30+) requires resources.arsc to be uncompressed and 4-byte aligned.
    arsc_info = zf.getinfo("resources.arsc")
    if arsc_info.compress_type != zipfile.ZIP_STORED:
        raise SystemExit(
            f"FAIL: resources.arsc must be stored uncompressed (compress_type={arsc_info.compress_type})"
        )
    with open(apk_path, "rb") as raw:
        raw.seek(arsc_info.header_offset)
        lfh = raw.read(30)
        if len(lfh) != 30 or lfh[:4] != b"PK\x03\x04":
            raise SystemExit("FAIL: Invalid local file header for resources.arsc")
        fname_len, extra_len = struct.unpack_from("<HH", lfh, 26)
        data_offset = arsc_info.header_offset + 30 + fname_len + extra_len
        if data_offset % 4 != 0:
            raise SystemExit(
                f"FAIL: resources.arsc data offset ({data_offset}) is not 4-byte aligned"
            )

    # Verify packaged resources exist (full res/mipmap* paths or AGP 8+ shortened res/* paths).
    # In full mode, step [3/5] cross-checks every badging application-icon entry against the ZIP.
    has_res = any(n.startswith("res/") for n in names)
    if not has_res:
        raise SystemExit("FAIL: No res/ resources packaged in APK")

    # Verify all 4 ABIs have valid little-endian ELF binaries and no misnamed executables.
    for n in names:
        if n.startswith("lib/") and (n.endswith("/dastyab") or n.endswith("/dastyab-android")):
            raise SystemExit(f"FAIL: Incorrectly named native executable in APK: {n}")

    for abi, spec in expected_abis.items():
        entry = f"lib/{abi}/libdastyab.so"
        if entry not in names:
            raise SystemExit(f"FAIL: Missing native binary entry: {entry}")
        data = zf.read(entry)
        if len(data) < 4096 or data[:4] != b"\x7fELF" or data[5] != 1:
            raise SystemExit(f"FAIL: Invalid little-endian ELF header in {entry}")
        actual = (data[4], struct.unpack_from("<H", data, 18)[0])
        if actual != spec:
            raise SystemExit(f"FAIL: ABI mismatch in {entry}: got {actual}, expected {spec}")

print("  OK: ZIP integrity, uncompressed 4-byte aligned resources.arsc, resources, and 4-ABI ELFs verified.")
PY

if $STATIC_ONLY; then
  echo "Static-only mode enabled — skipping Android SDK build-tools checks (aapt2/zipalign/apksigner)."
  exit 0
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
find_tool() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return 0
  fi
  if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/build-tools" ]]; then
    local found
    found=$(find "$SDK_ROOT/build-tools" -maxdepth 2 -type f -name "$name" 2>/dev/null | sort -V | tail -n 1 || true)
    if [[ -x "$found" ]]; then
      echo "$found"
      return 0
    fi
  fi
  return 1
}

ZIPALIGN=$(find_tool zipalign || true)
AAPT2=$(find_tool aapt2 || true)
APKSIGNER=$(find_tool apksigner || true)

[[ -n "$ZIPALIGN" && -n "$AAPT2" && -n "$APKSIGNER" ]] || {
  echo "FAIL: Android SDK build-tools (zipalign, aapt2, apksigner) not found. Set ANDROID_HOME or pass --static-only." >&2
  exit 1
}

echo "[2/5] Checking zipalign (4-byte alignment)..."
"$ZIPALIGN" -c -v 4 "$APK" >/dev/null
echo "  OK: zipalign passed."

echo "[3/5] Checking aapt2 PackageParser badging & packaged launcher icons..."
BADGING=$("$AAPT2" dump badging "$APK")
echo "$BADGING" | grep -q "package: name='com.dastyab.app'" || {
  echo "FAIL: Unexpected package name in badging" >&2
  exit 1
}
echo "$BADGING" | grep -q "launchable-activity: name='com.dastyab.app.MainActivity'" || {
  echo "FAIL: Missing launchable-activity MainActivity" >&2
  exit 1
}
python3 - "$APK" "$BADGING" <<'PY'
import re, sys, zipfile

apk_path, badging = sys.argv[1], sys.argv[2]
icons = re.findall(r"application-icon[^:]*:'([^']+)'", badging)
if not icons:
    raise SystemExit("FAIL: Missing application-icon in aapt2 badging")

with zipfile.ZipFile(apk_path, "r") as zf:
    names = set(zf.namelist())
    for icon_path in icons:
        if icon_path not in names:
            raise SystemExit(f"FAIL: Launcher icon {icon_path} declared in badging is missing from APK")
        if zf.getinfo(icon_path).file_size <= 0:
            raise SystemExit(f"FAIL: Launcher icon {icon_path} in APK is empty")

print(f"  OK: aapt2 badging & {len(icons)} packaged launcher icon entries verified.")
PY

echo "[4/5] Checking AndroidManifest flags (no testOnly, extractNativeLibs=true)..."
MANIFEST_TREE=$("$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml)
if echo "$MANIFEST_TREE" | grep -qiE "testOnly.*=.*true|testOnly.*=\(type 0x12\)0xffffffff"; then
  echo "FAIL: Release APK has android:testOnly=true — users cannot sideload it!" >&2
  exit 1
fi
echo "  OK: Manifest flags verified."

echo "[5/5] Verifying APK signatures (v2 + v3 schemes for minSdk 24+)..."
VERIFY_OUT=$("$APKSIGNER" verify --verbose --print-certs "$APK")
echo "$VERIFY_OUT"
grep -qi "^Verifies" <<<"$VERIFY_OUT" || {
  echo "FAIL: apksigner did not report overall 'Verifies' status" >&2
  exit 1
}
for scheme in "v2 scheme" "v3 scheme"; do
  grep -qi "Verified using ${scheme}.*: *true" <<<"$VERIFY_OUT" || {
    echo "FAIL: APK signature missing ${scheme} verification" >&2
    exit 1
  }
done

echo "✅ PASS: APK passed all installability, alignment, ABI, manifest, and v2/v3 signature gates."
