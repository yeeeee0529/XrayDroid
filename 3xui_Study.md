# 3x-ui 嵌入 Android App 可行性研究

## 1. 研究目標

目標是確認是否能把 **3x-ui 後端服務嵌入 Android App**，並保留原本 Web Panel，由瀏覽器存取，而不是重寫 Android 原生前端。

預期架構：

```text
Android App
    ↓
Foreground Service
    ↓
3x-ui backend
    ↓
Xray-core
    ↓
Browser
http://127.0.0.1:<panel-port>
```

Android App 本身只負責：

- 啟動 / 停止 3x-ui
- 維持背景服務
- 管理 App lifecycle
- 提供「開啟 Web Panel」入口

Web UI、SQLite 資料庫與 Xray 管理仍沿用 3x-ui 現有實作。

---

## 2. 結論

目前證據已足以判斷：

> **3x-ui 在 Android 上運行的核心方案高度可行。**

目前不是在判斷「3x-ui 能不能跑」，而是在處理「如何安全、符合 Android 機制地封裝成 rootless APK」。

已在 Android 17 / API 37 / arm64-v8a / SELinux Enforcing / 無 root 環境完成真機 PoC。

核心功能結果：

| 項目 | 結果 |
|---|---|
| 3x-ui Android arm64 編譯 | PASS |
| 3x-ui 真機啟動 | PASS |
| Embedded Web Panel | PASS |
| HTTP 登入流程 | PASS |
| SQLite 建立 / 寫入 / 持久化 | PASS |
| Xray Android arm64 執行 | PASS |
| 3x-ui 啟動 Xray child process | PASS |
| VLESS TCP inbound | PASS |
| Xray restart | PASS |
| Xray graceful stop | PASS |
| Panel graceful shutdown | PASS |
| SELinux Enforcing | PASS |
| Rootless `/data/local/tmp` PoC | PASS |
| 真正 APK App UID | 尚未驗證 |
| nativeLibraryDir 執行 | 尚未驗證 |
| Foreground Service | 尚未實作 |

因此目前可以把專案從：

```text
可行性研究
```

推進到：

```text
Android APK 工程化 / 封裝階段
```

---

## 3. Android 編譯結果

測試的 3x-ui：

```text
3x-ui version: 3.8.5
commit:
99047c0a6394af4f74a3105220f2910ef615dce8
```

Android build：

```text
GOOS=android
GOARCH=arm64
CGO_ENABLED=1
Android API 24
Android NDK r30
```

成功產生：

```text
x-ui-android-arm64
```

Binary：

```text
ELF 64-bit
AArch64
PIE
Android interpreter: /system/bin/linker64
```

### 唯一編譯 blocker

原始 build 在 link 階段遇到：

```text
link: github.com/wlynxg/anet:
invalid reference to net.zoneCache
```

原因為 `wlynxg/anet` Android implementation 使用 `//go:linkname` 存取 Go private symbol，而 Go 1.23+ linker 對 linkname 有額外檢查。

最後使用：

```bash
go build   -ldflags=-checklinkname=0   -o x-ui-android-arm64   .
```

即可成功編譯。

沒有修改 3x-ui source。

並確認 `wlynxg/anet` 最終被 dead-code elimination 移除，因此沒有進入 final binary。

---

## 4. SQLite 相容性

3x-ui 使用：

```text
github.com/mattn/go-sqlite3
```

因此 Android build 必須：

```text
CGO_ENABLED=1
```

並使用 Android NDK clang。

Android 真機實測：

```text
x-ui.db
x-ui.db-shm
x-ui.db-wal
```

皆正常建立。

已實際驗證：

- 建立設定
- 建立 API token
- 建立 inbound
- Panel restart
- DB persistence
- 重新讀取設定

全部正常。

因此 SQLite 不需要：

- 更換 driver
- 更換 ORM
- PostgreSQL
- database layer rewrite

---

## 5. Web Panel

3x-ui 的 React frontend 透過 Go `embed` 直接包進 backend binary。

因此 Android 不需要：

- WebView
- Node.js runtime
- Nginx
- 額外 frontend server
- Android 原生 UI 重寫

真機實測：

```text
HTTP :2053
```

