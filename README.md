# DS NEXT（Android）

dev-sidecar 的手机端：把 Go 版 MITM 内核（[ds-core-go](https://github.com/Blue-Frontier/ds-core-go)，未来的 ds-core next）直接跑在应用进程里，内置浏览器发出的请求全部经它转发，规则层沿用 dev-sidecar 的远端配置（DNS 映射、SNI 规则、预设 IP）。

包名 `org.blueFrontier.devSideCar.next.android`，应用名 **DS NEXT**。

## 架构

请求链路只有一条：

```
WebView → shouldInterceptRequest
        → HttpURLConnection（Proxy 指向 127.0.0.1:31191）
        → 进程内 Go 内核（MITM、规则、测速、改写）
        → 上游
```

- **没有 VPN**，不需要系统代理，也不需要安装证书。内核用自签 CA 给站点签临时证书，信任判断在应用内完成（`KernelTrust`：只放行 issuer 为 `dev-sidecar-core CA` 的链，其余仍走系统校验）。
- **内核在进程内**：`libgojni.so` + gomobile 绑定，`KernelService` 用前台服务 + WakeLock 保活，应用退到后台时内核继续监听。
- **拦截开关只管规则匹配**：`server.intercept.enabled=false`（安全模式）时不再匹配规则，预设 IP、DNS 回退、中继仍然工作。

## 内核以 submodule 引入

内核源码是独立仓库，通过 git submodule 挂在本仓库的 `kernel/` 下：

```powershell
git clone --recurse-submodules https://github.com/Blue-Frontier/dev-sidecar-android.git
# 已 clone 的话：
git submodule update --init --recursive
```

构建脚本按这个顺序解析内核目录，第一个命中即用：

1. `kernel/`（submodule）
2. `$env:DS_KERNEL_DIR`（本地另开的检出）
3. `D:\dev-sidecar-core-go`（历史路径，兼容旧环境）

升级内核：在 `kernel/` 里切到目标提交，然后回主仓提交 submodule 指针。

## 构建

无 Gradle、无 Android Studio，一条链路跑完：

```powershell
. C:\Users\Nobody\android-toolchain\env.ps1   # JDK / Kotlin / Android SDK
# 1) 编译内核 AAR（gomobile，arm64）
& .\kernel\build-kernel-aar.ps1
# 2) 把 AAR 里的 classes.jar 与 libgojni.so 同步到 libs/
& .\sync-kernel.ps1
# 3) aapt2 → kotlinc → d8 → jar uf → zipalign → apksigner
& .\build.ps1
```

产物 `build\dscore.apk`，每次构建另存一份到 `dist\ds-next-<时间戳>.apk`，哈希追加在 `dist\SHA256SUMS.txt`，只留最近 20 份。

`build.ps1` 会拒绝打包过期内核：`libs\.sync-stamp` 早于最新 `.go` 源码就报错，避免"改了内核却没重新 sync"。

## 目录

```
kernel/                       内核 submodule（ds-core-go）
assets/                       首次启动落到 files\ 的官方配置、CA 证书与私钥
res/mipmap-*/                 应用图标
src/org/blueFrontier/devSideCar/next/android/
    MainActivity              工具栏 + WebView + 隐藏的 --es mode 入口
    ProxyFetchWebViewClient   把每个 http(s) 请求经内核转发
    KernelTrust               内核 CA 的应用内信任
    KernelRuntime             内核句柄、配置层级、规则与统计的封装
    KernelService             前台服务 + WakeLock
    KernelSettingsActivity    运行状态、模式、三层配置、端口、日志
    RulesActivity             按域名列出规则，逐条开关或编辑
    TrafficActivity           按域名统计流量
    GithubStatusActivity      经内核逐个探测 GitHub 端点
    HelpActivity              帮助
    BrowserPrefs              主页设置
AndroidManifest.xml
build.ps1 / sync-kernel.ps1
dist/                         归档的签名 APK
```

## 配置层级

官方（`files\remote_config.json5`）→ 个人（`files\remote_config_personal.json5`）→ 本地（`files\user_config.json5`），后者覆盖前者。合并语义与桌面端一致：对象深合并、数组整体替换、`null` 或 `"[delete]"` 删除。三层任一文件变化后约 2 秒热生效，不重启、不断连。

## 模式

- **默认**：按规则拦截与改写。
- **安全**：关闭规则匹配，用来判断问题是否由规则引起。
- **增强**（`ow`，启用中继插件）：界面不提供入口，属于保留能力。切换要用 `-S` 强制重启，否则 `am start` 会退化成"把任务拉到前台"，活动不会重建、extra 不生效：

  ```powershell
  adb shell am start -S -n org.blueFrontier.devSideCar.next.android/.MainActivity --es mode ow
  ```

  设置页会如实显示"当前模式：增强"。日志里有一行 `ds-mode: extra=ow ok=true current=ow` 可核对。

## 已知限制

- `shouldInterceptRequest` 不暴露请求体，因此非 GET/HEAD 请求返回 501；页面里的 POST/XHR 会失败。
- minSdk 29（内核依赖的 TLS 行为与 29 对齐），arm64 only。
- 桌面端用于代理其它应用的场景在手机端未实现：本应用只转发自己的 WebView。

