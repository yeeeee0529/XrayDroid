# 進度與驗證紀錄

日期：2026-10-01。

首頁顯示修正：移除副標題，重新啟動按鈕改用狀態卡對應前景色與外框；Kotlin 編譯、ktlint 與 lint、APK build 通過。此次為純顯示變更，未新增測試；真機視覺複查仍受裝置鎖定限制。

[專案首頁](../README.md) · [繁體中文](zh-TW/README.md) · [English](en/README.md)

## 設定頁與逐介面選擇交接

本次完成：加入 M3E 設定頁，將出站網路移到設定頁，獨立顯示 App 可見的 VPN／虛擬介面。基底 commit 為 `6a2bfff`；本次使用 Conventional Commit（慣例提交）訊息 `feat: add settings and per-interface network selection`。

### 已完成的設計與檔案

- `MainActivity.kt` 保存首頁／設定頁導覽狀態，處理 Android 返回鍵；`ServerDashboard.kt` 提供設定入口並保留核心實際使用網路。
- `ui/SettingsScreen.kt` 提供設定頁與返回；`ui/NetworkCard.kt` 逐一顯示介面、IP、DNS、狀態與不可選原因，提供重新偵測。
- `runtime/NetworkStore.kt` 合併 Android 可見網路與 `NetworkInterface` 列舉結果；VPN 不再歸入 SYSTEM。列舉到名稱但無可綁定 Android 網路、未啟用或本機回送介面仍顯示，但不可選。
- `runtime/NetworkSelection.kt` 的純選擇規則與 JVM test 保證指定介面不存在／不可用時不回退，並保留舊網路類型偏好的相容性。
- `XuiService.kt` 觀察模式、介面名稱與當前 handle。核心啟動前做原生綁定預檢；失敗回報介面不可用，不偷偷改走預設路由。此次未修改 3x-ui／Xray 原生修補或版本。

### 接手時須保留的行為

1. 指定介面優先於模式；持久化介面名稱，不能跨啟動保存 Android Network handle。失效介面仍保留選擇並等待恢復。
2. SYSTEM 且未指定介面才使用 handle `0`；預設網路改變不重啟核心。指定介面換 handle／消失時重啟或等待。
3. 介面對應的是 Android 網路；實際底層封包路由由 Android 決定，不承諾以 Linux 裝置名稱強制綁定。`LinkProperties.getStackedLinks` 屬隱藏 API，不能使用或以 reflection（反射）繞過；CLAT 等沒有公開網路關聯的子介面僅顯示，不能靠名稱猜測 handle。
4. App 可見介面受 Android 權限與版本限制，不能保證列出其他使用者／App 專用或沒有 IP 的所有介面。不得為此讀取使用者資料庫或後端日誌。
5. 手動重新偵測可重試原生綁定；自動網路回呼不可不斷清除綁定失敗狀態，以免無限重啟。

### 驗證與交付

