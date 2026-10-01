# 第三方來源與授權 / Third-party sources and licenses

- **3x-ui v3.8.5** — [官方來源](https://github.com/MHSanaei/3x-ui/tree/v3.8.5)，commit `7ef22f94c950ff09f0870e2295fa65ad5968742c`，[GPL-3.0](https://github.com/MHSanaei/3x-ui/blob/v3.8.5/LICENSE)。Android 修改完整保存在 `patches/3x-ui-android.patch`；建置流程保留上游來源於 `upstream/3x-ui`。
- **Xray-core v26.6.27** — [官方原始碼](https://github.com/XTLS/Xray-core/tree/v26.6.27)，[MPL-2.0](https://github.com/XTLS/Xray-core/blob/v26.6.27/LICENSE)。使用未修改的官方 Android arm64 發行檔，下載來源與 SHA256 固定於建置腳本。
- **AndroidX / Jetpack Compose** — [Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/)，[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)。
- **Kotlin / kotlinx.coroutines** — [JetBrains Kotlin](https://github.com/JetBrains/kotlin)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)，Apache-2.0。

建置腳本保留 Xray 發行檔中的授權與聲明於 App 資產。上游前端與 Go 相依套件仍依各自授權使用，來源與版本可由固定的上游 lockfile / go.mod 查閱。
散布 APK 時，應一併提供對應專案原始碼、Android 修補與可重現建置指令。

The build script retains release license notices in application assets. Upstream frontend and Go dependencies retain their respective licenses and are identified by the pinned upstream lockfile / go.mod.
APK distribution must include access to corresponding project source, Android patches, and reproducible build instructions.
