#!/usr/bin/env bash
# Build the dastyab CLI from the current checkout for Android and stage it as
# the native executable name expected by the APK (lib/<abi>/libdastyab.so).
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT_DIR=${1:-"$ROOT_DIR/android/app/src/main/jniLibs"}
NDK_VERSION=${ANDROID_NDK_VERSION:-27.0.12077973}
ANDROID_API=${ANDROID_API_LEVEL:-24}

if [[ ! "$ANDROID_API" =~ ^[0-9]+$ ]] || (( ANDROID_API < 24 )); then
  echo "ANDROID_API_LEVEL must be an integer >= 24 (got: $ANDROID_API)" >&2
  exit 2
fi

SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME is required" >&2
  exit 2
fi
NDK_ROOT=${ANDROID_NDK_ROOT:-$SDK_ROOT/ndk/$NDK_VERSION}
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "Android NDK toolchain not found: $TOOLCHAIN" >&2
  exit 1
fi

# Rust target, Android ABI, clang triple, and the cc-rs environment suffix.
declare -a TARGETS=(
  "aarch64-linux-android|arm64-v8a|aarch64-linux-android|aarch64_linux_android"
  "armv7-linux-androideabi|armeabi-v7a|armv7a-linux-androideabi|armv7_linux_androideabi"
  "x86_64-linux-android|x86_64|x86_64-linux-android|x86_64_linux_android"
  "i686-linux-android|x86|i686-linux-android|i686_linux_android"
)

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
for entry in "${TARGETS[@]}"; do
  IFS='|' read -r target abi clang_triple cc_suffix <<< "$entry"
  linker="$TOOLCHAIN/${clang_triple}${ANDROID_API}-clang"
  if [[ ! -x "$linker" ]]; then
    echo "Android API $ANDROID_API linker not found: $linker" >&2
    exit 1
  fi

  target_env=$(printf '%s' "$target" | tr '[:lower:]-' '[:upper:]_')
  export "CARGO_TARGET_${target_env}_LINKER=$linker"
  export "CC_${cc_suffix}=$linker"
  export "AR_${cc_suffix}=$TOOLCHAIN/llvm-ar"

  echo "Building dastyab-cli: target=$target abi=$abi api=$ANDROID_API"
  cargo build --locked --release --target "$target" -p dastyab-cli

  source_bin="$ROOT_DIR/target/$target/release/dastyab"
  staged_bin="$OUTPUT_DIR/$abi/libdastyab.so"
  [[ -f "$source_bin" ]] || { echo "Missing build output: $source_bin" >&2; exit 1; }
  mkdir -p "$(dirname "$staged_bin")"
  install -m 0755 "$source_bin" "$staged_bin"

  # Catch accidental host binaries or a wrong target before Gradle packages it.
  file_output=$(file "$staged_bin")
  [[ "$file_output" == *"ELF"* ]] || { echo "Not an ELF executable: $staged_bin ($file_output)" >&2; exit 1; }
  case "$abi" in
    arm64-v8a)      [[ "$file_output" == *"ARM aarch64"* ]] || exit 1 ;;
    armeabi-v7a)    [[ "$file_output" == *"ARM"* ]] || exit 1 ;;
    x86_64)         [[ "$file_output" == *"x86-64"* ]] || exit 1 ;;
    x86)            [[ "$file_output" == *"80386"* || "$file_output" == *"Intel 80386"* ]] || exit 1 ;;
  esac
  echo "$file_output"
done

expected=(arm64-v8a armeabi-v7a x86 x86_64)
for abi in "${expected[@]}"; do
  [[ -x "$OUTPUT_DIR/$abi/libdastyab.so" ]] || { echo "Missing packaged ABI: $abi" >&2; exit 1; }
done
find "$OUTPUT_DIR" -mindepth 1 -maxdepth 2 -type f -name 'libdastyab.so' | sort
