# XrayDroid

將 **3x-ui v3.8.5 + Xray-core v26.6.27** 封裝為無需 root 的 Android App。
Kotlin + Jetpack Compose 原生控制介面採 Material 3 Expressive；完整管理功能沿用 3x-ui 網頁面板，由預設瀏覽器開啟。

[繁體中文完整說明](docs/zh-TW/README.md) · [English documentation](docs/en/README.md) · [研究報告](3xui_Study.md) · [進度](docs/PROGRESS.md)

## 需求

- Android 8.0 / API 26 以上，`arm64-v8a`。
- 建置：JDK 17、Android SDK 36 / Build Tools 36.0.0、Android NDK r30、Go 1.27.1、Node.js 24 以上、npm、Python 3.11 以上、Git、curl。
- 設定 `ANDROID_HOME`、`ANDROID_NDK_HOME`；或使用 Android Studio 的 SDK 設定與 `local.properties`。

## 建置與使用

```bash
./scripts/build-core.sh
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

開啟 XrayDroid →「啟動服務」→「開啟管理面板」。
面板固定為 `http://127.0.0.1:2053/`。首次登入沿用上游公開預設帳密 `admin` / `admin`，請立即在面板設定中更改。
使用高於 1024 的入站連接埠；背景服務可從 App 或通知停止。
「出站網路」可偵測介面與 IP，選擇跟隨系統、Wi-Fi、行動網路或乙太網路。切換會重啟核心；指定網路中斷時等待恢復，不會改走其他網路。

## 狀態

目前為首版工程原型：原生控制介面、前景服務、版本固定的核心封裝與 Android 修補已實作；驗證結果請參閱[進度紀錄](docs/PROGRESS.md)。
M3E 適用於原生介面；既有網頁面板保留上游樣式。第一版不支援 MTProto / TUIC 側車程序、Linux 系統管理與核心自行更新。
本 App 提供本機伺服器，尚未實作 Android VPNService（VPN 服務）或全裝置流量接管。

## 授權

專案採 [GPL-3.0](LICENSE)。上游來源、版本與個別授權見 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
