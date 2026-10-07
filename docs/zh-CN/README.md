# XrayDroid 使用与开发

[项目主页](../../README.md) · [繁體中文](../zh-TW/README.md) · [English](../en/README.md) · [交接文档](../PROGRESS.md)

## 功能与范围

- 无需 root，以普通 Android App 运行 3x-ui 与 Xray-core；支持 Android API 26 以上的 arm64 设备。
- 界面提供服务状态、启动、停止、重启、生命周期日志与浏览器入口。
- 入站、客户端、路由与流量统计使用 3x-ui 网页面板。
- 这是**本机代理服务器**，**不是**代理工具，不接管其他 App 的流量。
- 目前不支持 MTProto / TUIC 边车进程与面板／Xray 自行更新。
- Android 无法读取的系统统计以警告或零值处理；Linux 的 systemd、Fail2ban、syslog、安装器不属于 App 功能，部分上游菜单仍可能显示。

## 构建

准备 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK r30、Go 1.27.1、Node.js 24 以上、npm、Python 3.11 以上、Git、curl。

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="/path/to/android-sdk"
export ANDROID_NDK_HOME="/path/to/android-ndk"
./scripts/build-core.sh
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- 也可用 `local.properties` 的 `sdk.dir` 指定 SDK；macOS 的 NDK 默认位于 `/opt/homebrew/share/android-ndk`。
- `build-core.sh` 下载官方源码（首次构建需要网络）、校验 3x-ui commit 与 Xray SHA256、应用固定补丁，构建上游前端与三个 Go 可执行文件。
- Gradle 不会自动下载内核，缺少文件时拒绝打包 APK。

## 使用

1. 打开 App 并点“启动服务”，选择是否授予通知权限。
2. 等服务就绪后，再点“打开管理面板”。
3. 首次登录使用 3x-ui 默认账号密码 `admin` / `admin`，建议登录后到面板设置中修改。
4. 创建入站需要使用高于 1024 的端口。对其他设备开放入站必须使用可达的监听地址，并确认所在网络可以连入。
5. 可从 App 或常驻通知停止；关闭主页不会停止服务。

管理面板固定使用 `http://127.0.0.1:2053/`，Android 补丁强制 HTTP、根路径与 loopback（本地回环）监听。面板中的端口、路径、TLS 或监听地址设置不会覆盖 Android 的固定入口；订阅服务器与入站仍按各自配置运行。App 只显示自身的生命周期日志。
内核更新需要重新构建并安装 APK。

## 关于出站网络

从主页右上角“设置”→“出站网络”进入。子页面逐一列出 App 可见的接口、IP、DNS 与连接状态，可手动重新检测；名称按设备实际情况显示。

选择会保存并在下次启动时继续使用。指定接口按名称对应当时的 Android 网络；接口消失后仍保留选择并等待恢复，不会改选其他同类型网络。

VPN 独立显示，不并入系统默认。没有可用 Android 网络、已关闭或本地回环的接口会标明原因并停用选择；Android 可能不公开他人或其他 App 专用的接口，不保证能列出所有虚拟接口。选择后会绑定接口所属的 Android 网络，实际数据包走哪个底层接口由 Android 决定。

- 跟随系统：沿用 Android 默认路由，含系统 VPN。
- 指定网络：将 Xray 出站套接字与系统 DNS 绑定到接口所属的 Android 网络，可能绕过系统 VPN；选择移动网络时会请求 Android 保持该网络可用。
- 切换：服务运行中切换会重启 3x-ui 与 Xray，现有连接将会中断；指定网络消失时进入等待，恢复后重新启动。

## frp 客户端

“设置”→“frp”先显示服务器实例列表。点“新增服务器”并输入实例名称，即可进入单独配置；列表也可重命名实例。多个实例可同时连接不同 frps，每个实例的 TOML、认证资料、代理／访客规则、支持文件、网络接口选择及启停状态均独立。停止、重启或网络切换只影响该实例；通知提供最多三个单独停止操作，其余可从列表进入控制。

更新后原有配置、支持文件、外部令牌命令选项与网络偏好保留为“默认实例”，不移动原有文件。退出配置先返回服务器列表，有未保存草稿时仍会确认。Android 重建前台服务时，只恢复之前要求运行的实例。多个实例使用本地监听端口时仍需自行避免冲突。

“设置”→“frp”提供完整的 frp 客户端，连接外部 frp 服务端；手机不提供 frp 服务端。此子页面与出站网络分开，frpc 使用独立的前台服务与通知，启停不影响 3x-ui／Xray。frpc 可在此页面选择网络接口，设置与 Xray 分别保存；默认跟随 Android 系统路由，指定接口时将 frpc 与 DNS 绑定到该 Android 网络。切换会重启 frpc；指定网络不可用时等待恢复，不回退其他网络。

