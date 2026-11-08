# DS NEXT（Android）

dev-sidecar 的手机端：把 Go 版 MITM 内核（[ds-core-go](https://github.com/Blue-Frontier/ds-core-go)，未来的 ds-core next）直接跑在应用进程里，内置浏览器发出的请求全部经它转发，规则层沿用 dev-sidecar 的远端配置（DNS 映射、SNI 规则、预设 IP）。

包名 `org.blueFrontier.devSideCar.next.android`，应用名 **DS NEXT**。

## 架构

请求链路只有一条：

```
WebView → 应用内 HTTP 代理（ProxyController 指向 127.0.0.1:31191）
        → 进程内 Go 内核（MITM、规则、测速、改写）
        → 上游
```

- **不用 VPN，也不是系统级代理**：`androidx.webkit` 的 `ProxyController` 只把本应用的 WebView 指向内核，其它应用不受影响，也没有授权弹窗。
- **不用装证书**：内核的 CA 通过 `network_security_config` 作为应用自身的信任锚，WebView 直接认可；`onReceivedSslError` 里还按 issuer 放行一层兜底。
- **POST/登录可用**：请求由 WebView 原生发出、整体经过内核，请求体不会被丢掉（早期那套逐请求拦截就是因为拿不到 body 才返回 501 的）。
- **内核在进程内**：`libgojni.so` + gomobile 绑定，`KernelService` 用前台服务 + WakeLock 保活，应用退到后台时内核继续监听。
- **拦截开关只管规则匹配**：`server.intercept.enabled=false`（安全模式）时不再匹配规则，预设 IP、DNS 回退、中继仍然工作。

## 内核以 submodule 引入

内核源码是独立仓库，通过 git submodule 挂在本仓库的 `kernel/` 下：

```powershell
git clone --recurse-submodules https://github.com/Blue-Frontier/dev-sidecar-android.git
# 已 clone 的话：
git submodule update --init --recursive
```

依赖只有一项：`androidx.webkit`（提供 `ProxyController`，让 WebView 把内核当 HTTP 代理用）。没有 Gradle，所以 `deps/fetch-deps.ps1` 把 AAR 下载下来、取出 `classes.jar`，`build.ps1` 再把它塞进 kotlinc 的 classpath 和 d8（输出第 4c 步）。`deps/jars/` 不入库，CI 在构建前会跑同一个脚本。

构建脚本按这个顺序解析内核目录，第一个命中即用：

1. `kernel/`（submodule）
2. `$env:DS_KERNEL_DIR`（本地另开的检出）
3. `D:\dev-sidecar-core-go`（历史路径，兼容旧环境）

改内核就在 `kernel/` 里改：它是独立仓库，提交后回到主仓 `git add kernel` 记录新指针。不要在 `D:\dev-sidecar-core-go` 那份检出里改完就直接构建——主仓构建用的是 `kernel/`，两边会悄悄分叉（这次就因此让一个 `javac` 的修复在 CI 路径上失效了一轮）。

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

本机跑需要 `C:\Users\Nobody\android-toolchain\env.ps1`；没有它时脚本改从环境变量取工具链（`JAVA_HOME`、`ANDROID_JAR`、`BUILD_TOOLS`、`KOTLINC`、`KOTLIN_STDLIB`、`ANDROID_NDK_HOME`），CI 就是这么跑的。

## CI 与发布

| 工作流 | 触发 | 做什么 |
| --- | --- | --- |
| [build.yml](.github/workflows/build.yml) | push 到 main、PR、手动 | 装 JDK 21 / Android SDK / NDK / Go / gomobile / Kotlin，构建内核 AAR 与签名的 APK，上传 artifact |
| [release.yml](.github/workflows/release.yml) | 推送 `v*` 标签 | 复用 build.yml 构建，然后创建 **release 草稿**（fork 仓库会加 alpha 后缀并标 prerelease） |

发版流程：

```powershell
git tag v0.2.0
git push origin v0.2.0
```

草稿会在 Actions 跑完后出现在 Releases 里，确认 APK 可用后再手动发布。版本号从标签写入 `versionName`，`versionCode` 取 Actions 的运行序号，所以同一标签重复构建也不会撞版本。

工具链版本固定在 `build.yml` 顶部（Kotlin / build-tools / NDK / Go）。本机与 CI 用同一套脚本，差别只在环境变量由谁提供。

## 目录

```
kernel/                       内核 submodule（ds-core-go）
assets/                       首次启动落到 files\ 的官方配置、CA 证书与私钥
res/mipmap-*/                 应用图标
res/xml/                      网络安全配置（把内核 CA 设为信任锚）
res/raw/                      内核 CA（PEM）
deps/                         AAR 依赖下载与解包（fetch-deps.ps1）
src/org/blueFrontier/devSideCar/next/android/
    MainActivity              工具栏 + WebView（代理指向内核）+ 隐藏的 --es mode 入口
    KernelRuntime             内核句柄、配置层级、规则与统计的封装
    KernelService             前台服务 + WakeLock
    KernelSettingsActivity    运行状态、模式、三层配置、端口、日志
    RulesActivity             按域名列出规则，逐条开关或编辑
    TrafficActivity           按域名统计流量
    GithubStatusActivity      经内核逐个探测 GitHub 端点
    HelpActivity              帮助
    LogProvider               把 kernel.log 交给系统分享
    BrowserPrefs              主页设置
    KernelTrust               内核 CA 的信任链（供探测类请求使用）
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

## 图标

用的是桌面版 `packages/gui/dist/icon-1.png`（透明底 + 蓝色分子），不是安装包里那套 `build/icons/*.png`（蓝底白分子）。

这是**有意区分**：手机版和桌面版在视觉上分开，透明底交给启动器自己铺背景，各家 ROM 下也都能看。不要为了"和桌面版一致"换掉它。

## 日志

内核把日志写到 `files/kernel.log`（应用私有目录）。设置页的「内核日志」卡片显示最近 12 行，下面两个按钮把它交给别人：

- **复制全文**：把文件尾部 256 KB 放进剪贴板，直接贴进 issue
- **分享文件**：通过 `content://…logs/kernel.log` 调起系统分享，微信、邮件、网盘都能收；`file://` 早就被分享目标拒收了，应用没有 AndroidX 也就没有 FileProvider，所以自己实现了一个只暴露这一个文件的 `LogProvider`

## 已知限制

- minSdk 29（内核依赖的 TLS 行为与 29 对齐）。发布时按架构拆成两个包：`DS-NEXT-<版本>-arm64.apk`（约 7.6 MB）和 `DS-NEXT-<版本>-x86_64.apk`（约 8.3 MB），装错了系统会直接拒绝。本地要单个架构就用 `-Target android/arm64` 或 `android/amd64` 构建内核。
- 桌面端"给其它应用当代理"的场景在手机端未实现：代理只作用于本应用的 WebView。

## 第三方依赖

- `androidx.webkit` — `ProxyController`，把内置浏览器的流量交给内核。
- [FreeDroidWarn](https://github.com/woheller69/FreeDroidWarn)（Apache-2.0）— 启动时提示：Google 从 2026/2027 起要求所有应用在认证设备上通过开发者验证，本项目不参与，届时将无法在认证设备上运行。库的 `res/` 由 `build.ps1` 合并进资源表，`R` 类由 `aapt2 --extra-packages` 生成后用 `javac` 编译——因为没有 Gradle 帮忙做这一步。
