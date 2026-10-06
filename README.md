# XrayDroid

This project packages **3x-ui + Xray-core** into an Android app that is simple to set up; management uses the 3x-ui web panel.

[繁體中文文檔](docs/zh-TW/README.md) · [简体中文文档](docs/zh-CN/README.md) · [English documentation](docs/en/README.md) · [Handoff notes(zh-TW)](docs/PROGRESS.md)

## Requirements

- Android 8.0 / API 26 or later, `arm64-v8a`.
- Build: JDK 17, Android SDK 36 / Build Tools 36.0.0, Android NDK r30, Go 1.27.1, Node.js 24 or later, npm, Python 3.11 or later, Git, curl.
- The SDK path comes from `ANDROID_HOME` or `sdk.dir` in `local.properties`; the NDK path comes from `ANDROID_NDK_HOME`, which defaults to Homebrew's `/opt/homebrew/share/android-ndk` on macOS when unset.

## Build and usage

```bash
./scripts/build-core.sh
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open XrayDroid → "Start service" → "Open admin panel"; the panel is fixed at `http://127.0.0.1:2053/`, and the first sign-in uses `admin` / `admin`, which is best changed immediately.

Inbounds should use a port above 1024; the service can be stopped from the app or the notification. The Settings page also has separate "Outbound network" and "frp" subpages; frpc can select its own network interface independently of Xray. See the full documentation for detailed behavior.

## Current implementation status

The native interface, foreground service, version-pinned core packaging, and Android patches are implemented; validation results are in the [progress record](docs/PROGRESS.md).
MTProto / TUIC sidecars, Linux system administration, and core self-updates are not supported yet, and Android VPNService is not implemented.

## License

The project is licensed under [GPL-3.0](LICENSE). Upstream sources, versions, and individual licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