1. 可选“表单配置”或“TOML 配置”。表单按基本设置、认证、传输、TLS 与其他设置分组，展开需要的区块即可编辑；支持 Token／OIDC、全部 TCP／KCP／QUIC／WebSocket／WSS 传输协议与元数据。
2. “转发规则”可添加、编辑与删除 TCP、UDP、HTTP、HTTPS、STCP、SUDP、XTCP、TCPMUX 代理；“访客规则”支持 STCP、SUDP、XTCP。各协议显示对应的域名、路径、密钥、监听、穿透及备用等字段。规则协议在创建时选择，要改协议请再添加一条规则。
3. 两种模式共用同一份完整 TOML。切换到表单会解析 TOML；语法或字段结构无法转换时保留原文并留在 TOML 模式。只切换模式不会改写原文；实际表单修改会规范化排版并移除注释，但保留未编辑字段、插件、访客与 `includes`。插件及其他未提供表单的高级选项可在 TOML 中编辑。无效数字保留在表单中，须修正后才能切换模式、验证、保存或启动。导入替换草稿仍需确认。
4. 点“启动 frpc”；启动与重启会先验证、保存草稿。运行中保存配置后需重启才能应用。主页服务与 frpc 分别控制，关闭设置页不会停止 frpc。
5. 页面最上方区块显示内核上报的“正在连接 frp 服务器”、“已连接到 frp 服务器”、“连接失败，正在重试”、“已断开连接，正在重新连接”或“无法获取连接状态”。失败时显示可供用户判断的原因与尝试次数：DNS、拒绝连接、超时、TLS、认证准备、登录被拒或登录前连接被关闭。
6. “已连接”代表成功登录 frps，即使没有代理或只配置访客也能确认连接；代理已启用仍不保证本地目标服务可用。无法获取状态时清除先前结果，不沿用“已连接”。可从子页面或 frp 通知停止。

“允许外部令牌命令”默认关闭，与上游安全默认一致。若通过外部命令获取令牌（`tokenSource`），需先停止 frpc，再开启此选项；验证与启动会启用 `TokenSourceExec`。外部命令使用此 App 的权限；从文件选择器导入支持文件不代表该文件可执行。此选项独立保存，不改写 TOML。

## 架构

```text
MainActivity → XuiService → nativeLibraryDir/libxui.so → libxray.so
                  ↓
filesDir/server/{db,xray,log}
                  ↓
Browser → http://127.0.0.1:2053/

MainActivity → FrpService → per-instance libfrpc.so processes → external frps
                  ↓
filesDir/server/frp/{frpc.toml,support/} (default)
filesDir/server/frp/instances/{UUID}/{frpc.toml,support/}
```

## 验证

```bash
./gradlew :app:ktlintCheck :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
./gradlew -PvalidationApplicationId=io.github.xraydroid.validation :app:connectedDebugAndroidTest
```

设备测试验证原生程序执行、嵌入前端、Xray 子进程、面板登录、VLESS TCP 入站的新增／监听／删除、后台面板、强制停止后的清理、重启、停止与数据库文件保留。
frpc 转发测试需要本机测试服务器。在另一个终端执行以下命令，再运行上面的隔离设备测试；未启动此服务器时，TCP／UDP 转发项目会跳过。测试服务器只监听本机，使用构建产生的 `.core-cache/frps`；测试后以 Ctrl+C 停止，并移除这两个测试转发。

```bash
adb reverse tcp:17000 tcp:17000
adb reverse tcp:18080 tcp:18080
python3 scripts/frp-validation-server.py
# 测试完成并停止服务器后
adb reverse --remove tcp:17000
adb reverse --remove tcp:18080
```

frpc 设备测试涵盖 TCP／UDP 实际往返、断线重连、独立启停、无效配置保留、支持文件导入，以及外部令牌命令与父进程退出时的进程组清理；不等同于所有 frp 协议／插件已逐项验证。
设备测试请使用隔离安装包与开发设备；现有正式包须先停止服务以释放固定面板端口，测试不会移除正式包的数据。已执行项目与限制见[进度记录](../PROGRESS.md)。

多实例测试使用 `scripts/frp-validation-server.py --multi-instance` 启动两个本机 frps，并以 `adb reverse tcp:17000 tcp:17000`、`adb reverse tcp:17001 tcp:17001` 转发。可用 `-Pandroid.testInstrumentationRunnerArguments.class=io.github.xraydroid.runtime.FrpMultiInstanceTest` 限定设备测试；测试后停止 fixture 并移除这两个转发。此案例验证两个服务器同时登录，以及重启、网络等待、停止与快速启停互不影响。

## 授权与来源

[GPL-3.0](../../LICENSE) · [第三方声明](../../THIRD_PARTY_NOTICES.md)
