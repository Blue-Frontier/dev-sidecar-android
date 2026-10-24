# dev-sidecar 安卓版 · 会话迁移总结

> 写于 2026-10-25 凌晨（本地时区 Asia/Shanghai）。本会话从"测试 ds 安卓版"开始，
> 一路修了 app 端 bug，最终把 Go 内核通过 gomobile 打进伴生 app、在手机上本地跑通。
> 新会话接手前请读完本文。

---

## 0. 一句话现状

- **手机本地内核已跑通**：伴生 app `com.bluefrontier.dscore.kernel` 里用 gomobile 编出的
  `libgojni.so` 在手机上监听 `127.0.0.1:31181`，代理 baidu 返回 200（实测）。
- **主 app `com.bluefrontier.dscore` 已指向本地内核**，等用户点"开启 VPN"+"打开"做最后的
  HTTPS(MITM) 端到端确认。
- 架构成立：主 app 的 `VpnService` 只圈自己（`addAllowedApplication(packageName)`），
  伴生内核 app 是**独立包名**，它的上游流量不被 VPN 圈住 → **无自代理回环**。

---

## 1. 目标

把 dev-sidecar 的 MITM 代理内核做到手机上（"ds 安卓版"），自包含可用，并能加速/代理
GitHub 访问。当前处于 "0.1 跑通 + 内核嵌入" 阶段。

## 2. 三个组件

| 组件 | 路径 | 说明 |
|---|---|---|
| 主 app（WebView + VpnService） | `D:\dev-sidecar-android\` | 包名 `com.bluefrontier.dscore`，产物 `build\dscore.apk` |
| 伴生内核 app（跑 Go 内核） | `D:\dev-sidecar-android\kernel-app\` | 包名 `com.bluefrontier.dscore.kernel`，产物 `build\ds-kernel.apk` |
| Go 内核 | `D:\dev-sidecar-core-go\` | 桌面 CLI（`cmd\ds-core`）+ 可嵌入库（`mobile\`）|

手机：Xiaomi `25053RP5CC`（HyperOS / Android 16，**未 root**，`ro.build.type=user`）。
adb serial（mDNS）：`adb-0789B48009A40540-aL18tj._adb-tls-connect._tcp`。
PC 局域网 IP：`192.168.31.73`。PC 上 dev-sidecar 跑在 `31181`（程序级防火墙规则放行）。

---

## 3. 本会话修掉的 bug（主 app / 内核）

1. **标题栏盖住顶部控件**（edge-to-edge）
   - 因：manifest 无主题 → 默认 ActionBar；`targetSdk 35` 触发强制 edge-to-edge，内容没给状态栏留 padding。
   - 修：`AndroidManifest.xml` 加 `android:theme="@android:style/Theme.Material.Light.NoActionBar"`；
     `MainActivity` 用 `setOnApplyWindowInsetsListener` 补顶/底 inset。

2. **WebView 拒内核 CA（MITM 握手 `unknown certificate`）**
   - 因：`KernelCaWebViewClient` 用 `error.certificate.issuedBy.toString()` 取 issuer，
     但 `DName.toString()` 打的是内存地址（`DName@xxxx`），匹配恒 false，把自己内核的 CA 拒了。
   - 修：改用 `issuedBy.dName`（完整 DN 串，形如 `CN=dev-sidecar-core CA,O=dev-sidecar`）匹配
     `KERNEL_CA_ISSUER = "dev-sidecar-core CA"`。logcat `ds-web` 变 `trust kernel-issued certificate`。

3. **每次装包要卸载（签名不一致）**
   - 因：`build.ps1` 每次 `Remove-Item build/` 把 keystore 也删了、重新生成新密钥。
   - 修：keystore 挪到仓库根 `D:\dev-sidecar-android\debug.keystore`（build/ 外，稳定签名）。
     注意：改位置后**新旧 key 不同**，切换时需 `uninstall` 旧包再装（一次性）。

4. **伴生 app 拷贝 assets 子目录失败**
   - 因：aapt2 `-A` 打的 APK 里，**子目录 assets（`assets/ca/ca.crt`）AssetManager 打不开**
     （`open("ca/ca.crt")` → FileNotFoundException），只有顶层 asset 能 open。
   - 修：把 CA 拍平成顶层 asset `assets/ca_ca.crt`、`assets/ca_ca.key`，代码里
     `copyAssetFile("ca_ca.crt", "ca/ca.crt")` 写到 `files/ca/`。验证 `files/ca/ca.crt`
     哈希 = `.smoke` 的 `5a2efbad…`。

5. **gomobile `//export` 返回值限制**
   - `//export` 函数只能返回 0 或 1 个值（+可选 error）。`StartKernel` 原设计 `(int64,string)` 不行，
     改成返回单个 JSON 字符串 `{"handle":N,"error":""}`。
   - 且 `gomobile bind` 要求**普通库包**（非 `package main`）→ `mobile` 包改为 `package mobile`、去掉 `main()`。