成功取得：

```text
/
assets/*.js
assets/*.css
manifest.webmanifest
/panel/
```

登入：

```text
POST /login
```

亦成功。

因此最終 App 可以直接：

```text
Open Panel
    ↓
ACTION_VIEW
    ↓
http://127.0.0.1:2053
```

交給 Chrome 或預設瀏覽器。

---

## 6. Xray 相容性

測試版本：

```text
Xray 26.3.27
android/arm64
```

3x-ui 成功：

- 啟動 Xray
- 建立 config.json
- 建立 VLESS TCP inbound
- hot-add inbound
- restart Xray
- stop Xray
- cleanup child process

真機 process tree：

```text
x-ui
 └─ xray-android-arm64
```

實際監聽：

```text
Panel:        :2053
Sub server:   :2096
Xray API:     127.0.0.1:<dynamic>
Xray metrics: 127.0.0.1:11111
VLESS:        :18443
```

因此 Xray-core 本身不是 Android porting blocker。

---

# 7. 真正 APK 需要解決的核心問題

## 7.1 executable 與 writable data 必須分離

目前 3x-ui：

```text
XUI_BIN_FOLDER
```

同時管理：

```text
xray executable
config.json
geoip.dat
geosite.dat
```

但 Android rootless APK 不適合這樣。

Android 10+ 對 App 從 writable app data directory 執行下載 binary 有限制。

因此最終應採：

```text
nativeLibraryDir/
├── libxui.so
└── libxray.so

filesDir/
├── xray/
│   ├── config.json
│   ├── geoip.dat
│   └── geosite.dat
│
├── db/
│   └── x-ui.db
│
└── log/
```

建議新增：

```text
XUI_XRAY_BINARY
```

例如：

```text
XUI_XRAY_BINARY=
/data/app/.../lib/arm64/libxray.so

XUI_BIN_FOLDER=
/data/user/0/<package>/files/xray

XUI_DB_FOLDER=
/data/user/0/<package>/files/db

XUI_LOG_FOLDER=
/data/user/0/<package>/files/log
```

### 建議修改

`GetBinaryPath()`：

```text
如果 XUI_XRAY_BINARY 有設定
    → 使用 XUI_XRAY_BINARY

否則
    → 維持目前 XUI_BIN_FOLDER/xray-<os>-<arch>
```

這可以保持 Linux 完整 backward compatibility。

---

## 7.2 XRAY_LOCATION_ASSET

Xray executable 未來在：

```text
nativeLibraryDir/libxray.so
```

但：

```text
geoip.dat
geosite.dat
```

位於：

```text
filesDir/xray/
```

因此 App / 3x-ui 必須在第一個 Xray process 啟動前設定：

```text
XRAY_LOCATION_ASSET=<filesDir>/xray
```

否則部分：

```text
geoip:
geosite:
```

routing rule 可能找不到 asset。

目前 3x-ui 設定 asset location 的時間點太晚，需要提前。

---

## 7.3 Xray self-update 應停用

Android APK 內：

```text
libxray.so
```

屬於 App native code。

Web Panel 不應自行：

```text
download
replace
rename
overwrite
```

nativeLibraryDir 中的 executable。

因此當：

```text
XUI_XRAY_BINARY
```

被設定時，建議直接停用 Xray self-update。

更新方式改為：

```text
更新 Xray
    ↓
build 新 APK
    ↓
App update
```

這更符合 Android application security model。

---

# 8. 目前最重要的 runtime 問題：orphan Xray

正常關閉：

```text
SIGTERM x-ui
    ↓
Xray 正常停止
    ↓
全部 port 釋放
```

沒有問題。

但真機已重現：

```text
kill -9 x-ui
```

會造成：

```text
xray
PPID = 1
```

Xray 繼續存在。

結果：

```text
VLESS port 被占用
Xray API port 被占用
metrics port 被占用
```

重新啟動 3x-ui 時，新 Xray 無法 bind。

典型錯誤：

```text
bind: address already in use
```

這對 Android 很重要，因為：

```text
LMK
App crash
process kill
```

都有可能造成 parent process 非 graceful shutdown。

---

## 8.1 建議解法

啟動新 Xray 前：

