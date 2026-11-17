# Android build and runtime

The APK bundles the Rust CLI as `lib/<abi>/libdastyab.so`. Android extracts it into the application's read-only native library directory. Do not download executable code into `filesDir`: Android 10+ denies execution there for apps targeting API 29+. App updates replace the engine through the signed APK installer.

## Build

Use Java 17, Android SDK platform/build-tools 34, NDK 27.0.12077973, and Rust targets aarch64-linux-android, armv7-linux-androideabi, x86_64-linux-android, i686-linux-android.

From the repository root:

```sh
bash scripts/android-build-native.sh android/app/src/main/jniLibs
cd android
./gradlew testDebugUnitTest assembleRelease
./gradlew connectedDebugAndroidTest
```

The native builder targets Android API 24. Native outputs are generated, gitignored files. Release CI builds them from the same checkout as the app, runs JVM and emulator tests, checks all four packaged ABIs and the APK signature, and uploads the APK. Only tag releases publish it; main builds no longer replace assets in an unrelated older release.

## Tools and limits

Type `--help` for the bundled CLI's complete command list and `<command> --help` for its arguments. Paths, URLs, Unicode, and quoted arguments are accepted without a shell. HOME and working directory are private app storage; TMPDIR is app cache. Commands have a five-minute timeout and can be cancelled. Leaving/destroying the activity cancels its active process; this is not a persistent background service.

The bundled CLI exposes native profile, account, configuration, diagnostic, network-discovery, and export operations. Actual network operations require connectivity and any applicable credentials. Desktop-only tools that invoke systemd, Python, shell scripts, external proxy engines, or privileged routing are not ported by this launcher. Device-wide VPN routing needs an Android VpnService integration; long-running protection needs a foreground service. These remain separate work and must not be advertised as complete Linux parity.

## Verification

Instrumentation tests execute the packaged engine with `--version`, `--help`, and `profiles` in an isolated HOME, then exercise the activity's Run button and recreation. A successful build or APK signature check alone does not prove startup or networking works on a device.

The local 1.4.0 development APK initially uses existing API-33 arm64/x86_64 release engines for packaging checks; it is not the four-ABI/API-24 CI release. Use CI-built assets for distribution after its runtime gates pass.