```bash
./gradlew :app:ktlintFormat
./gradlew -PvalidationApplicationId=io.github.xraydroid.validation \
  :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest \
  :app:assembleDebug :app:connectedDebugAndroidTest
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

本機需設定 JDK 17 的 `JAVA_HOME`、`ANDROID_HOME` 與可寫的 `GRADLE_USER_HOME`。正式套件 APK 必須在未傳入 `validationApplicationId` 時重新 build；不得把測試套件當成正式更新交付。

- `:app:ktlintFormat`、`:app:ktlintCheck`、`:app:lintDebug`、`:app:testDebugUnitTest`、`:app:assembleDebug`：通過。6 個 JVM 選擇規則 test 全數通過；Android lint 無 issues。
- Pixel 9 Pro XL / Android 17 完整裝置 suite：7 項，6 通過、1 行動網路不可用 skipped、0 failures、0 errors。設定頁進入、指定介面切回系統、重新偵測、按鈕返回與 Android 系統返回鍵均通過；指定真實 Wi-Fi 介面／失效介面等待／恢復／停止後不復活、舊模式切換、TCP／UDP DNS 與既有面板生命週期均通過。
- 開發中曾遇隱藏 `stackedLinks` API 編譯失敗與 `clearCapabilities` API 30 lint 錯誤，已移除隱藏 API 並補 API 26–29 相容路徑。導覽 test 曾因已選取單選項不提供無障礙點擊而失敗，已改成先指定介面再實際切回系統；最終完整 suite 通過。
- 此次原生核心未修改，沿用前輪已驗證的核心 build；Gradle 包裝 `verifyCore` 通過，未重跑 Go suite。
- 正式套件 `:app:assembleDebug` 通過；`adb install -r` 更新成功，保留帳號／資料庫，已開啟正式 App。APK：`app/build/outputs/apk/debug/app-debug.apk`。本次變更完成後 commit；本專案未設定 remote，不 push。
- 裝置測試使用 `-PvalidationApplicationId=io.github.xraydroid.validation`，保留正式套件資料；正式服務須停止以釋放面板固定連接埠。
- 本輪裝置未鎖定，導覽 test 實際執行通過；未擷取設定頁視覺截圖。尚須在實際 VPN／`tun1` 上驗證 TCP、UDP、DNS 與中斷恢復；邏輯測試不等於 VPN 實際傳輸驗證。
- 後續優先事項：實際 VPN／虛擬網卡驗證、不同 Android／OEM 的介面可見性、多個同類型介面重連，以及設定頁視覺複查。

## 出站網路切換

- 新增 Wi-Fi、行動網路、乙太網路的介面名稱、IP、DNS 與驗證狀態偵測；選擇持久化。
- 系統模式沿用預設路由；指定模式使用 Xray 原生 DNS 與逐通訊端網路綁定。切換重啟核心；指定網路不可用時等待，不回退其他網路。
- 裝置測試採 `io.github.xraydroid.validation` 隔離套件；正式 App 資料保留。
- 完整 `./scripts/build-core.sh` 已通過，兩個核心從固定來源編譯，保留官方資產驗證。
- Xray 相關 test、Android arm64 build、16 KB ELF 對齊與模組驗證通過。完整 Go suite 初次因缺少 geodata 測試資產失敗；補齊官方資產後三個失敗套件重跑全部通過，其餘套件及完整 scenario 通過。
- Xray Go vet 修改範圍除上游未修改的 VLESS `inbound.go:585/586` 兩個 `unsafe.Pointer` 警告外通過；未將整體 vet 記為通過。
- 第一輪獨立核心真機測試：3 項，2 通過、1 跳過、0 failures。Wi-Fi TCP 對外路徑比較、UDP DNS 與無效 handle 拒絕連線通過；行動網路不可用而跳過。
- Kotlin 編譯、ktlint 與 Android lint 通過；JVM 單元測試為 NO-SOURCE。
- 完整真機 suite 首輪 5 項：3 通過、1 行動網路跳過、1 VLESS 入站監聽逾時；網路切換、無可用網路等待、恢復及快速停止競態皆通過。VLESS 生命週期 test 單獨重跑通過。來源確認：上游新增入站 RPC 失敗時仍回報資料儲存成功，待每 30 秒排程重新啟動；測試原 10 秒等待過短，已改 40 秒涵蓋排程及重啟。最終完整 suite：5 項，4 通過、1 行動網路跳過、0 failures、0 errors。包含 TCP／UDP DNS、無效網路拒絕、完整服務切換與既有面板／生命週期；ktlint、lint、APK build 通過。
- 正式套件 APK build 與 `adb install -r` 更新成功，保留使用者資料。前輪交付時手機鎖定，無法從首頁重新啟動服務；私人服務拒絕 shell 直接啟動，需解鎖後點「啟動服務」。
- 尚未驗證實際拔線／無線電中斷後的自動恢復、行動網路傳輸、乙太網路傳輸與所有協定。

## 已實作

- Kotlin / Jetpack Compose Material 3 Expressive 原生控制介面。
- specialUse 前景服務、通知停止、核心 readiness（就緒）檢查與啟停序列化。
- arm64 執行檔封裝、地理資料安裝、私有資料庫與日誌目錄。
- 相同 UID 與執行路徑的殘留程序清理；上游父程序死亡保護。
- 上游原生執行路徑、面板 loopback / HTTP 固定入口、self-update 與不支援功能 guard（防護條件）。
- 固定來源版本、下載驗證、上游前端實際 build、三份 README 與第三方來源說明。

## 首版驗證狀態

- 官方 release 確認：3x-ui 最新正式版 v3.8.5；Xray v26.6.27 存在 Android arm64 發行檔。
- 3x-ui 上游前端 `npm ci` / `npm run build`：通過。
- Go 相關套件 test、回歸 test、Go vet、gofmt、golangci-lint（修改範圍 0 issues）：通過。
- 完整 `go test ./...` 已執行一次：未修改的 Discord `TestGatewayRequestedHeartbeatDoesNotRaceTicker` 發生 `broken pipe`；該 test 單獨重跑五次皆通過。其他套件通過；完整 suite 不記為全數通過。
- 3x-ui NDK Android arm64 PIE build：通過。
- Xray 官方發行檔雜湊驗證：通過。
- 兩個原生執行檔 ELF（執行檔格式）LOAD 段 16 KB 對齊：通過。
- 完整 `./scripts/build-core.sh`：通過，包含官方 Xray zip / dgst 的 SHA256 雙驗證與真實嵌入前端。
- Android Kotlin 編譯、APK build、ktlint 格式／檢查、lint：通過。
- `:app:testDebugUnitTest`：NO-SOURCE（尚無 JVM 單元測試），不列為 test 通過。
- Pixel 9 Pro XL / Android 17 裝置 test：1 個 test、0 failures、0 errors、0 skipped。
- 真機已驗證：一般 App UID 執行 `nativeLibraryDir` 兩個核心、嵌入前端、HTTP / CSRF 登入、VLESS TCP 入站新增／監聽／刪除、背景面板、SIGKILL 後清理、重新啟動、正常停止、資料庫檔案保留。
- 管理面板驗證涵蓋 VLESS listener（監聽端點）；尚未驗證完整代理資料傳輸或所有協定。

## 尚未證實的範圍

- 所有 Xray 協定、路由設定與新版 3x-ui 欄位對 v26.6.27 的相容性。
- 不同 Android 版本、OEM、省電策略與長時間螢幕關閉。
- Google Play specialUse 審核與正式 APK 簽署／散布。
- 真機原生介面截圖與視覺檢查：裝置鎖定，尚未完成。
- Android VPNService 全裝置流量接管、原生完整管理面板與非 arm64 ABI。

原始研究的 shell UID PoC 不視為 APK 的 App UID 驗證；後者必須由實際裝置測試確認。