```text
掃描 /proc/*
    ↓
讀 /proc/<pid>/exe
    ↓
比對 executable path
    ↓
發現與 XUI_XRAY_BINARY 相同的 orphan
    ↓
終止 orphan
    ↓
啟動新的 Xray
```

不要使用：

```text
killall xray
pkill xray
```

避免誤殺裝置上其他 Xray instance。

可以另外研究：

```go
SysProcAttr{
    Pdeathsig: SIGKILL,
}
```

作為第一層保護。

但仍建議保留 orphan scan 作 crash recovery。

---

# 9. 建議保留的功能

以下功能目前都有充分理由保留。

## 核心

```text
Web Panel
SQLite
Xray lifecycle
Inbound management
User/client management
VLESS
VMess
Trojan
Shadowsocks
REALITY
TCP
WebSocket
gRPC
XHTTP
routing
traffic statistics（可用部分）
```

只要功能本身是由 Xray-core 提供，就大多不需要 Android-specific port。

---

# 10. 建議 Android 版拿掉 / 停用的功能

## 10.1 systemd / systemctl

Android 沒有 systemd。

直接拿掉 Android 相關入口即可。

包括：

```text
systemctl
systemd-run
/etc/systemd/system
```

---

## 10.2 3x-ui self-update

Linux self-update 流程通常假設：

```text
/usr/local/x-ui
systemd
root filesystem
```

不適合 Android。

建議 Android build：

```text
disable
```

改由 APK 更新。

---

## 10.3 Xray self-update

同上。

如果 Xray 位於：

```text
nativeLibraryDir
```

Panel 不應更新。

建議 Android：

```text
disable
```

---

## 10.4 Fail2ban

Android 通常沒有：

```text
fail2ban-client
```

目前 3x-ui 已會安全 fallback。

Android 版可以直接隱藏 / disable。

---

## 10.5 journalctl / syslog

Android 沒有傳統 Unix syslog / journalctl。

目前真機：

```text
syslog backend disabled
```

但 file logging 正常。

建議：

```text
file logging only
```

Android debug 可以另外導到：

```text
logcat
```

但不是必要。

---

## 10.6 MTProto sidecar

目前 `mtg-multi` 沒有現成 Android arm64 release。

第一版建議：

```text
disable
```

未來如果真的需要，再：

- port mtg
- 自行 cross compile
- 或重新設計成 Android-compatible implementation

---

## 10.7 TUIC sidecar

同樣沒有現成 Android binary。

建議第一版：

```text
disable
```

注意：

Xray-core 自己支援的協定與 sidecar 是兩件事。

---

## 10.8 Linux system statistics

真機 SELinux 已阻擋：

```text
/sys/block/dm-*
/sys/block/loop*
```

因此 gopsutil 的 partition enumeration 部分失效。

但：

```text
statfs
CPU
RAM
network
process
```

核心統計仍正常。

Android 可以：

```text
停用 partition enumeration
```

不影響主要功能。

---

# 11. 建議第一版 Android 3x-ui 功能範圍

第一版不要追求 100% Linux 版 feature parity。

建議：

```text
3x-ui Android
│
├─ Web Panel
├─ SQLite
├─ Xray-core
│
├─ VLESS
├─ VMess
├─ Trojan
├─ Shadowsocks
├─ REALITY
├─ TCP / WS / gRPC / XHTTP
│
├─ Routing
├─ Clients
├─ Traffic stats
│
└─ Android Service lifecycle
```

拿掉：

```text
systemd
self-update
Xray self-update
fail2ban
journalctl
syslog backend
MTProto sidecar
TUIC sidecar
Linux partition stats
Linux installer
Linux package management
```

這可以大幅降低 Android fork 維護成本。

---

# 12. 建議 Android App 架構

```text
Android APK
│
├─ MainActivity
│   ├─ Start Server
│   ├─ Stop Server
│   ├─ Restart Server
│   └─ Open Web Panel
│
├─ ForegroundService
│   │
│   └─ ProcessBuilder(libxui.so)
│
├─ nativeLibraryDir
│   ├─ libxui.so
│   └─ libxray.so
│
├─ assets
│   ├─ geoip.dat
│   └─ geosite.dat
│
└─ filesDir
    ├─ xray
    │   ├─ config.json
    │   ├─ geoip.dat
    │   └─ geosite.dat
    │
    ├─ db
    │   └─ x-ui.db
    │
    └─ log
```

