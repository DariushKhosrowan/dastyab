# Build, Release & Cross-Compile

## Build (debug)

```bash
cargo build --workspace
```

Binaries land in `target/debug/`:

- `dastyab` — CLI
- `dastyab-tui` — TUI
- `dastyab-gui` — GUI

## Build (release)

```bash
cargo build --release --workspace
```

Release profile: `opt-level=3`, thin LTO, `codegen-units=1`, stripped
symbols.

## Test

```bash
cargo test --workspace
cargo clippy --workspace   # must be warning-free
cargo fmt --all
```

## Cross-compiling

The core + CLI are pure Rust with no system C dependencies (rustls for TLS),
so cross-compilation is clean. For example, on a Linux host targeting Windows:

```bash
rustup target add x86_64-pc-windows-msvc
cargo build --release --target x86_64-pc-windows-msvc -p dastyab-cli
```

ARM targets follow the same pattern.

### Android

The core + CLI are pure Rust (rustls TLS, no C dependencies, no build
scripts), so they cross-compile to Android cleanly. The only extra piece is
the Android NDK `clang`, which serves as the cross-linker (API 33+):

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

# ANDROID_NDK_ROOT must point to an installed NDK (e.g. r30)
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang"
cargo build --release --target aarch64-linux-android -p dastyab-cli
# -> target/aarch64-linux-android/release/dastyab
```

The GitHub Release workflow (`.github/workflows/release.yml`) builds all four
Android ABIs (`aarch64`, `armv7`, `x86_64`, `i686`) and attaches
`dastyab-android-<arch>` to every release, alongside the other platforms.

## Releasing

1. Bump `version` in the workspace `Cargo.toml`.
2. Update `docs/CHANGELOG.md`.
3. Tag and push: `git tag vX.Y.Z && git push --tags`.
4. The CI workflow builds release binaries for Linux, Windows, macOS and
   Android and attaches them all to a GitHub Release
   (see `.github/workflows/`).

## Self-update

The CLI can check and fetch newer binaries from GitHub releases:

```bash
dastyab update-check
dastyab update-apply
```

`selfupdate.rs` downloads the platform-matching asset. Note: the release must
actually attach a binary for the target OS/arch.

## Installing as a single binary

Copy `target/release/dastyab` to a directory on `PATH`:

```bash
install -m 0755 target/release/dastyab ~/.local/bin/dastyab
```

No other runtime is required.
