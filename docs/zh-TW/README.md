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
建置腳本下載官方來源、驗證 3x-ui commit 與 Xray 發行檔 SHA256，套用版本固定的修補，實際 build 上游前端與三個 Go 執行檔。Xray 由固定 commit 原始碼編譯，官方發行檔提供地理資料與授權。
frpc v0.71.0 由固定 commit 與 Go 模組校驗編譯，Android 修補保留完整用戶端，加入程序生命週期與私有狀態控制端點。
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

從首頁右上「設定」→「出站網路」進入；返回按鈕或 Android 返回手勢先回設定索引，再回首頁。出站網路子頁逐一列出 App 可見的介面（包括 `wlan0`、`tun1` 等）、IP、DNS 與連線狀態，支援「重新偵測」。介面名稱依裝置實際情況顯示，不由 App 建立。

選擇會儲存並於下次啟動沿用。指定介面以名稱對應當下的 Android 網路，不儲存會隨重連變動的網路 handle（控制代碼）；介面消失後仍保留選擇並等待恢復，不改選其他同類型網路。既有依網路類型的選擇設定仍相容。

VPN 會獨立顯示，不再合併為系統預設。僅列舉到名稱但沒有可用 Android 網路、介面關閉或本機回送介面，會顯示原因並停用選擇。Android 可能不公開其他使用者／其他 App 專用的介面，因此無法保證列出所有虛擬介面。核心實際綁定失敗時亦不回退，須重新偵測或改選其他介面。

- 跟隨系統：沿用 Android 預設路由，包括系統 VPN。
- 指定網路：將 Xray 出站通訊端與系統 DNS 綁定介面所屬的 Android 網路；可能繞過系統 VPN。選擇行動網路時會向 Android 請求保持該網路可用。
- 服務執行中切換會重啟 3x-ui 與 Xray，現有連線會中斷。指定網路消失時服務進入等待，恢復後重新啟動；不自動回退至其他網路。

選取介面會綁定其所屬 Android 網路，實際封包使用哪個底層介面由 Android 決定。CLAT 等子介面若沒有公開 API 提供的網路關聯，僅列出資訊，不提供選擇；不使用隱藏 API 或依名稱猜測關聯。

管理面板與入站監聽保持原本路由。手動選網路時不支援 xicmp。無線電關閉、無 SIM 或電信限制可能使行動網路無法啟用。

## frp 用戶端

「設定」→「frp」提供完整 frpc v0.71.0 用戶端，連線至外部 frps；手機不提供 frps 伺服器。此子頁與出站網路分開，frpc 使用獨立的 Foreground Service（前景服務）與通知，啟停不影響 3x-ui／Xray。frpc 沿用 Android 系統路由，包括系統 VPN；Xray 的指定出站介面不套用至 frpc。

1. 選擇「表單配置」或「TOML 配置」。表單依基本設定、驗證、傳輸、TLS 與其他設定分組，展開需要的區塊即可編輯；支援 Token／OIDC、全部 TCP／KCP／QUIC／WebSocket／WSS 傳輸協定與中繼資料。
2. 「轉發規則」可新增、編輯與刪除 TCP、UDP、HTTP、HTTPS、STCP、SUDP、XTCP、TCPMUX 代理；「訪客規則」支援 STCP、SUDP、XTCP。各協定顯示對應的網域、路徑、密鑰、監聽、穿透及備援等欄位。規則協定在建立時選擇，要改協定請新增另一筆規則。
3. 兩種模式共用同一份完整 TOML 草稿。切換至表單會解析 TOML；語法或欄位結構無法轉換時保留原文並留在 TOML 模式。僅切換模式不改寫原文；實際表單修改會標準化排版並移除註解，但保留未編輯欄位、外掛、訪客與 `includes`。外掛及其他未提供表單的進階選項可在 TOML 編輯。無效數字保留於表單中，須修正後才能切換模式、驗證、儲存或啟動。匯入取代草稿仍需確認。點「驗證設定」或「儲存設定」交由官方 frpc 嚴格驗證，通過後才原子寫入私有目錄。
4. 點「啟動 frpc」；啟動與重新啟動會先驗證、儲存草稿。執行中儲存設定後需重新啟動套用。首頁服務與 frpc 分別控制，關閉設定頁不會停止 frpc。
5. 代理列表顯示核心回報的代理狀態；「用戶端運作中」表示程序與本機狀態端點可用，不保證全部代理已連線。可從子頁或 frp 通知停止。

