# XrayDroid 使用與開發

[專案首頁](../../README.md) · [English](../en/README.md) · [進度](../PROGRESS.md)

## 功能與範圍

XrayDroid 在一般 Android App UID 下執行 3x-ui v3.8.5 與 Xray-core v26.6.27，無需 root。
Kotlin + Jetpack Compose 的 Material 3 Expressive 原生首頁提供服務狀態、啟動、停止、重新啟動、生命週期日誌及瀏覽器入口。
入站、用戶端、路由與流量統計使用既有 3x-ui 網頁管理介面；該介面沒有改為 M3E。

支援 Android API 26 以上的 arm64 裝置。此 App 是本機伺服器，不會透過 Android VPNService 接管其他 App 的流量。
第一版停用 MTProto / TUIC 側車程序、面板與 Xray 自行更新。Android 無法讀取的系統統計由上游以警告或零值處理。
Linux 的 systemd、Fail2ban、syslog 與安裝器不屬於 Android App 功能；部分上游選單仍可能顯示。

## 建置

準備 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK r30、Go 1.27.1、Node.js 24 以上、npm、Python 3.11 以上、Git、curl。

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_NDK_HOME="/path/to/android-ndk"
./scripts/build-core.sh
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可用 `local.properties` 的 `sdk.dir` 指向 SDK。macOS 的 NDK 預設位置是 Homebrew 安裝路徑 `/opt/homebrew/share/android-ndk`。
建置腳本下載官方來源、驗證 3x-ui commit 與 Xray 發行檔 SHA256，套用版本固定的修補，實際 build 上游前端與兩個 Go 執行檔。Xray 由固定 commit 原始碼編譯，官方發行檔提供地理資料與授權。
Gradle 不會自動下載核心；缺少核心檔案時會明確拒絕包裝 APK。
Material 3 固定使用 `1.5.0-alpha04`，因穩定版 `1.4.0` 尚未公開 `MaterialExpressiveTheme`；升級時需重新驗證原生介面。
首次建置需要網路。`.tools/`、`.core-cache/`、`upstream/`、原生核心與產生的資產不加入 Git。

## 使用

1. 開啟 App 並點「啟動服務」，選擇是否允許通知。
2. 等待服務就緒，再點「開啟管理面板」。
3. 首次登入使用上游公開預設帳密 `admin` / `admin`，立即到面板設定更改。
4. 建立入站，使用高於 1024 的連接埠。對其他裝置開放入站須使用可達的監聽位址，並確認所在網路可連入。
5. 可從 App 或常駐通知停止；關閉首頁不會停止服務。

管理面板固定使用 `http://127.0.0.1:2053/`，Android 修補強制 HTTP、根路徑與 loopback（本機回送）監聽。
面板中的連接埠、路徑、TLS 或監聽位址設定不會覆蓋 Android 的固定入口；訂閱伺服器與入站仍依各自設定運作。
App 僅顯示自身生命週期日誌，避免將核心輸出可能包含的登入資料顯示於原生介面。
核心更新需重新 build 並安裝 APK；不從可寫資料目錄執行下載的程式。

管理面板的儲存統計讀取 App 私有資料目錄所在的分割區，顯示整個分割區的容量與使用量，並非 App 本身佔用的大小。

## 出站網路

從首頁右上「設定」進入出站網路設定；返回按鈕或 Android 返回手勢可回首頁。設定頁逐一列出 App 可見的介面（包括 `wlan0`、`tun1` 等）、IP、DNS 與連線狀態，支援「重新偵測」。介面名稱依裝置實際情況顯示，不由 App 建立。

選擇會儲存並於下次啟動沿用。指定介面以名稱對應當下的 Android 網路，不儲存會隨重連變動的網路 handle（控制代碼）；介面消失後仍保留選擇並等待恢復，不改選其他同類型網路。既有依網路類型的選擇設定仍相容。

VPN 會獨立顯示，不再合併為系統預設。僅列舉到名稱但沒有可用 Android 網路、介面關閉或本機回送介面，會顯示原因並停用選擇。Android 可能不公開其他使用者／其他 App 專用的介面，因此無法保證列出所有虛擬介面。核心實際綁定失敗時亦不回退，須重新偵測或改選其他介面。

- 跟隨系統：沿用 Android 預設路由，包括系統 VPN。
- 指定網路：將 Xray 出站通訊端與系統 DNS 綁定介面所屬的 Android 網路；可能繞過系統 VPN。選擇行動網路時會向 Android 請求保持該網路可用。
- 服務執行中切換會重啟 3x-ui 與 Xray，現有連線會中斷。指定網路消失時服務進入等待，恢復後重新啟動；不自動回退至其他網路。

選取介面會綁定其所屬 Android 網路，實際封包使用哪個底層介面由 Android 決定。CLAT 等子介面若沒有公開 API 提供的網路關聯，僅列出資訊，不提供選擇；不使用隱藏 API 或依名稱猜測關聯。

管理面板與入站監聽保持原本路由。手動選網路時不支援 xicmp。無線電關閉、無 SIM 或電信限制可能使行動網路無法啟用。

## 架構

```text
MainActivity → XuiService → nativeLibraryDir/libxui.so → libxray.so
                  ↓
filesDir/server/{db,xray,log}
                  ↓
Browser → http://127.0.0.1:2053/
```

`XuiService` 使用 specialUse 類型的 Foreground Service（前景服務），按使用者指令啟停。
啟動前複製地理資料，設定 `XUI_XRAY_BINARY`、`XUI_BIN_FOLDER`、`XUI_DB_FOLDER`、`XUI_LOG_FOLDER`、`XRAY_LOCATION_ASSET`。
正常停止先發送 SIGTERM，再於逾時後強制停止。異常清理只比對相同 App UID 與確切執行檔路徑，避免影響其他 Xray。
上游修補另外處理父程序死亡；前景服務不能保證抵抗所有系統回收、Doze（低耗電模式）或使用者強制停止。

## 驗證

```bash
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew -PvalidationApplicationId=io.github.xraydroid.validation :app:connectedDebugAndroidTest
```

裝置測試驗證原生程式執行、嵌入前端、Xray 子程序、面板登入、VLESS TCP 入站新增／監聽／刪除、背景面板、強制終止後清理、重啟、停止與資料庫檔案保留。
裝置測試請使用隔離套件與開發裝置；既有正式套件須先停止服務以釋放固定面板連接埠，測試不會移除正式套件資料。已執行項目與限制見[進度紀錄](../PROGRESS.md)。
3x-ui 的 Go 型別依賴比 v26.6.27 核心新；新增協定或較新欄位不保證可用，須對實際設定逐項驗證。

## 授權與來源

[GPL-3.0](../../LICENSE) · [第三方聲明](../../THIRD_PARTY_NOTICES.md) · [原始研究](../../3xui_Study.md)。
3x-ui v3.8.5 固定於 `7ef22f94c950ff09f0870e2295fa65ad5968742c`；研究報告使用的 commit 與正式 release 不同，以建置腳本為準。
