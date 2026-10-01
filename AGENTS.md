# XrayDroid agent guide

## Scope and architecture
- Kotlin Android application using Compose Material 3 Expressive. Native UI controls an embedded server; the existing upstream web panel opens in a browser.
- `app/src/main/java/io/github/xraydroid/runtime`: foreground service, executable/data layout, scoped process cleanup, observable state.
- `scripts/build-core.sh`: reproducible arm64 core build; upstream v3.8.5 commit and Xray v26.6.27 hashes are pinned.
- `patches/3x-ui-android.patch`: all upstream changes. `upstream/` is ignored and must never be staged as an embedded Git repository.
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
- Run `:app:connectedDebugAndroidTest` on an arm64 Android development device for lifecycle changes.
- Upstream Go changes require formatting, relevant tests, cross-compilation, and one full Go test suite before a commit.
- Record actual results and unverified cases in `docs/PROGRESS.md`; never claim build or runtime success without observed evidence.
- Do not commit incomplete or unverified work. Do not push without a configured authorized remote.