「允許外部權杖指令」預設關閉，與上游安全預設一致。若設定 `tokenSource` 透過外部指令取得權杖，需先停止 frpc，再開啟此選項；驗證與啟動會啟用 `TokenSourceExec`。外部指令使用此 App 的權限，其執行檔仍須符合 Android 可執行路徑限制；從檔案選擇器匯入支援檔案不代表該檔案可執行。此選項會獨立儲存，不改寫 TOML。

完整 TOML 支援上游代理、訪客、驗證、傳輸、TLS、外掛及其他進階欄位，實際功能受 Android App UID 與系統權限限制。VirtualNet（虛擬網路）的 TUN 裝置功能需要系統支援與權限；本 App 沒有 Android VPNService（VPN 服務），不提供 TUN 或全裝置流量接管。外掛只能使用 App 可存取的資源；不能假設 Linux 系統檔案或特權可用。各協定與進階組合的實際驗證範圍見[進度紀錄](../PROGRESS.md)。

TOML 匯入限制為 UTF-8、1 MiB。「匯入憑證或檔案」將單檔最多 16 MiB 的資料複製至私有 `support/UUID` 檔名並保留安全的副檔名（例如 `.toml`、`.pem`），畫面顯示可填入 TOML 的相對路徑；檔案內容不顯示。請調整 TLS 憑證、驗證檔案、外掛檔案等路徑；`includes` 萬用字元可使用，但匯入的檔名已更換，須依實際路徑調整。返回時會確認捨棄未儲存草稿；畫面重建不保留未儲存設定。

設定存在 `filesDir/server/frp/frpc.toml`，不寫入 Activity 儲存狀態；frp 頁面停用螢幕擷取。核心原始日誌與驗證錯誤輸出會丟棄，介面僅顯示不含原始輸出的診斷。App 保留本機控制端點：啟動時覆寫上游 `webServer` 設定為 loopback（本機回送）臨時連接埠與一次性憑證、停用控制端點 TLS，並覆寫日誌與 `loginFailExit = false`，讓連線失敗由 frpc 重試；儲存的 TOML 不因此修改。frps 中斷後由核心重新連線，並非每次重建 Android 服務。

## 架構

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
frpc 轉發測試需要本機測試伺服器。在另一個終端執行以下指令，再跑上面的隔離裝置測試；未啟動此伺服器時，TCP／UDP 轉發項目會略過。測試伺服器僅監聽本機，使用建置產生的 `.core-cache/frps`；測試後以 Ctrl+C 停止，並移除這兩個測試轉送。

```bash
adb reverse tcp:17000 tcp:17000
adb reverse tcp:18080 tcp:18080
python3 scripts/frp-validation-server.py
# 測試完成並停止伺服器後
adb reverse --remove tcp:17000
adb reverse --remove tcp:18080
```

frpc 表單使用 tomlj 1.1.1 解析完整 TOML；不自行以字串切割判斷 TOML 語法。新增 Compose UI test 驗證模式切換與草稿保留；測試用 Espresso 固定為 3.7.0，以支援 Android 17 輸入操作。

frpc 裝置測試涵蓋 TCP／UDP 實際往返、斷線重連、獨立啟停、無效設定保留、支援檔案匯入，以及外部權杖指令與父程序死亡的群組清理；不等同於所有 frp 協定／外掛已逐一驗證。
裝置測試請使用隔離套件與開發裝置；既有正式套件須先停止服務以釋放固定面板連接埠，測試不會移除正式套件資料。已執行項目與限制見[進度紀錄](../PROGRESS.md)。
3x-ui 的 Go 型別依賴比 v26.6.27 核心新；新增協定或較新欄位不保證可用，須對實際設定逐項驗證。

## 授權與來源

[GPL-3.0](../../LICENSE) · [第三方聲明](../../THIRD_PARTY_NOTICES.md) · [原始研究](../../3xui_Study.md)。
3x-ui v3.8.5 固定於 `7ef22f94c950ff09f0870e2295fa65ad5968742c`；研究報告使用的 commit 與正式 release 不同，以建置腳本為準。
