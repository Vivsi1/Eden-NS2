# EdenPack Converter for Android

Standalone Android app for converting an extracted game directory into EdenPack v1.

## Requirements
- Android 8.0 (API 26) or newer.
- A source directory containing `ExeFS/` and `RomFS/`.
- A destination document provider that supports seekable writable files (the Android system Files provider does).

## Build
Open this directory in Android Studio, or run:

```sh
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

The app uses Android's Storage Access Framework. It does not request broad storage permissions. File contents are copied in 1 MiB chunks and hashed with SHA-256 while writing. Output uses the EdenPack v1 32-byte little-endian header, 4096-byte data alignment, and JSON index compatible with `tools/edenpack/edenpack.py`.

Note: output providers must expose a seekable file descriptor because the archive header is finalized after the index has been written. Use the device's local Downloads/Documents provider if a cloud provider returns a pipe.
