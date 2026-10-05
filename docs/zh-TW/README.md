# XrayDroid 使用與開發

[專案首頁](../../README.md) · [English](../en/README.md) · [简体中文](../zh-CN/README.md) · [交接文檔](../PROGRESS.md)

## 功能與範圍

- 不需 root，以一般 Android App 執行 3x-ui 與 Xray-core；支援 Android API 26 以上的 arm64 裝置。

- 介面提供服務狀態、啟停、重啟、生命週期日誌與瀏覽器入口。

- 入站、用戶端、路由與流量統計使用 3x-ui 網頁面板。

- 此為**本機代理伺服器**，**不是**代理工具，不接管其他 App 流量

- 目前不支援 MTProto / TUIC 側車程序與面板／Xray 自行更新。

- Android 讀不到的系統統計以警告或零值處理；Linux 的 systemd、Fail2ban、syslog、安裝器不屬於 App 功能，部分上游選單仍可能顯示。

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

- SDK 也可改用 `local.properties` 的 `sdk.dir` 指定；macOS 的 NDK 預設在 `/opt/homebrew/share/android-ndk`。
- `build-core.sh` 下載官方來源（首次建置需網路）、驗證 3x-ui commit 與 Xray SHA256、套用固定修補，build 上游前端與三個 Go 執行檔。
- Gradle 不自動下載核心，缺檔案時拒絕打包 APK。

## 使用

1. 開啟 App 並點「啟動服務」，選擇是否同意通知權限。
2. 等待服務就緒，再點「開啟管理面板」。
3. 首次登入使用 3x-ui 預設帳密 `admin` / `admin`，建議登入後到面板設定更改。
4. 建立入站，需要使用高於 1024 的連接埠。對其他裝置開放入站須使用可達的監聽位址，並確認所在網路可連入。
5. 可從 App 或常駐通知停止；關閉首頁不會停止服務。

管理面板固定使用 `http://127.0.0.1:2053/`，Android 修補強制 HTTP、根路徑與 loopback 監聽。面板中的連接埠、路徑、TLS 或監聽位址設定不會覆蓋 Android 的固定入口；訂閱伺服器與入站仍依各自設定運作。App 僅顯示自身生命週期日誌。
核心更新需重新 build 並安裝 APK。

## 關於出站網路

從首頁右上「設定」→「出站網路」進入。子頁逐一列出 App 可見的介面、IP、DNS 與連線狀態，可手動重新偵測；名稱依裝置實際情況顯示。

選擇會儲存並於下次啟動繼續沿用。指定介面以名稱對應當下的 Android 網路；介面消失後仍保留選擇並等待恢復，不會改選其他同類型網路。

VPN 獨立顯示，不併入系統預設。沒有可用 Android 網路、已關閉或本機回送的介面會標示原因並停用選擇；Android 可能不公開他人或其他 App 專用的介面，不保證能列出所有虛擬介面。選取後綁定介面所屬的 Android 網路，實際封包走哪個底層介面由 Android 決定。

- 跟隨系統：沿用 Android 預設路由，含系統 VPN。
- 指定網路：將 Xray 出站通訊端與系統 DNS 綁定介面所屬的 Android 網路，可能繞過系統 VPN；選行動網路時會請求 Android 保持該網路可用。
- 切換：服務執行中切換會重啟 3x-ui 與 Xray，現有連線將會中斷；指定網路消失時進入等待，恢復後重新啟動。

## frp 用戶端

「設定」→「frp」提供完整 frp 用戶端，連線至外部 frp 服務端；手機不提供 frp 伺服器。此子頁與出站網路分開，frpc 使用獨立的 Foreground Service 與通知，啟停不影響 3x-ui／Xray。frpc 沿用 Android 系統路由，包括系統 VPN；Xray 的指定出站介面不套用至 frpc。

1. 可選「表單配置」或「TOML 配置」。表單依基本設定、驗證、傳輸、TLS 與其他設定分組，展開需要的區塊即可編輯；支援 Token／OIDC、全部 TCP／KCP／QUIC／WebSocket／WSS 傳輸協定與中繼資料。

2. 「轉發規則」可新增、編輯與刪除 TCP、UDP、HTTP、HTTPS、STCP、SUDP、XTCP、TCPMUX 代理；「訪客規則」支援 STCP、SUDP、XTCP。各協定顯示對應的網域、路徑、密鑰、監聽、穿透及備援等欄位。規則協定在建立時選擇，要改協定請新增另一筆規則。

3. 兩種模式共用同一份完整 TOML。切換至表單會解析 TOML；語法或欄位結構無法轉換時保留原文並留在 TOML 模式。僅切換模式不改寫原文；實際表單修改會標準化排版並移除註解，但保留未編輯欄位、外掛、訪客與 `includes`。外掛及其他未提供表單的進階選項可在 TOML 編輯。無效數字保留於表單中，須修正後才能切換模式、驗證、儲存或啟動。匯入取代草稿仍需確認。

4. 點「啟動 frpc」；啟動與重新啟動會先驗證、儲存草稿。執行中儲存設定後需重新啟動套用。首頁服務與 frpc 分別控制，關閉設定頁不會停止 frpc。

5. 頁面最上方區塊顯示核心回報的「正在連線至 frps」、「已連線至 frps」、「連線失敗，重試中」、「已斷線，重新連線中」或「連線狀態無法取得」。失敗時顯示可供使用者判定的 DNS、拒絕連線、逾時、TLS、驗證準備、登入遭拒或登入前連線遭關閉原因與嘗試次數。

6. 「已連線」代表成功登入 frps，即使沒有代理或只設定訪客也能確認連線；代理已啟用仍不保證本機目標服務可用。無法取得狀態時清除先前結果，不沿用「已連線」。可從子頁或 frp 通知停止。

「允許外部權杖指令」預設關閉，與上游安全預設一致。若設定 `tokenSource` 透過外部指令取得權杖，需先停止 frpc，再開啟此選項；驗證與啟動會啟用 `TokenSourceExec`。外部指令使用此 App 的權限；從檔案選擇器匯入支援檔案不代表該檔案可執行。此選項會獨立儲存，不改寫 TOML。

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

frpc 裝置測試涵蓋 TCP／UDP 實際往返、斷線重連、獨立啟停、無效設定保留、支援檔案匯入，以及外部權杖指令與父程序死亡的群組清理；不等同於所有 frp 協定／外掛已逐一驗證。
裝置測試請使用隔離套件與開發裝置；既有正式套件須先停止服務以釋放固定面板連接埠，測試不會移除正式套件資料。已執行項目與限制見[進度紀錄](../PROGRESS.md)。

## 授權與來源

[GPL-3.0](../../LICENSE) · [第三方聲明](../../THIRD_PARTY_NOTICES.md) 