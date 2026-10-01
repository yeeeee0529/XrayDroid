# XrayDroid usage and development

[Project overview](../../README.md) · [繁體中文](../zh-TW/README.md) · [Progress](../PROGRESS.md)

## Features and scope

XrayDroid runs 3x-ui v3.8.5 and Xray-core v26.6.27 under a normal Android application UID, without root.
The Kotlin / Jetpack Compose Material 3 Expressive dashboard provides status, start, stop, restart, lifecycle logs, and a browser entry point.
Inbound, client, routing, and traffic management use the original 3x-ui web panel, whose design has not been converted to M3E.

Android API 26+ arm64 devices are supported. This is a local server; Android VPNService and device-wide traffic interception are not implemented.
The first version disables MTProto / TUIC sidecars and panel / Xray self-updates. System statistics inaccessible on Android are handled by upstream warnings or zero values.
Linux systemd, Fail2ban, syslog, and installer functionality are outside the Android app's scope; some upstream menus may remain visible.

## Build

Install JDK 17, Android SDK 36, Build Tools 36.0.0, NDK r30, Go 1.27.1, Node.js 24+, npm, Python 3.11+, Git, and curl.

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_NDK_HOME="/path/to/android-ndk"
./scripts/build-core.sh
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, set `sdk.dir` in `local.properties`. On macOS, the default NDK location is Homebrew's `/opt/homebrew/share/android-ndk`.
The core script fetches official sources, verifies the 3x-ui commit and Xray release SHA256, applies pinned Android patches, and builds the upstream frontend and both Go executables. Xray is compiled from a pinned source commit; the official archive supplies geodata and licenses.
Gradle does not download cores automatically and rejects APK packaging if required binaries/assets are missing.
Material 3 is pinned to `1.5.0-alpha04` because stable `1.4.0` does not expose `MaterialExpressiveTheme`; upgrades require renewed native UI validation.
The first build needs network access. `.tools/`, `.core-cache/`, `upstream/`, generated native executables, and geodata are excluded from Git.

## Usage

1. Open the app, tap start, and choose whether to allow notifications.
2. Wait for readiness and open the web panel.
3. Initially sign in using upstream's public defaults `admin` / `admin`; immediately change them in panel settings.
4. Create an inbound on a port above 1024. Access from other devices requires a reachable listening address and a network that permits inbound traffic.
5. Stop using the app or persistent notification; closing the activity keeps the service running.

The management panel is fixed at `http://127.0.0.1:2053/`. The Android patch forces HTTP, the root path, and loopback listening.
Panel port, path, TLS, and listening-address settings do not override this Android entry point; subscription and inbound servers retain their own settings.
The native app shows only its own lifecycle events, avoiding exposure of potentially sensitive backend output.
Core updates require a rebuilt APK; downloaded executables are never run from writable app data.

## Outbound network

Open Settings from the dashboard to configure outbound networks; the return button or Android Back gesture returns home. The settings page lists individual app-visible interfaces, such as `wlan0` and `tun1`, with IP addresses, DNS servers, status, and a refresh action. Names reflect existing device interfaces; the app does not create them.

Selection persists by interface name and resolves the current Android network handle after reconnecting. A missing selected interface remains selected and waits for recovery, without switching to another interface of the same type. Existing transport-mode preferences remain supported.

VPN interfaces appear separately from the system default. Interfaces without an available Android Network, down interfaces, and loopback interfaces display a reason and cannot be selected. Android may hide interfaces owned by other users or restricted to other apps, so detection cannot guarantee every virtual interface. Native binding failures also prevent fallback; refresh or select another interface.

- System default follows Android routing, including a system VPN.
- A selected network binds Xray outbound sockets and system DNS to the Android network associated with that interface and may bypass a VPN. Cellular selection requests that Android keep the network available.
- Changing the selection restarts both cores and disconnects current sessions. Losing a selected network pauses the service until it returns, without falling back to another network.

Selecting an interface binds its associated Android network; Android chooses the underlying route. CLAT and other child interfaces without a public API network association are listed for information only. The app does not use hidden APIs or infer associations from interface names.

Management-panel connections and inbound listeners retain their original routing. xicmp is unsupported with a selected network. Disabled radios, missing SIMs, and carrier restrictions can prevent cellular activation.

## Architecture

```text
MainActivity → XuiService → nativeLibraryDir/libxui.so → libxray.so
                  ↓
filesDir/server/{db,xray,log}
                  ↓
Browser → http://127.0.0.1:2053/
```

`XuiService` uses a user-started `specialUse` foreground service.
It installs geodata and sets `XUI_XRAY_BINARY`, `XUI_BIN_FOLDER`, `XUI_DB_FOLDER`, `XUI_LOG_FOLDER`, and `XRAY_LOCATION_ASSET` before launching the panel.
Shutdown sends SIGTERM and escalates after a timeout. Recovery matches the same application UID and exact executable paths, never broad process names.
The upstream patch additionally protects against parent death; foreground services cannot guarantee survival against all process reclamation, Doze, or user force-stop actions.

## Validation

```bash
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew -PvalidationApplicationId=io.github.xraydroid.validation :app:connectedDebugAndroidTest
```

Device tests cover native executable launch, embedded frontend, Xray child execution, panel login, VLESS TCP inbound creation / listening / deletion, background access, forced panel death cleanup, restart, shutdown, and database file retention.
Use the isolated validation package on a development device. Stop the regular package service first to release the fixed panel port; its data is preserved. Observed checks and limitations are recorded in [Progress](../PROGRESS.md).
The panel's Go types depend on a newer Xray revision than v26.6.27. New protocols or fields require configuration-specific compatibility validation.

## License and sources

[GPL-3.0](../../LICENSE) · [Third-party notices](../../THIRD_PARTY_NOTICES.md) · [Original study](../../3xui_Study.md).
3x-ui v3.8.5 is pinned to `7ef22f94c950ff09f0870e2295fa65ad5968742c`; the study used a different commit, so the build script is authoritative.
