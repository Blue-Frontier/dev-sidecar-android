# dev-sidecar-android

[dev-sidecar](https://github.com/docmirror/dev-sidecar) 的手机端（Android）移植：把本地 MITM 代理引擎跑在手机上，流量走系统级 VPN tunnel，规则层沿用 dev-sidecar 的远端配置（DNS 映射、SNI 规则、预设 IP）。

## 设计约束

- **零第三方依赖**：app 只用 `android.jar` 里的东西（原生 View、`VpnService`、`WebView`、`Service`），无 AndroidX/AppCompat；Kotlin 标准库随 APK 自带（构建脚本把 `kotlin-stdlib.jar` 一起 dex 进去）。
- **无 Gradle、无 Android Studio**：打包链是手写的 `aapt2 → kotlinc → d8 → jar uf → zipalign → apksigner`，一条 PowerShell 脚本跑完（[build.ps1](https://github.com/Blue-Frontier/dev-sidecar-android/blob/main/build.ps1)）。CI 里跑同一条脚本即可出 APK。
- **内核单独成仓库**：Go 内核在 [ds-core-go](https://github.com/Blue-Frontier/ds-core-go)，之后以 gomobile AAR 形式集成进来；当前版本代理地址可配置，指向任意已运行的内核实例（如 PC 上的 `ds-core` CLI）。

## 架构（A 方案：VPN 只圈自己 + WebView）

- `ProxyVpnService`：`VpnService` 只圈住本 app（`addAllowedApplication`），用 API 29 的 `setHttpProxy` 把本 app 的 HTTP 流量指到可配置的内核地址；proxy-only 模式不开 tun 路由，不需要用户态 TCP 栈。
- `MainActivity`：纯代码布局（APK 内无 res/），WebView + 代理地址 / URL 输入。
- `KernelCaWebViewClient`：`onReceivedSslError` 里只放行内核 CA 签发的证书（issuer 含 `dev-sidecar-core CA`），其余一律拒绝——**本机不需要安装任何证书**。

## 目录

```
AndroidManifest.xml        minSdk 29 / target 35，specialUse 前台服务
src/org/BlueFrontier/DevSidecar/
    MainActivity.kt            界面 + VPN 授权流程
    ProxyVpnService.kt         VpnService（proxy-only / route 两种模式）
    KernelCaWebViewClient.kt   只信任内核 CA 的 WebViewClient
build.ps1                    无 Gradle 全链路构建（含 stdlib 打包）
smoke/                       构建链冒烟验证模板（与 app 同源）
```

## 已知风险（需真机确认）

- **proxy-only 模式**（不加路由、仅 `setHttpProxy`）能否让系统把代理应用到本 app 的 WebView 流量，属于 Android 行为假设，需要实机验证；不通就回退到加路由 + netstack（方案 B）。
- WebView 子资源的 TLS 错误在部分 Chromium 版本不上报 `onReceivedSslError`，页面可能缺资源。
- `route` 模式目前只建立隧道、不读包，会黑洞本 app 流量，仅供下一步接内核时用。

## 构建

```powershell
. C:\Users\Nobody\android-toolchain\env.ps1   # 本机工具链（JDK/Kotlin/Android SDK，无 Gradle）
& D:\dev-sidecar-android\build.ps1
# 产物: build\dscore.apk
```

下一里程碑：gomobile 打 AAR，把 [ds-core-go](https://github.com/Blue-Frontier/ds-core-go) 的内核塞进 app 进程，代理地址变成 `127.0.0.1` 本地直连。
