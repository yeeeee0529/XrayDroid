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
The core script fetches official sources, verifies the 3x-ui commit and Xray release SHA256, applies pinned Android patches, and builds the upstream frontend and all three Go executables. Xray is compiled from a pinned source commit; the official archive supplies geodata and licenses.
frpc v0.71.0 is built from a pinned commit with verified Go modules. Its Android patch preserves the full client and adds process lifecycle protection and a private status endpoint.
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

The panel reports capacity and usage of the filesystem containing the app’s private data, rather than the Android root filesystem. These figures describe the entire filesystem, not the app’s own storage footprint.

## Outbound network

Open Settings → Outbound network from the dashboard. The return button or Android Back gesture returns to the settings index, then home. The outbound network subpage lists individual app-visible interfaces, such as `wlan0` and `tun1`, with IP addresses, DNS servers, status, and a refresh action. Names reflect existing device interfaces; the app does not create them.

Selection persists by interface name and resolves the current Android network handle after reconnecting. A missing selected interface remains selected and waits for recovery, without switching to another interface of the same type. Existing transport-mode preferences remain supported.

VPN interfaces appear separately from the system default. Interfaces without an available Android Network, down interfaces, and loopback interfaces display a reason and cannot be selected. Android may hide interfaces owned by other users or restricted to other apps, so detection cannot guarantee every virtual interface. Native binding failures also prevent fallback; refresh or select another interface.

- System default follows Android routing, including a system VPN.
- A selected network binds Xray outbound sockets and system DNS to the Android network associated with that interface and may bypass a VPN. Cellular selection requests that Android keep the network available.
- Changing the selection restarts both cores and disconnects current sessions. Losing a selected network pauses the service until it returns, without falling back to another network.

Selecting an interface binds its associated Android network; Android chooses the underlying route. CLAT and other child interfaces without a public API network association are listed for information only. The app does not use hidden APIs or infer associations from interface names.

Management-panel connections and inbound listeners retain their original routing. xicmp is unsupported with a selected network. Disabled radios, missing SIMs, and carrier restrictions can prevent cellular activation.

## frp client

Settings → frp provides the complete frpc v0.71.0 client for an external frps; the phone does not run frps. It has a separate foreground service and notification, so starting or stopping it does not control 3x-ui / Xray. frpc follows Android system routing, including a system VPN; Xray's selected outbound interface does not apply to frpc.

1. Choose Form or TOML mode. Expand the grouped connection, authentication, transport, TLS, and miscellaneous settings to edit them. The form supports Token/OIDC, TCP/KCP/QUIC/WebSocket/WSS transports, and metadata.
2. Add, edit, and remove TCP, UDP, HTTP, HTTPS, STCP, SUDP, XTCP, and TCPMUX proxy rules, or STCP, SUDP, and XTCP visitors. Each type exposes its domain, path, secret, listener, traversal, and fallback settings. Choose the protocol when adding a rule; add a new rule to use another protocol.
3. Both modes share one complete TOML draft. Entering Form mode parses the draft; invalid syntax or incompatible field structures keep the original text in TOML mode. Switching without editing preserves the original text. Actual form edits normalize formatting and remove comments while retaining untouched fields, plugins, visitors, and includes. Use TOML for plugin and other advanced options without form controls. Invalid numeric input remains in the form and blocks switching, validation, saving, and startup until corrected. Import replacement still requires confirmation. Validate and Save use the official strict frpc verifier; saving writes private app data atomically only after success.
4. Start and restart first validate and save the draft. Saving while running requires a restart to apply. Closing settings does not stop frpc.
5. The proxy list displays native proxy statuses. “Client running” means the process and local status endpoint are available, rather than confirming that every proxy is connected. Stop through the frp page or its notification.

The external token-command option is off by default, matching upstream safety defaults. To use a command-based `tokenSource`, stop frpc and enable the option; validation and startup then allow `TokenSourceExec`. Commands run with this app’s permissions and remain subject to Android executable-path restrictions. Importing a support file does not make it executable. The option persists separately without modifying TOML.

Complete TOML supports upstream proxies, visitors, authentication, transports, TLS, plugins, and advanced fields within Android application UID and permission limits. VirtualNet requires TUN device access; this app has no Android VPNService and does not provide TUN or device-wide traffic interception. Plugins can only access resources available to this app; Linux system paths or privileged operations cannot be assumed. See [Progress](../PROGRESS.md) for actual protocol and advanced-configuration validation coverage.

TOML imports must use UTF-8 and remain within 1 MiB. The support-file importer copies files of up to 16 MiB into private `support/UUID` filenames with safe extensions preserved (such as `.toml` or `.pem`) and shows the relative path without displaying contents. Update certificate, authentication, plugin, and other file paths in TOML accordingly. `includes` globs are supported, but imported filenames change and their paths must be adjusted. Leaving with an unsaved draft requires confirmation; activity recreation does not retain unsaved configuration.

Settings reside at `filesDir/server/frp/frpc.toml`, are excluded from saved activity state, and the frp screen disables screen capture. Raw native logs and validation output are discarded; the UI shows generic diagnostics. The app reserves the client control endpoint: at launch it overrides upstream `webServer` settings with a loopback ephemeral port and one-time credentials, disables control-endpoint TLS, and overrides logging plus `loginFailExit = false` so frpc retries failed login. These runtime overrides do not modify the saved TOML. The native client handles reconnecting to frps.

## Architecture

```text
MainActivity → XuiService → nativeLibraryDir/libxui.so → libxray.so
                  ↓
filesDir/server/{db,xray,log}
                  ↓
Browser → http://127.0.0.1:2053/

MainActivity → FrpService → nativeLibraryDir/libfrpc.so → external frps
                  ↓
filesDir/server/frp/{frpc.toml,support/}
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
frpc forwarding tests require a local fixture. Run these commands in another terminal before the isolated device suite above; TCP/UDP forwarding is skipped without the fixture. The fixture listens only on loopback and uses the generated `.core-cache/frps`. Stop it with Ctrl+C and remove these two test forwards afterward.

```bash
adb reverse tcp:17000 tcp:17000
adb reverse tcp:18080 tcp:18080
python3 scripts/frp-validation-server.py
# After the tests and fixture shutdown
adb reverse --remove tcp:17000
adb reverse --remove tcp:18080
```

The form uses tomlj 1.1.1 for complete TOML parsing rather than splitting configuration strings. Compose UI tests verify mode switching and draft retention. Test-only Espresso is pinned to 3.7.0 for Android 17 input injection compatibility.

frpc device tests cover TCP/UDP round trips, reconnects, independent lifecycle, invalid-save retention, support-file import, and cleanup of external token commands after stop or parent death. This does not establish that every frp protocol or plugin has been individually tested.
Use the isolated validation package on a development device. Stop the regular package service first to release the fixed panel port; its data is preserved. Observed checks and limitations are recorded in [Progress](../PROGRESS.md).
The panel's Go types depend on a newer Xray revision than v26.6.27. New protocols or fields require configuration-specific compatibility validation.

## License and sources

[GPL-3.0](../../LICENSE) · [Third-party notices](../../THIRD_PARTY_NOTICES.md) · [Original study](../../3xui_Study.md).
3x-ui v3.8.5 is pinned to `7ef22f94c950ff09f0870e2295fa65ad5968742c`; the study used a different commit, so the build script is authoritative.
