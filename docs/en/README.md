# XrayDroid usage and development

[Project overview](../../README.md) · [繁體中文](../zh-TW/README.md) · [简体中文](../zh-CN/README.md) · [Handoff notes](../PROGRESS.md)

## Features and scope

- No root required; runs 3x-ui and Xray-core as a normal Android app on Android API 26+ arm64 devices.
- The interface provides service status, start, stop, restart, lifecycle logs, and a browser entry point.
- Inbounds, clients, routing, and traffic statistics use the 3x-ui web panel.
- This is a **local proxy server**, **not** a proxy tool, and does not intercept other apps' traffic.
- MTProto / TUIC sidecars and panel / Xray self-updates are not supported yet.
- System statistics Android cannot read fall back to warnings or zero values; Linux systemd, Fail2ban, syslog, and installers are outside the app's scope, and some upstream menus may still appear.

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

- The SDK can also be set through `sdk.dir` in `local.properties`; on macOS the NDK defaults to `/opt/homebrew/share/android-ndk`.
- `build-core.sh` downloads official sources (the first build needs network access), verifies the 3x-ui commit and the Xray SHA256, applies pinned patches, and builds the upstream frontend plus three Go executables.
- Gradle does not download cores automatically and refuses to package the APK when files are missing.

## Usage

1. Open the app and tap "Start service"; choose whether to grant notification permission.
2. Wait for the service to be ready, then tap "Open admin panel".
3. The first sign-in uses the 3x-ui defaults `admin` / `admin`; changing them in the panel settings afterward is recommended.
4. Creating an inbound requires a port above 1024. Opening an inbound to other devices requires a reachable listening address and a network that permits inbound connections.
5. Stop from the app or the persistent notification; closing the dashboard does not stop the service.

The management panel is fixed at `http://127.0.0.1:2053/`; the Android patch forces HTTP, the root path, and loopback listening. Panel port, path, TLS, or listening-address settings do not override this fixed Android entry point; subscription servers and inbounds still follow their own settings. The app shows only its own lifecycle logs.
Core updates require rebuilding and installing the APK.

## About outbound network

Open Settings → Outbound network from the top right of the dashboard. The subpage lists app-visible interfaces, IP addresses, DNS, and connection status, with a manual refresh; names reflect the actual device.

The selection is saved and reused on the next start. A selected interface is matched by name to the current Android network; if it disappears, the selection is kept and waits for recovery instead of switching to another interface of the same type.

VPN interfaces appear separately and are not merged into the system default. Interfaces without an available Android network, disabled interfaces, and loopback interfaces are marked with a reason and cannot be selected; Android may not expose interfaces dedicated to other users or other apps, so not all virtual interfaces are guaranteed to be listed. Selecting an interface binds the Android network it belongs to, and Android decides which underlying interface the packets actually use.

- System default: follows Android routing, including a system VPN.
- Selected network: binds Xray outbound sockets and system DNS to the Android network of that interface and may bypass a system VPN; selecting a cellular network asks Android to keep it available.
- Switching: switching while the service is running restarts 3x-ui and Xray and drops current connections; if the selected network disappears, the service waits and restarts once it returns.

## frp client

Settings → frp provides the complete frp client for connecting to an external frp server; the phone does not provide an frps server. This subpage is separate from Outbound network: frpc runs in its own foreground service with its own notification, and starting or stopping it does not affect 3x-ui / Xray. frpc has its own network interface selector, saved independently of Xray. It follows Android system routing by default; selecting an interface binds frpc and DNS to that Android network. Switching restarts frpc; if the selected network becomes unavailable, frpc waits for recovery without falling back.

1. Choose "Form" or "TOML". The form groups basic settings, authentication, transport, TLS, and other settings; expand the sections you need. It supports Token/OIDC, all TCP/KCP/QUIC/WebSocket/WSS transports, and metadata.
2. "Proxy rules" can add, edit, and remove TCP, UDP, HTTP, HTTPS, STCP, SUDP, XTCP, and TCPMUX proxies; "visitor rules" support STCP, SUDP, and XTCP. Each protocol shows its domain, path, secret, listener, traversal, and fallback fields. The protocol is chosen when the rule is created; add another rule to change it.
3. Both modes share one complete TOML document. Switching to the form parses the TOML; when the syntax or field structure cannot be converted, the original text is kept and the page stays in TOML mode. Switching modes alone does not rewrite the text; actual form edits normalize formatting and remove comments while keeping untouched fields, plugins, visitors, and `includes`. Plugins and other advanced options without form controls can be edited in TOML. Invalid numbers stay in the form and must be fixed before switching modes, validating, saving, or starting. Importing to replace the draft still requires confirmation.
4. Tap "Start frpc"; starting and restarting validate and save the draft first. Saving while running requires a restart to apply. The dashboard service and frpc are controlled separately, and closing the settings page does not stop frpc.
5. The top card shows the states reported by the core: "Connecting to the frp server", "Connected to the frp server", "Connection failed, retrying", "Disconnected, reconnecting", or "Connection status unavailable". On failure it shows reasons the user can act on — DNS, connection refused, timeout, TLS, authentication preparation, login rejected, or the connection closed before login — plus the attempt count.
6. "Connected" means login to frps succeeded, which can be confirmed even with no proxies or only visitors; an enabled proxy still does not guarantee that the local target service is reachable. When the status is unavailable, previous results are cleared instead of keeping "Connected". Stop from the subpage or the frp notification.

"Allow external token command" is off by default, matching upstream safety defaults. To use a `tokenSource` that obtains the token through an external command, stop frpc first and then enable it; validation and startup then allow `TokenSourceExec`. External commands run with this app's permissions; importing a support file through the file picker does not mean the file is executable. The option is stored separately and does not rewrite the TOML.

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

## Validation

```bash
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew -PvalidationApplicationId=io.github.xraydroid.validation :app:connectedDebugAndroidTest
```

Device tests verify native execution, the embedded frontend, the Xray child process, panel login, adding / listening on / deleting a VLESS TCP inbound, the background panel, cleanup after force-stop, restart, stop, and retention of database files.
frpc forwarding tests need a local test server. Run the following commands in another terminal, then run the isolated device tests above; without that server, the TCP/UDP forwarding cases are skipped. The test server listens only on the local machine and uses the `.core-cache/frps` produced by the build; stop it with Ctrl+C after testing and remove the two test forwards.

```bash
adb reverse tcp:17000 tcp:17000
adb reverse tcp:18080 tcp:18080
python3 scripts/frp-validation-server.py
# after the tests and stopping the server
adb reverse --remove tcp:17000
adb reverse --remove tcp:18080
```

frpc device tests cover real TCP/UDP round trips, reconnects, independent start and stop, retention of invalid configuration, support-file import, and group cleanup of external token commands and on parent death; this is not the same as validating every frp protocol or plugin.
Use the isolated package and a development device for device tests; stop the service of the regular package first to release the fixed panel port, and the tests do not remove the regular package's data. Executed items and limitations are in the [progress record](../PROGRESS.md).

## License and sources

[GPL-3.0](../../LICENSE) · [Third-party notices](../../THIRD_PARTY_NOTICES.md)
