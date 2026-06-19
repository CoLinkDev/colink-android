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
```

## Build

```sh
# Debug build (app ID: com.colink.android.debug)
./gradlew assembleDebug
./gradlew installDebug

# Release build (app ID: com.colink.android)
./gradlew assembleRelease
```

Release builds require signing config in `local.properties`:

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
- **CastBoard**: embedded WebView loading bundled assets from `app/src/main/assets/castboard/`
