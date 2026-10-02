# CoLink Android

Android client for CoLink — clipboard sync, file transfer, text messaging, and CastBoard display.

**Tech stack:** Kotlin 2 · Jetpack Compose (Material 3) · Hilt · OkHttp/Retrofit · Ktor CIO · Room · Bouncy Castle · Android NSD

## Requirements

- Android Studio Hedgehog or later
- JDK 21
- Android SDK: compileSdk 36, minSdk 26

## Setup

Create `local.properties` in the project root:

```properties
SERVER_BASE_URL=https://sync.colink.evative7.host
# Optional: point to a local CastBoard dev server
# CASTBOARD_DEV_URL=http://10.0.2.2:5173
# Optional: use an unreleased local CastBoard project or dist directory
# CASTBOARD_LOCAL_PATH=../colink-castboard
```

## Build

```sh
# Debug build (app ID: com.colink.android.debug)
./gradlew assembleDebug
./gradlew installDebug

# Release build (app ID: com.colink.android)
./gradlew assembleRelease
```

Builds download the immutable CastBoard release declared by `castboard.version` in `gradle.properties` and cache it under `.gradle/castboard-cache/`. `CASTBOARD_DEV_URL` skips bundled assets for debug builds, while `CASTBOARD_LOCAL_PATH` replaces the download with an existing local build.

```properties
KEYSTORE_FILE=release.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

## Architecture

The app runs a persistent foreground service (`CoLinkService`) that maintains both LAN (mDNS + WebSocket) and cloud (WebSocket relay) connections simultaneously.

- **LAN discovery**: Android NSD (mDNS)
- **LAN crypto**: Ed25519 identity, X25519 ECDH + HKDF-SHA256 session key, AES-256-GCM / ChaCha20-Poly1305
- **CastBoard**: embedded WebView loading a pinned CastBoard release artifact (debug with `CASTBOARD_DEV_URL` connects to an external development server instead)
