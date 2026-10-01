# XrayDroid agent guide

## Scope and architecture
- Kotlin Android application using Compose Material 3 Expressive. Native UI controls an embedded server; the existing upstream web panel opens in a browser.
- `app/src/main/java/io/github/xraydroid/runtime`: foreground service, executable/data layout, scoped process cleanup, observable state.
- `scripts/build-core.sh`: reproducible arm64 core build; upstream v3.8.5 commit and Xray v26.6.27 hashes are pinned.
- `scripts/build-frpc.sh`: pinned frpc v0.71.0 Android arm64 build, called by the core build; host frpc/frps are validation-only tools in `.core-cache/`.
- `FrpService` is independent of `XuiService` and follows system routing. Keep frp and outbound network settings in separate subpages. Full TOML is validated before atomic save; runtime control endpoint, logging and reconnect settings are managed by the app.
- `patches/frp-android.patch` creates an isolated session and protects against parent death. Scope token-command cleanup to that session and the app UID; never kill processes by shell names.
- `patches/3x-ui-android.patch` and `patches/xray-android-network.patch`: all upstream changes. `upstream/` is ignored and must never be staged as an embedded Git repository.
- Executables must remain in `nativeLibraryDir`; writable runtime data remains under `filesDir/server`.

## Conventions
- Use English identifiers and diagnostics, Traditional Chinese user-facing text and new source comments.
- Keep root README concise and cross-link `docs/zh-TW/README.md` and `docs/en/README.md`; update all three when behavior changes.
- Use graph tools for code discovery; index the project before discovering unindexed code.
- Do not read secrets or backend runtime logs; native lifecycle events intentionally exclude raw backend output.
- Preserve user databases and runtime configuration. Recovery must match application UID and executable path.

## Validation
- Build native cores before Gradle packaging.
- Run `./gradlew :app:ktlintFormat`, then `:app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.
- Run `:app:connectedDebugAndroidTest` with `-PvalidationApplicationId=io.github.xraydroid.validation` on an arm64 Android development device for lifecycle changes. Stop the regular package service to release port 2053; preserve its data.
- For frpc forwarding validation, run `scripts/frp-validation-server.py` and reverse TCP ports 17000 and 18080 with adb; stop the fixture and remove only those forwards afterward. Without the fixture, forwarding coverage is skipped.
- Upstream Go changes require formatting, relevant tests, cross-compilation, and one full Go test suite before a commit.
- Record actual results and unverified cases in `docs/PROGRESS.md`; never claim build or runtime success without observed evidence.
- Do not commit incomplete or unverified work. Do not push without a configured authorized remote.
