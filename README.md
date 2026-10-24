# dev-sidecar-android

[dev-sidecar](https://github.com/docmirror/dev-sidecar) 的手机端（Android）移植：把本地 MITM 代理引擎跑在手机上，流量走系统级 VPN tunnel，规则层沿用 dev-sidecar 的远端配置（DNS 映射、SNI 规则、预设 IP）。

## 设计约束

- **零第三方依赖**：代理层用 `java.net.Socket` + `SSLEngine` 原生实现，规则层是纯逻辑移植，全部在 `android.jar` 标准库范围内。
- **无 Gradle、无 Android Studio**：打包链是手写的 `aapt2 → kotlinc → d8 → jar uf → zipalign → apksigner`，一条 PowerShell 脚本跑完。CI 里跑同一条脚本即可出 APK。
- **架构**：`VpnService`（前台服务）提供 tun + DNS 劫持，应用内进程做 TLS 终结与中继；规则决策层移植自 dev-sidecar 的 `remote-config-rule.js`（正则表预编译 + DNS/SNI/preSetIpList 三类注入）。

## 目录

```
smoke/        全链路验证模板：最小 Activity + manifest + build-smoke.ps1
```

`build-smoke.ps1` 的头部注释说明如何在无 Gradle 环境下驱动整条打包链（脚本内路径为开发者本机工具链位置）。`src/`（VpnService 与规则引擎）待实现。