Service 啟動時：

```text
建立 filesDir
    ↓
copy geoip / geosite
    ↓
設定 environment
    ↓
啟動 libxui.so
    ↓
3x-ui 啟動 libxray.so
```

Environment：

```text
XUI_XRAY_BINARY=<nativeLibraryDir>/libxray.so
XUI_BIN_FOLDER=<filesDir>/xray
XUI_DB_FOLDER=<filesDir>/db
XUI_LOG_FOLDER=<filesDir>/log
XRAY_LOCATION_ASSET=<filesDir>/xray
```

---

# 13. Foreground Service

3x-ui 不應只由 Activity 啟動。

建議：

```text
MainActivity
    ↓
startForegroundService()
    ↓
XuiService
```

Notification：

```text
3x-ui Server
Running
Panel: 127.0.0.1:2053
```

Foreground Service 可以降低 Android 在背景回收 process 的機率。

但不能假設 process 永遠不會死。

因此：

```text
Foreground Service
+
orphan recovery
```

兩者都需要。

---

# 14. 下一階段需要驗證的項目

目前剩下真正影響「正式可行」的項目很少。

## 必驗證

### 1. App UID

目前 PoC 使用：

```text
shell UID 2000
```

必須再證明：

```text
普通 Android App UID
```

可以：

```text
exec nativeLibraryDir/libxui.so
```

且 libxui 可以：

```text
exec nativeLibraryDir/libxray.so
```

### 2. nativeLibraryDir

驗證：

```text
APK
lib/arm64-v8a/libxui.so
lib/arm64-v8a/libxray.so
```

安裝後可由 App 正常執行。

### 3. Foreground Service

驗證：

```text
Activity 關閉
螢幕關閉
切到背景
```

時服務仍能正常提供：

```text
Panel
Xray inbound
```

### 4. Process death recovery

模擬：

```text
kill x-ui
App process crash
service restart
```

確認：

```text
舊 Xray 被回收
新 Xray 正常啟動
ports 能重新 bind
```

---

# 15. 可行性評估

目前：

```text
Android cross compilation       PASS
Android 17 runtime              PASS
SELinux Enforcing               PASS
No root                         PASS
Embedded Web Panel              PASS
SQLite                          PASS
Xray child process              PASS
VLESS                           PASS
Restart                         PASS
Graceful shutdown               PASS
```

尚未：

```text
APK App UID                     TODO
nativeLibraryDir execution      TODO
Foreground Service              TODO
LMK/crash recovery              TODO
```

因此整體可行性可以評估為：

```text
高
```

目前已不存在會要求：

```text
重寫 3x-ui
重寫 Web Panel
替換 SQLite
gomobile 化整個 backend
libXray 化
要求 root
```

這類大型架構變更的證據。

剩餘工作主要屬於 Android packaging 與 lifecycle engineering。

---

# 16. 最終建議

不要繼續投入一般性的可行性研究。

下一步直接做最小 APK prototype：

```text
libxui.so
libxray.so
Foreground Service
filesDir
Open Panel
```

只先驗證：

```text
App UID
    ↓
libxui.so
    ↓
libxray.so
    ↓
http://127.0.0.1:2053
```

如果這條鏈成功：

> **3x-ui rootless Android App 的核心可行性即可視為正式證實。**

後續工作就只是產品化與 Android-specific feature trimming，而不是基礎技術可行性的問題。

---

## 17. 研究依據

本文件依據本專案實際完成的：

- 3x-ui Android arm64 cross compilation
- Android 17 Pixel 真機 Runtime PoC
- SQLite persistence 測試
- Web Panel HTTP / login 測試
- Xray child process 測試
- VLESS inbound 測試
- Xray restart / stop 測試
- SELinux Enforcing 測試
- SIGKILL orphan reproduction

整理而成。

最後一次真機 PoC 裝置：

```text
Pixel 9 Pro XL
Android 17
API 37
arm64-v8a
SELinux Enforcing
No root
```