---

## 4. "加速"的真相（重要认知）

dev-sidecar 让 GitHub 变快是**三层**机制：

1. **SNI 伪装**（`sni: baidu.com` 防墙按 SNI 断连）→ Go 内核**已实现** ✅
2. **ghp.ci 重定向**（release 文件走镜像）→ Go 内核**已实现** ✅
3. **客户端脚本注入**（`tampermonkeyScript` + `GithubEnhanced` user script，把资源/下载链接
   改写到境内快线）→ Go 内核**未实现** ❌（README"未实现"第一项；这是真正的"高速下载"来源）

所以"加载慢"根因是：境内直连 GitHub CDN 慢/抖 + 缺第 3 层脚本注入。
**打包内核只解决"自包含"，不解决"快"。** 已知内核还有个 bug：`sni: baidu.com` 套在
GitHub 真实 CDN IP 上时，多租户 CDN 会把别家子域的证书发回来 →
`x509: certificate is valid for *.actions.githubusercontent.com, not collector.github.com`。
（JS 内核里 sni 是配合"境内镜像 DNS/预设 IP"一起用的；Go 内核目前 DNS 解到真实 IP，
sni 单用会出问题，待对齐 JS 语义。）

`remote_config.json5`（`D:\dev-sidecar-core-go\.smoke\`）里的关键项：
`preSetIpList: github.com → [4.237.22.38, 20.26.x, 140.82.x…]`；`dns.mapping` 只覆盖
jetbrains/azureedge/stackoverflow；GitHub 子域走 `sni: baidu.com`。

---

## 5. TLS 信任的边界（未 root 手机）

- 手机**未 root** → 装不了系统 CA。用户装了**用户 CA**（`Download/ds-core-ca.crt`），
  但 **WebView 不认用户 CA**（`unknown certificate` 仍出现）→ 非 root 下无干净解法。
- 现方案：主 app 靠 `onReceivedSslError` 放行内核 CA（**主文档可靠；子资源不都上报**，
  是 Chromium 已知局限）→ 页面能显示，但**图标/部分资源偶发缺失**（黑块、坏图标）。
- 要彻底干净：root 装系统 CA（或接受瑕疵）。

---

## 6. 工具链 / 环境

- 本机：Win11 x86-64；Go 1.27.3；Node/pnpm；uv；代理 `127.0.0.1:31181`（dev-sidecar）。
- Android 无 Gradle 工具链：`C:\Users\Nobody\android-toolchain\`（jdk/kotlin/android-sdk/env.ps1）。
  - `aapt2 link` 的 assets 参数是 **`-A <dir>`**（不是 `--assets`）。
  - keystore 在 `D:\dev-sidecar-android\debug.keystore`（storepass/android，alias dscore-debug）。
- NDK：`android-toolchain\android-sdk\ndk\29.0.14206865`（sdkmanager 走 DS 代理装的）。
- gomobile：`C:\Users\Nobody\go\bin\gomobile.exe`。`go.mod` 已加 `tool golang.org/x/mobile/cmd/gobind`
  + indirect require（用户已同意加 Go 依赖；**桌面 CLI `go build ./cmd/ds-core` 不受影响**）。

### gomobile 构建（已验证可跑）
```powershell
. C:\Users\Nobody\android-toolchain\env.ps1
$env:ANDROID_NDK="C:\Users\Nobody\android-toolchain\android-sdk\ndk\29.0.14206865"
$env:ANDROID_HOME="C:\Users\Nobody\android-toolchain\android-sdk"
$env:GOPROXY="https://goproxy.cn,direct"; $env:HTTPS_PROXY="http://127.0.0.1:31181"
& "$env:USERPROFILE\go\bin\gomobile.exe" bind -target=android/arm64 -androidapi=35 -o build\kernel.aar ./mobile
# → build\kernel.aar: jni/arm64-v8a/libgojni.so + classes.jar(mobile.Mobile/go.*)
```
Java API（`mobile.Mobile`）：`touch()`（加载 .so）→ `startKernel(cfg,listen,caDir,log)` 返回 JSON → `stopKernel(handle)`。

### 伴生 app 构建（`kernel-app\build-kernel.ps1`，已验证）
aapt2(-A assets) → kotlinc(**classpath 带 `;` 要用反引号转义外层引号**，PowerShell 会把裸 `;` 拆开)
→ d8×3(app/gomobile/stdlib) → jar uf(dex + `lib/arm64-v8a/libgojni.so`) → zipalign → apksigner。

---

## 7. 验证记录（本会话）

- **方案 A（路由级 VPN + per-network 代理）实测死亡**：`bindProcessToNetwork(vpn)=true` 后
  WebView 仍 `net::ERR_TIMED_OUT`、内核 0 连接。dumpsys 证实 VPN 网络
  `HttpProxy: [127.0.0.1] 31181`、`Uids: {10288-10288}`，但 Chromium 代理服务只认
  **默认网络的 ProxyTracker 广播**（04:34:27 发出的是 WiFi 的空代理 `[]`），不认 VPN 网络的
  LinkProperties 代理。结论：WebView 无代理 API 且不认 VPN per-network 代理 → A 不可行。
- **方案 B（shouldInterceptRequest + app 内显式代理转发）实测成功**：
  `ProxyFetchWebViewClient` 拦截全部 http/https 资源，用
  `HttpURLConnection(Proxy(HTTP,127.0.0.1:31181))` 转发；TLS 由 app 直接连内核 MITM
  （`KernelTrust`：系统信任库优先，失败按 issuer 含 `dev-sidecar-core CA` 放行）；Set-Cookie
  以原始 multimap 喂 CookieManager；请求带 CookieManager 已有 Cookie 回内核；非 GET/HEAD
  返回 501 明示（shouldInterceptRequest 拿不到请求体，Android 平台限制）。
- **手机端到端截图确认 GitHub 完整渲染**（05:00 本地）：UNIVERSE'26 横幅 + 导航 +
  Copilot/代码区，子资源（github.githubassets.com 的 JS/CSS/字体）全部经内核加载，
  内核日志 160+ request，无 upstream 失败。无 VPN、无授权弹窗。
- dial timeout 由 10s→5s：13 个候选 IP 中 Azure 段全被丢包，串行重试每个 10s 会让
  WebView 30s 超时先到；5s + DNS.Note 沉降（失败 IP 沉队尾）后首屏 ~1 分钟内可开。
- MIUI 电源冻结复现：内核进程活着、监听队列 Recv-Q=3、accept 死循环不吐包，nc 全挂；
  重启内核恢复。用户已把 ds-kernel 设为省电"无限制"，是否根治待静置复测（后台任务在跑）。

## 8. 下一步（按优先级）

1. **确认静置不复发**（后台 nc 复测任务，内核空闲 11 分钟后请求应立即 200）；
   若仍睡死需实现内核 60s 心跳或前台锁唤醒。
2. **首次连接提速**：并行 race dial（前 N 个候选并发，先到先用），或配置里把 github 排序
   优化（`PreSet` 顺序 + Note 学习后二次连接已快）。
3. **修内核 sni 语义**：对齐 JS 内核（sni 需配境内镜像 DNS/预设 IP）。
4. **实现第 3 层加速**（脚本注入 / 响应体改写）——"真快"的关键。
5. POST body：`shouldInterceptRequest` 拿不到，登录取 501；深度方案是 netstack VPN 模式。

## 9. 快速恢复命令

```powershell
# 手机内核状态
$S='adb-0789B48009A40540-aL18tj._adb-tls-connect._tcp'
adb -s $S shell 'ss -ltn | grep 31181'
adb -s $S shell run-as com.bluefrontier.dscore.kernel tail -8 files/kernel.log
# 重启伴生内核
adb -s $S shell am force-stop com.bluefrontier.dscore.kernel
adb -s $S shell am start -n com.bluefrontier.dscore.kernel/.MainActivity
# 主 app 指向本地内核并测试
adb -s $S shell am force-stop com.bluefrontier.dscore
adb -s $S shell am start -n com.bluefrontier.dscore/.MainActivity --es host 127.0.0.1 --es port 31181
# 截图
adb -s $S shell screencap -p /sdcard/_s.png; adb -s $S pull /sdcard/_s.png D:\dev-sidecar-android\build\_s.png
```

## 10. 注意事项 / 坑

- **adb install 会被小米拒**（`INSTALL_FAILED_USER_RESTRICTED`）：需手机端"允许 USB 安装"
  （开发者选项"USB 安装"，可能要小米账号）。本会话里用户点过几次"允许"才装成功。
- 两个 app 各自包名独立：主 app 更新用仓库根 keystore；伴生 app 也签 `D:\dev-sidecar-android\debug.keystore`。
  换 key 后要 `uninstall` 旧包。
- 伴生内核进程被 `force-stop` 或手机省电杀掉后，要重新 `am start` 伴生 activity 才重启内核。
- `pm clear com.bluefrontier.dscore.kernel` 会删掉 `files/ca/`（下次启动会从 asset 重拷）。
- PowerShell 传带 `;` 的参数给 kotlinc 会拆参 → 用 `` "`"$path`" `` 外层转义引号。
- 内核日志时间戳是 UTC（`Z`），比本地慢 8 小时。
- 中间脚本/截图在 `D:\dev-sidecar-android\build\`（`_scan_sessions.py`、`_tail*.md`、
  `_phone_screen*.png` 等），属一次性产物，迁移后可清理（保留 `dscore.apk`、`ds-kernel.apk`）。
