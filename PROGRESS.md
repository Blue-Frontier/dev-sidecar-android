# dev-sidecar 安卓版 · 进度总结

> 更新于 2026-10-28。上接 `SESSION-SUMMARY.md`（2026-10-25 凌晨，内核嵌入阶段）。
> 本文记录从"内核嵌入跑通"到"方案 B 端到端闭环 + 502 修复"的全部进展、结论与遗留问题。

---

## 0.1 二次更新（2026-10-28 中午）

**端口规定（硬性）**：本项目**不得再绑定 31181**。31181 归桌面 dev-sidecar。
手机内核、主 app 默认值、桌面 CLI 默认 `-listen` 全部改为 **31191**；PC 侧 forward 用
43181 等空闲端口（仍不要用 31181）。

**白屏根因已定位**：内核 `serveOne` 在响应头到达后把读超时清成了无限
（`SetReadDeadline(time.Time{})`），而 race dial 偶尔会选中"能握手、能返头、但 body 中途
黑洞"的地址 → `resp.Write` 拷贝 body 永久阻塞 → WebView 主文档流不结束 → 白屏。
实测：同一 IP 从 PC 直连 0.3–1.0s 拿满 584KB；经手机内核对同一 IP 偶发只到 6488 字节后永久停住。

**已修（`proxy/proxy.go`）**：
1. 每次 attempt 开头 `upstream.SetDeadline(time.Time{})`，清掉拨号/TLS 阶段残留的 deadline。
2. **body 预取**：在向客户端写任何字节之前，先把 body 读到 EOF（上限 1 MiB）。完整读到时
   用 `Content-Length` 重新定帧（不再 chunk 重排）；超过上限才边读边流。
3. **空闲超时**：`idleReader` 每次 Read 前重设 15s 读 deadline，body 停摆即报错。
4. 预取失败 → `DNS.Note` 降权 + **换 IP 重试一次**（此时尚未写客户端，可安全重试），二次失败才 502。

这样"能返头但 body 黑洞"的地址会在 ~15s 内被发现并换掉，而不是无限挂起。

### 0.2 验证结果（2026-10-28 12:5x，**白屏已解决**）

- 内核侧（PC 经 `forward tcp:43181 tcp:31191` 压测）：github 主文档 **10/10 成功**，
  120.7 KB / 0.40–0.60 s；抽 8 个 `github.githubassets.com` 真实资源 **8/8 成功**，0.5–3.3 s。
- 主 app 端到端：`am start … --es url https://github.com/` 后页面**完整渲染**
  （GitHub 深色首页、主视觉、注册表单、Copilot 按钮）。
  CDP 实测 DOM：`readyState=complete`、`styleSheets=33`、`bodyTextLen=5685`、`scrollH=16239`。
- 本次加载统计：`response_written=583`、`body_stalled=48`（全部重试救回）、
  `no_usable_address=82`（少数图片缺失，页面上一两处破图）。
- **白屏的真实成因有两个，缺一不可**：
  1. 内核 body 拷贝无超时 → 偶发永久挂起（已修，见 0.1）；
  2. **app 的 `connectTimeout=8s` 短于内核 race dial 的最坏耗时（~20s）**，内核还在换 IP
     时 app 就把请求掐了，页面上表现为成片空白 → 已改为 `connectTimeout=20s`、
     `readTimeout=60s`，并加一次 IO 失败重试。
- 结论：白屏不是 WebView 的能力边界，也不是选错引擎。**方案 B 成立。**

### 0.3 仍未解决 / 下一步

1. **首次加载慢（数十秒到数分钟）**：`github.githubassets.com` 的 4 个 Fastly IP 经常
   TCP 层被墙，race dial 每批要付 5s 超时。已有 `DNS.Note` 学习，重载会快；但重启内核后丢失。
   - 可选：把 `githubassets` 的候选做持久化/预热；或降 `DialTimeout` 到 3s。
2. **`sni: baidu.com` 对 body 卡死无影响**（卡在 TCP dial，早于 TLS），但会造成
   `collector.github.com` 那类 `x509: certificate is valid for … not …`；待对齐 JS 语义。
3. **第 3 层加速**（脚本注入/资源改写）才是"真快"的解——这是 `githubassets` 频繁被墙的根治手段。
4. 小瑕疵：个别图片 502（页面破图）；app 没有"重载"按钮，目前靠 `--es url` 或输入法 GO。

### 0.4 ds-core 功能补齐（2026-10-28 下午，纯离线，未上实机）

按"响应处理层 → 透传层 → 观测层"补齐，`go build ./...`、`go vet ./...`、`go test ./...` 全绿。

| 新能力 | 实现位置 | 用例 |
|---|---|---|
| `script`（字符串/数组）+ `tampermonkeyScript` 注入 | `proxy/inject.go`、`scriptstore/` | `TestHTMLScriptInjection` |
| `/____ds_script____/<key>` 同源自托管 | `proxy/inject.go` | `TestInjectedScriptServedLocally` |
| CSP `script-src` 补 `'self'` + `nonce` 透传 | `proxy/inject.go` | 同上 |
| gzip/deflate 解压 → 切拼 → 重压 | `proxy/inject.go` | 同上 |
| github.com 特判（跳过 turbo-frame、追加 fakefile.js） | `proxy/inject.go` | 同上 |
| `whiteList` / `excludeIpList` 字节隧道 | `proxy/tunnel.go` | `TestWhiteListHostIsTunneledNotMITM` |
| WebSocket / Upgrade 透传 | `proxy/tunnel.go` | `TestWebSocketUpgrade` |
| 流量统计 + `KernelStats(handle)` | `proxy/stats.go`、`mobile/mobile.go` | `TestStatsCountRequests` |
| `verifyHost`（SNI 换域名后按指定名字校验） | `proxy/proxy.go` | `TestVerifyHostOverridesChainCheck` |
| `compatible.request[x].rejectUnauthorized=false` | `proxy/proxy.go` | 配置解析冒烟 |
| 脚本启动预取 | `proxy/inject.go` | — |

仍未对齐：HTTP/2（`NextProtos` 仅 `http/1.1`，要支持等于第二套按流处理的代理实现）、
ECH / NAT64 / SVCB、配置热更新、`speedTest`（等价能力由拨号失败降位提供）、
`sni: ""` 显式禁用 SNI。

### 0.5 实机验证与两个真 bug（2026-10-29 上午）

新内核和新 app 装机后逐项验证，**并抓出两个只在真机上才暴露的缺陷**：

| 缺陷 | 现象 | 根因 | 修法 |
|---|---|---|---|
| CSP 指令粘连 | 注入脚本被 `default-src 'none'` 拦掉 | `cspWithSelf` 取错了正则分组（`loc[4:5]` 是空白组），拼成 `script-src'self'` | 改为 `loc[6:7]` 判 `'self'`、在指令名后插入；单测从 `Contains("'self'")` 收紧为精确子串 |
| 注入后页面乱码 | 仓库页整屏 gzip 字节被当文本渲染 | Chromium 对 `shouldInterceptRequest` 返回体不按 `Content-Encoding` 解码；内核重新 gzip 后 app 直传原始字节 | app 侧统一收窄 `Accept-Encoding: gzip, deflate`、自行 inflate 并剥掉 `content-encoding`/`content-length` |

实机验证结果（`torvalds/linux` 仓库页）：

| 项 | 证据 |
|---|---|
| 响应注入 | 内核 `script injected host=github.com tags=3`；HTML 里 2 个 `/____ds_script____/` 标签 + `fakefile.js`；`injected=5` |
| CSP 改写 | 头部实为 `script-src 'self' github.githubassets.com 'sha256-…'`，粘连形式消失 |
| 同源脚本服务 | `/____ds_script____/<key>` 返回 26291 字节 tampermonkey 运行时，`DS-Middleware: ds_script` |
| 白名单隧道 | `bypass tunnel host=www.icloud.com`，200 / 31534 B / 0.33 s |
| WebSocket 升级 | PC 明文 echo 服务：`101 Switching Protocols` + `ping-frame` 回显，`upgrades=1` |
| 统计 | `/__ds_stats__` 返回 JSON；整页 201 请求 / 199 响应 / **0 stalls / 0 errors** / 2.9 MB |
| 资源完整度 | `github.githubassets.com` 167 个资源全部成功，页面完整渲染 |

### 0.6 内核设置界面与 WebView 持久化（2026-10-29 上午）

**ds-kernel 设置界面**（`src/.../KernelSettingsActivity.kt`，纯代码布局，无 XML；入口是 URL 行右侧 ⚙）：

| 卡片 | 内容 |
|---|---|
| 运行状态 | 运行中/未启动、监听地址、handle、启动/停止、重启 |
| 配置概览 | 监听地址、拦截域名数、白名单数、排除 IP 数（新增 Go 导出 `KernelSummary`） |
| 监听端口 | 可编辑并持久化到 `SharedPreferences`，重启内核后生效 |
| 行为 | 打开应用自动启动内核、运行时每 3 秒刷新统计 |
| 流量统计 | 请求/响应/注入/隧道/升级/停滞/错误/出站字节 + 热门主机 Top3 |
| CA 证书 | 证书文件状态、完整 SHA-256 |
| 内核日志 | 最近 12 行（从文件尾部读 8 KB，避免整文件加载） |

实机验证：状态显示 `运行中 · 127.0.0.1:31191`；配置概览 `9 域名 / 2 白名单 / 32 排除 IP`；
统计显示 `193 请求 / 1 注入 / 2.6 MB`，热门主机 `github.githubassets.com 167`；
CA 指纹 `5a2efbad…57dde8` 与已知内核 CA 一致。

**WebView 持久化**：默认就是持久的（存在 app 私有目录 `app_webview/`），本次补了三处让登录态可靠：

- `CookieManager.setAcceptCookie(true)` + `setAcceptThirdPartyCookies(web, true)`（OAuth/资源跨域需要）
- `onPause` 里 `CookieManager.flush()`（cookie 写盘是异步的，刚登录就被杀进程会丢）
- 记住上次 URL，重启回到同一页面（`SharedPreferences`）

设备实测（本地静态站 `http://192.168.31.73:9099/`，避开 GFW 与站点跳转）：
写入 `localStorage.dsprobe` + `dsprobe` cookie → 回桌面（触发 onPause flush）→ `am force-stop`
→ 重启 app → 读回 `lsAlive:true, cookieAlive:true, href` 一致。**本地存储与 cookie 都跨进程存活。**

**新发现：内核后台被 MIUI 冻结会直接卡死整条链路。**
两次复现：内核进程 `S` 状态、`ss -ltn` 的 `Recv-Q` 持续增长（5 → 9），日志停在冻结前一刻，
app 侧 `ds-fetch` 连续 `timeout`/`Failed to connect to /127.0.0.1:31191`。
唤醒方式只有重启内核。当前的 `PARTIAL_WAKE_LOCK` + 前台通知不足以阻止 MIUI 冻结。

建议的根治方向：**把内核跑在浏览器 app 进程内**。方案 B 已不使用 VpnService，
不再需要「内核在独立包避免自我代理」这个约束，同进程可彻底绕开后台冻结；
代价是内核崩溃会带走浏览器进程，需要用子进程/异常隔离来兜。

### 0.7 合并为单 app（2026-10-29 上午，已完成）

伴生 app `org.BlueFrontier.DsCoreGo` 已从设备卸载、源码目录删除，内核并入
`org.BlueFrontier.DevSidecar`：

| 文件 | 作用 |
|---|---|
| `src/.../KernelRuntime.kt` | 进程内内核所有者。`start()` **同步绑定端口后返回**，WebView 首个请求不可能再和内核启动竞态 |
| `src/.../KernelService.kt` | 只剩前台通知 + PARTIAL_WAKE_LOCK，内核本身由 `KernelRuntime` 持有 |
| `src/.../KernelSettingsActivity.kt` | 原内核设置界面，入口是 URL 行右侧 ⚙ |
| `assets/` | `remote_config.json5` + `ca_ca.crt/key`（原 `kernel-app/assets`） |
| `libs/` | `classes.jar` + `libgojni.so` + 解包后的 `classes/` |

构建链变为三步，按序执行：

```powershell
. C:\Users\Nobody\android-toolchain\env.ps1
& D:\dev-sidecar-core-go\build-kernel-aar.ps1   # Go -> kernel.aar
& D:\dev-sidecar-android\sync-kernel.ps1        # aar -> libs/
& D:\dev-sidecar-android\build.ps1              # assets + gomobile + .so -> 单 APK
```

`build.ps1` 现在会：`aapt2 -A assets`、kotlinc classpath 挂 gomobile `classes/`、
d8 产出 app/gomobile/stdlib 三个 dex、`jar uf` 一并塞入 `lib/arm64-v8a/libgojni.so`。

实机验证：`ps` 里只剩 `org.BlueFrontier.DevSidecar`，`127.0.0.1:31191` 由该进程监听；
GitHub 仓库页完整渲染；后台 20 秒再加载新页面，`Recv-Q` 保持 0、`ds-fetch` 零失败
（合并前同样操作会因内核冻结全部超时）。

代价：内核 panic 会带走浏览器进程。后续可加看门狗或在 `KernelRuntime` 捕获 panic 后重启内核。

### 0.8 性能对比与 >1 MiB 截断 bug（2026-10-29 上午）

**对比结论**（脚本在 `D:\dev-sidecar-core-go\.smoke\bench-*.py`，同机同目标）：
内存 23 MB vs 235 MB、8 MiB 吞吐 60 vs 53 MB/s（Go 略优）；小请求延迟 +3.6 ms、
每请求 CPU +40%、并发 −14%（Go 略差）；GitHub 一轮稳定 538 ms，另一轮抖动到 16.4 s 且 2/3 失败。
**结论：性能底子够，但现在不应迁移**（缺 h2/ECH/热更新与镜像改写，且抖动未收敛）。

**压测中发现并修复的严重 bug**：`serveOne` 在预取成功后无条件 `resp.Body.Close()`，
超过 `prefetchMax`(1 MiB) 的部分在读一个已关闭的 body → **所有大于 1 MiB 的响应被静默截断**。

设备实证（同一个 4 MiB 文件，经手机内核取回）：

| 版本 | 结果 |
|---|---|
| 修复前装机 APK | `200`，1049800 字节，curl 报 `end of response with 3144504 bytes missing` |
| 修复后 | `200`，4194304 字节，SHA-256 与源文件一致，0.41 s |

修复：只有 `complete` 为真才关闭 body，流式路径保持可读。

**为什么当初会漏到手机上**：改的是 Go 源码，也只重建了 PC 用的 CLI；`kernel.aar` 停在
08:14、装机 APK 停在 08:48，而修复是 09:01。防复发措施：`sync-kernel.ps1` 写
`libs/.sync-stamp`，`build.ps1` 把 stamp 与最新 `.go` 的 mtime 比对，落后就拒绝打包
（实测：无 stamp 时报 `libs/ has no sync stamp`）。

### 0.9 配置版本与官方配置对齐（2026-10-29 上午）

设置界面的「配置概览」补上桌面版底栏有的元信息，并顺带发现手机一直在跑**裁剪过的配置**：

| 项 | 之前 | 现在 |
|---|---|---|
| 配置文件 | 7 KB（`.smoke` 精简版） | 49 KB 官方 `remote_config.json5` |
| 拦截域名 | 9 | **71** |
| 白名单 / 排除 IP | 2 / 32 | **7 / 52** |
| 配置版本 | 无 | `2026-10-16 15:30 · official` + 更新说明 |
| 跳过规则 | 无统计 | 1 条（RE2 不支持 lookaround） |

要让这份官方配置能加载，解析器补了三处（均有单测）：

1. **JSON5 裸键**：`app: {` / `metaInfo: {` → 仅在 `{`、`,` 之后且后接 `:` 的标识符加引号；
2. **宽松转义**：配置里写了 `"^(.+\.)?x\.com$"`（严格 JSON/JSON5 非法），按 JS 语义丢掉
   未知转义的反斜杠，并支持 `\xHH`、`\v`、`\0`、`\'`；
3. **RE2 不支持的 lookaround**：不再让整份配置加载失败，改为跳过该条并在日志与设置页
   计数（实测跳过 1 条 `*steamcommunity.com` 的负向断言规则）。

新增 `config.App/MetaInfo{id,version,updateLog}`，`KernelSummary` 输出 `configId`、
`configVersion`、`configUpdate`、`rulesSkipped`；设置页把 `yyyyMMddHHmm` 格式化成
`2026-10-16 15:30` 展示。

### 0.10 手机版配置更新（2026-10-29 上午）

桌面版会从 `app.remoteConfig.url` 拉配置、比较 `app.metaInfo.version`；手机版现在也做同样的事。

| 层 | 实现 |
|---|---|
| Go | 新包 `configupdate`：下载 → 解析 → 比对版本 → 原子写（tmp+rename）。无版本号的远端配置**拒绝安装**，避免降级到无元数据的版本 |
| 导出 | `mobile.UpdateConfig(remoteURL, destPath) string` |
| 设置页 | 新增「配置更新」卡片：显示更新源、`检查更新`、`恢复内置`；更新成功后自动重启内核 |
| 种子策略 | `KernelRuntime.ensureConfig`：`files/remote_config.json5` 已存在就**不再用 asset 覆盖**，下载的配置可跨重启保留；`恢复内置` 删文件后重启即重新播种 |

实测（真实官方源）：

1. 点「检查更新」→ `已更新到 2026-10-28 12:30，正在重启内核`，`handle` 1→2，配置版本从
   `2026-10-16 15:30` 变为 `2026-10-28 12:30`，更新说明变成「测试antigravity.google」。
2. `pm clear` 清空数据后重启：内核正常起来，配置版本仍是 `2026-10-28 12:30`，说明仓库内置的
   种子配置已刷新到同一版本（`assets/remote_config.json5` 现为 49,531 字节）。
3. 单元测试 `configupdate`：更新旧文件、二次检查返回 current、拒绝无版本远端、下载失败。

附带发现：桌面版本地配置停在 `2026-10-16 15:30`，官方已经发布到 `2026-10-28 12:30`，
桌面版重启一次即可自行更新。

### 0.11 负向断言改写（2026-10-29 中午）

配置里 4 条 lookaround 规则都是 `^(?!A|B)rest` 形状，RE2 不支持；之前只能整条跳过，
代价是该 host 的加速规则整体失效。这类规则有确定的等价形式：

```
^(?!A|B)rest   ≡   匹配 ^rest，且排除 ^A、^B
```

而 `exclusions` 本来就是规则 schema 支持的字段。所以改在 **`rules.Compile` 里做编译期改写**
（`rewriteNegativeLookahead`），而不是改配置文件——配置会被更新覆盖，代码改写不会。

覆盖的情况：

- 多个顶层备选（`a|b|c`），括号嵌套与 `\` 转义安全；
- 备选里含 `.*`（表达"不包含"）；
- 叠加断言（`^(?!A)(?!B)rest`）；
- 与规则自带的 `exclusions` 合并，写在同一个 `compiledRule` 上。

实测（真实 49 KB 配置）：`rules rewritten count=1`，且**不再出现 skipped**。单测覆盖前缀排除、
`.*` 备选、叠加断言，以及"反向引用这类 RE2 真不支持的依旧跳过并计数"。

附带发现：桌面内核匹配的对象是 `rOptions.path`（`options.js:238`，以 `/` 开头），所以
`(?!fonts)` 这种写法在两边都等于不排除任何东西。改写忠实保留了该行为，不是解析差异；
上游若要排除 `fonts.*` 主机，应改 host 段而不是 path 正则。

### 0.12 个人配置层（2026-10-29 中午）

桌面版的配置是分层的：内置 → 官方远程 → 个人远程 → 用户本地。手机版现在补上了**个人远程层**。

合并语义照抄 `core/src/merge.js` 的 `lodash.mergeWith`：对象深合并、**数组整体替换**、
`null` / `"[delete]"` 删除该键。层级顺序官方在前、个人在后，所以个人层覆盖同名项。

| 层 | 实现 |
|---|---|
| Go | `config.LoadWithPersonal`：先解析官方，再合并 `remote_config_personal.json5`，最后反序列化成 `Config`；个人层解析失败只记 warning，不拖垮内核 |
| 元信息 | `LoadResult` 分别保留 `Official` 与 `Personal` 的 `metaInfo`——合并后 `app.metaInfo` 会被个人层覆盖，分开展示才知道"官方版本是多少" |
| 导出 | `mobile.UpdatePersonalConfig(url, dest)`：个人层没有版本契约，**总是安装**（`configupdate.InstallUnversioned`） |
| 设置页 | 「个人配置」卡片：URL 输入框（持久化）、下载并应用、清除；成功后重启内核 |
| CLI | `cmd/ds-core` 也走合并，路径同桌面（配置同目录下的 `remote_config_personal.json5`） |

设备实测（把桌面版的 78 KB 个人配置用本地 HTTP 服务提供）：

```
配置版本  2026-10-28 12:30 · official
个人层    io.github.cute-omega · 2026-10-28 11:47
拦截域名  280 个   （官方单独只有 71）
```

用真实个人配置还暴露了两个 RE2 兼容缺口，一并修了：

1. **`*` 作为 path 键**：dev-sidecar 约定 `*` / `.*` / `true` 表示"匹配所有路径"
   （`utils/util.match.js` 的 `isMatched`），且以 `*`、`?`、`+` 开头的模式会先补一个 `.`。
   Go 原来直接 `regexp.Compile("*")` 失败，整条规则被跳过。
2. **`(?<name>…)` 命名分组**：RE2 只认 `(?P<name>…)`，机械等价改写；`(?<=` / `(?<!`
   仍保持不支持并计数。

修完后真实合并配置 `rules=280`、**零跳过**。

### 0.13 差距清单与批次 1（2026-10-29 下午）

先把差距写成可执行清单 [`FEATURE-GAPS.md`](FEATURE-GAPS.md)：逐项对照 `ds-core` 的实现，
并在真实配置上统计字段用量（`官方/个人` 行数），避免凭印象排优先级。

批次 1（本地应答 + host 匹配）已完成：

| 项 | 实现 | 验证 |
|---|---|---|
| `abort` | `proxy/local.go`：命中即本地返回（默认 403），支持 status/body/headers 覆盖 | 端到端 403 |
| `success` | 同上，默认 200；`success: {script:";"}`、204 无 body | 端到端 202 + body |
| `options` | CORS 预检本地应答，回显 Origin，Max-Age 默认 2592000 | 端到端头部齐全 |
| host 正则键 | `hostmatch.Match`：`^` 开头的键按正则用；`*.x` 同时匹配 `x`；`.x` 视为 `*.x` | 单测 |

优先级与真实配置一致（桌面 `priority`）：options(101) → success(102) → abort(103) → redirect(105)。

两个容易搞错的语义，按桌面实现照实复刻：

1. **`success: true` 同级的 `status: 204` 不会生效**。`success.js` 只在对象形式下读
   `response.status`，布尔形式直接 `200`。配置里那条「采集数据，快速成功」在桌面版同样返回 200。
2. **`(a|b).googleapis.com` 这类 host 键不是正则**。`domainMapRegexply` 只把「含 `*`」或
   「以 `^` 开头」的键当正则，其余是精确匹配键，所以该规则在桌面版也从不命中——不是我们的缺口。
   真正的正则键（`^…`）官方 15 条、个人 20 条，现在能命中了。

另外补了 host 排序：字面量优先于 `*` / `^` 模式，与桌面「精确匹配先命中」一致。

### 0.14 批次 2：热更新与响应头改写（2026-10-29 下午）

**配置热更新。** 以前改端口/规则都要重启内核，重启会掐断在途请求。现在把"由配置派生的状态"
整体放进一个快照指针（表、DNS、白名单、排除 IP、不校验证书目标），
`Reload` 编译出新快照后原子替换；在途请求继续用旧快照跑完。

| 部分 | 实现 |
|---|---|
| `proxy.state` | `atomic.Pointer[state]`，`Table()` 等方法读当前快照 |
| `Server.Reload` | 重新编译规则表后替换，监听与 CA 不动 |
| `Server.Watch` | 2 秒轮询官方 + 个人配置的 size/mtime，变了就重载；解析失败保留旧快照 |
| 移动端 | 更新/清除配置后**不再重启内核**；`resetConfig` 删文件后立即重新播种，交给 watcher 生效 |
| 设置页 | 文案改为「规则将自动生效」 |

验证：

- 单测：`Reload` 换规则、`Watch` 检测文件变化、坏配置保留旧规则；
- CLI 端到端：改文件后 3 秒内 `redirect` 目标从 `a.test` 变 `b.test`，日志只有 `config reloaded`；
- 真机：给个人配置加一条规则后点「下载并应用」，拦截域名 280 → **281**、`handle` 仍是 **1**。

**responseReplace。** 桌面版这个拦截器只改响应头（不改 body），且只对 200 生效：

- `headers` 逐项覆盖，值写 `"[remove]"` 表示删除该头（配置里不能写 null）；
- `doDownload` 加 `content-disposition: attachment`、缺省 `content-type: octet-stream`，
  未配置 `cacheDays` 时删掉 `cache-control` / `last-modified` / `expires`；
- 命中后加 `DS-ResponseReplace-Interceptor: headers` 或 `download:<文件名>,headers`。

三个单测覆盖：头覆盖/删除/新增、下载头与缓存头清理、非 200 不动。

### 0.15 批次 2 收尾：requestReplace / cacheDays / SNI 校验（2026-10-29 下午）

**requestReplace。** 与 responseReplace 对称，但作用在发往上游的请求上：

- `headers` 逐项覆盖，`"[remove]"` 删除（`User-Agent` 要写成空值，否则 Go 会自动补
  `Go-http-client/1.1`——这是实测踩到的坑）；
- `doDownload` 且路径含 `DS_DOWNLOAD` 时，把标记从路径里剥掉再发上游，并置下载标志，
  供 responseReplace 加 `content-disposition`；
- 响应头回填 `DS-RequestReplace-Interceptor`。

**匹配主体修正。** 桌面版拿去匹配的是 `rOptions.path` = **path + query**（Node 的
`urlObj.path`）。我们之前只传 path，导致配置里 `^.*\?DS_DOWNLOAD$` 这类规则永不命中。
已改为 `req.URL.RequestURI()`，并补了"没有标记就不命中"的回归用例。

**cacheDays。** 不是本地磁盘缓存，而是 HTTP 缓存语义：

- 请求侧：GET + 有 `If-Modified-Since` + 仍在窗口内 + 未要求 `no-cache/no-store` → 直接回 **304**
  （`DS-Interceptor: cache: <秒>`）；
- 响应侧：200~303 改写 `Cache-Control: public, max-age=<秒+1>, immutable`、
  `Last-Modified`、`Expires`；原响应 max-age 更大且未开 immutable 时保留原值并记
  `skip: a > b`；`success` 桩响应也带上同样的缓存头。

**SNI 校验（真 bug）。** 验证 GitHub 时发现页面只出来无样式文字，追下去是：个人配置给
`github.githubassets.com` 的静态资源配了 `sni: baidu.com`（无 `verifyHost`）。桌面版的
`sni.js` 在应用 SNI 改写时**直接把 agent 换成 `rejectUnauthorized: false`**——伪装 SNI 拿不到
对应证书，所以干脆不校验；`verifyHost` 是给 `proxy` 规则用的（proxy 会把 hostname 换成目标域名）。
我们之前按"请求域名"强制校验，比桌面严格，于是所有 `sni:` 规则的资源全部失败。

修正后：`sni` 规则跳过上游校验；`proxy` 规则保持校验（默认校验 proxy 目标，可用 `verifyHost`
覆盖）。对应单测从"无 verifyHost 必须 502"改成"plain sni 不校验 / proxy 规则才校验"。
真机验证：GitHub 从"无样式文字"变成完整样式（导航栏、彩虹条、按钮）。

### 0.16 批次 3：增强功能、测速、镜像兜底（2026-10-29 下午）

**overwall（"增强功能"插件）。** 语义是把命中的域名改写到中继服务器：
`<server>/<path>/<原host><原path>`，带 `dspassword` 头，响应回 `DS-Overwall: config:<id>`。

关键的一步是**先对齐生效条件**：桌面 `createOverwallMiddleware` 在服务器列表为空时直接返回
null。`ow-prod.docmirror.top` 那套默认服务器只写在插件模块自己的 `config.js` 里，**并没有并进
运行时的代理配置**，所以尽管个人配置写了 `enabled: true`，桌面版实际是关闭的。

我是先实现了"内置默认服务器"才发现的——对照实验：同一台机器上请求 jsdelivr，桌面返回 200 且
**没有** `DS-Overwall`，我的实现返回 502。随后去掉默认值注入，行为与桌面一致。

**代理镜像兜底（`backup`）。** 规则里大量使用 `proxy` + `backup`（jsdelivr、huggingface 等）。
桌面用请求计数做镜像优选；这里实现为"主目标连不上就按序试备用"。单测：主目标不可达时自动切到
备用并返回 200。

**拨号预算 5s → 8s。** 手机直连 `fastly.jsdelivr.net` 要 5.1 秒，正好卡在 5 秒预算边缘，导致
`no usable address` / 502；桌面同一规则 7.4 秒才拿到可用连接仍然成功。放宽后手机侧 jsdelivr
从 502 变 200（2.18s，桌面 2.29s）。

**IP 测速（`speedTest`）。** 桌面定时探测 `hostnameList`，把最快的 IP 排到前面。这里实现为启动
预热：解析列表里的域名、逐个试连并写入与我们竞速拨号共用的排序（失败降权）。日志可见
`speedtest prewarm host=github.com addresses=1 reachable=1`。与桌面的差异：不按 `interval`
周期重测——正常流量会持续更新排序。

**`ipv6only`：不是缺口。** 在整个 `ds-core` 与 app 源码里**零引用**，个人配置里那个键是死键，
忽略它才是忠实。

### 0.17 批次 4：本地配置层与逐条开关（2026-10-29 下午）

桌面版的配置是四层：内置 → 官方远程 → 个人远程 → **用户本地**。手机版补上了最后一层，并把它
变成可编辑的形态。

| 部分 | 实现 |
|---|---|
| 分层加载 | `config.LoadLayers(official, personal, user)`，按序合并；坏的层只记 warning |
| 来源记录 | `LoadResult.Sources`：每个 host 块最后由哪一层提供（合并顺序决定） |
| 本地层 | `files/user_config.json5`，设置页内嵌编辑器，保存前用 `ValidateConfig` 校验 |
| 逐条开关 | `files/disabled_rules.json`，编译规则表前过滤；复用热更新，2 秒内生效 |
| 规则列表 | 新 `RulesActivity`：按域名分组，显示来源层与规则条数，一行一个开关 |
| CLI | 同样走三层加载（实测本地层加一条规则：71 → 72） |

真机验证：

1. 规则列表显示「共 280 个域名、288 条规则」，每行标注 `来源 personal`。
2. 关掉 `(userlocations|…).googleapis.com` → 状态行提示「已停用…约 2 秒生效」，设置页
   **拦截域名 280 → 279**，内核 `handle` 未变（热更新，不断线）。
3. 再打开 → 回到 280。

已知粒度差异：桌面按"host + path 规则"逐条开关，这里做的是**按 host 整体开关**（一个域名下的
多条规则一起关）。对使用者来说更直观，但要精确关掉某一条 path 规则，目前得写本地层。

### 0.18 批次 5：HTTP/2 判误、NAT64、ECH（2026-10-29 下午）

**HTTP/2 不是缺口。** 我之前把它当缺口是错的。桌面 `mitmproxy` 里 `ALPNProtocols` 只出现在两处，
值都是 `['http/1.1']`，整个仓库没有任何 `http2` 模块——桌面内核同样只有 HTTP/1.1。两侧对齐，
这一项从清单里划掉。

**NAT64 / DNS64。** 按桌面语义实现：`server.dns.nat64` 的 `domains` 命中时把 A 记录合成到配置的
前缀里，原生 IPv6 答案原样保留。**前缀为空时整个功能不启用**（桌面 `toNat64Address` 对空前缀直接
拒绝，不内置任何公共网关）——当前配置的 `prefix` 就是空的，所以它和 overwall 一样处于"实现但休眠"。

**ECH。** Go 1.26 的 `crypto/tls` 原生支持 ECH 客户端，所以不用像桌面那样自带 TLS 栈，只需要拿到
`ECHConfigList`：

| 步骤 | 实现 |
|---|---|
| 查询 | `dnsres.LookupHTTPS`：HTTPS(65) 记录查询，走配置里指定的 DNS 提供者（`cf-DoH`） |
| 解析 | `ParseHTTPS`：priority + target + SVCB 参数，取 `alpn`(key 1) 与 `ech`(key 5) |
| 多记录 | 一个域名常返回多条（别名 + 参数），**扫描全部**取第一条带 ECH 的 |
| 接线 | `dialOne` 设置 `EncryptedClientHelloConfigList` 并把 `MinVersion` 提到 TLS 1.3 |
| 回退 | 不装拒绝回调，ECH 被拒时自动退回明文握手 |

实测（真实配置 + 真实 DNS）：`crypto.cloudflare.com` 取到 **71 字节** ECHConfigList，日志
`ech enabled ... bytes=71` 后完成拨号；`www.cloudflare.com` 与桌面一致返回 200。`/cdn-cgi/trace`
这个特定端点在**不开 ECH 时同样 502**（桌面版直接超时），与本功能无关。

### 0.19 批次 6：HTTP/2 更正与实现（2026-10-29 傍晚）

**先把结论改回来。** §0.18 里我写"HTTP/2 不是缺口，桌面也只有 HTTP/1.1"是错的。错在检索范围：
只在 `mitmproxy/src` 里搜了 `ALPNProtocols`，没搜到 `FakeServersCenter.js`——它的第一行就是
`require('node:http2')`，第 123 行：

```js
fakeServer = http2.createSecureServer({ allowHTTP1: true, ... })
```

也就是说**客户端到代理这一段桌面就是 h2**，浏览器经 ALPN 协商，不支持的客户端回落到 h1。
真正只有 h1 的是**代理到源站**那一段（依赖只有 `agentkeepalive` / `tunnel-agent`）。

用 Go 的 h2 客户端经 CONNECT 打桌面版内核（31181）可以复现这个事实：`proto=HTTP/2.0 code=200`。

**实现。** 用 `golang.org/x/net/http2`，与桌面的 `allowHTTP1` 对齐：

| 环节 | 做法 |
|---|---|
| ALPN | TLS 配置改为 `NextProtos: ["h2", "http/1.1"]` |
| 分流 | 握手后看 `NegotiatedProtocol == "h2"`，走 `http2.Server.ServeConn`，否则走原 h1 循环 |
| 规则引擎 | 新增 `h2Handler`，复用 `localResponse`/`cacheHit`/overwall/`applyRequestReplace`/`applyCacheHeaders`/`applyResponseReplace`/注入，两个协议同一套判定 |
| 内部端点 | `/__ds_stats__` 与 `/____ds_script____/` 在 h2 下同样本地应答，否则页面上的注入脚本会 404 |
| 头处理 | h2 禁止的连接级头（`Connection`/`Transfer-Encoding` 等）在回写前剥掉 |

为避免两套逻辑漂移，把 `serveLocal`/`serveCached` 的判定抽成纯函数 `localResponse()` 与
`cacheHit()`，h1 只负责按行序列化，h2 交给 `http.ResponseWriter`。

**验证。**

| 场景 | 结果 |
|---|---|
| 单测：h2 直通 | `ProtoMajor=2`，响应体正确 |
| 单测：h2 + abort 规则 | 403 且带 `DS-Interceptor: abort` |
| 单测：h2 下的 stats 端点 | 200，JSON 含 `"requests"` |
| PC CLI + 真机 | 均 `negotiated ALPN="h2"`、TLS1.3、`HTTP/2.0 200`（cloudflare / github） |
| 桌面 reference | `HTTP/2.0 200`（同一探针） |

真机第一次测出 `unexpected EOF`，查 logcat 发现进程被系统 SIGKILL（后台被杀），与 h2 无关；
把应用拉回前台后即通过。

### 0.20 批次 7：拿桌面的测试当交叉验证（2026-10-29 傍晚）

这批的起因是一个直接的问题："这些测试是你写的，还是用的桌面版的？"——答案是我写的，而桌面版
自己有 mocha 测试（`core/test/*.js`、`mitmproxy/test/*.js` 共 21 个文件），我此前没拿来对照。
把其中纯函数的部分搬成 Go 用例后，**发现了一个真 bug**。

| 桌面测试 | 搬成 | 结果 |
|---|---|---|
| `mitmproxy/test/matchUtilTest.js` | `hostmatch/desktop_vectors_test.go`（19 条） | 全过：`*bbb.com` 能匹配 `xbbb.com` 但不匹配 `bbb.comx`；正则键 `^.{1,3}ddd.com$` 不匹配 `ddd.com`；`.github.com` 匹配 `github.com` 与 `aa.bb.github.com` |
| `core/test/mergeTest.js` | `config/desktop_merge_vectors_test.go` | **失败 → 修 bug**：桌面在 `doMerge` 之后还有一步 `deleteNullItems`，把合并结果里残留的 `null` 全删掉；我只在合并时处理了 `src` 的 `null`，基配置里自带的 `null` 会留下来 |
| `core/test/configTest.js` | `config/desktop_config_vectors_test.go`（5 条） | 修完后全过：上层 `null` 删除下层默认项、`false` 覆盖、用户新增项可删 |

修法：`Merge` 拆成 `mergeInto` + `dropNulls`，后者对应桌面的 `deleteNullItems`（递归清 `null`
与 `[delete]`）。真实配置复查仍是 280 条规则，行为无变化（现有配置里没有 `null`）。

结论：自己写的测试只能证明"按我理解的语义实现"，抓不到理解本身的偏差；桌面的用例才是基准。

### 0.21 批次 8：补齐默认模式缺口（2026-10-30）

用户要求"把这些补上"，但实机不可用，因此验收改为：Go 单测 + CLI 端到端 + APK 构建。

**`server.setting` 生效。** 桌面这一整段此前的解析是空的，现在四项都有了：

| 字段 | 桌面语义 | 我们的实现 |
|---|---|---|
| `defaultTimeout` / `timeoutMapping` | 映射 → 默认 → 60000 | 映射 → 默认 → 20s（桌面内置默认就是 20000） |
| `verifySsl` | 全局关闭上游校验 | 写入上游 `tls.Config`；规则级 `unVerifySsl` 仍优先 |
| `script.enabled` | 注入中间件总闸 | 关闭后 `maybeInject` 直接返回原体 |
| `allowTls12` / `tlsVersionMapping` | 见下 | 见下 |

**一个被实测推翻的假设。** 照代码读，`allowTls12: false` 意味着上游只谈 TLS 1.3。我按这个实现后，
`www.baidu.com` 立刻 `502`：日志里是 `remote error: tls: protocol version not supported`
——百度只支持 TLS 1.2。但同一台机器上桌面版访问百度是 **200**。

原因在于桌面的 `allowTls12` 只作用于**连接池 agent** 那条路径（`util.js` 的 `createAgent`），
而 `Connection: close` 的请求走 `agent = false`、用 Node 默认下限（TLS 1.2）。我们每个请求都是新连接，
等价于后一条路径。所以正确地改回 TLS 1.2 下限，`tlsVersionMapping` 仍可按域名把某个站钉到 1.3。

**`cloudflareRoute`。** 按桌面的 `lib/cloudflareRoute.js` 移植：每小时拉
`api.cloudflare.com/client/v4/ips` 的网段，每 5 分钟解析一次 `preferredEndpoint`，命中 CF 网段且域名
不被排除时把地址换成优选地址。`mode` 支持 blacklist（默认，名单内跳过）与 whitelist（仅名单内改写）。
六条单测覆盖：默认关闭、改写、两种模式、去重、空实现透传。

**`excludeDomesticDomainAllowList` 划掉。** 它只被 `set-system-proxy/index.js` 引用，即只影响 PAC 文件
生成；Android 没有系统代理消费者，这个功能在这里不存在对应物。

**按域名统计。** `perHost` 记录 requests / bytes / errors / ms，h1 与 h2 两条路径都写入，
`/__ds_stats__` 一并返回。

**模式切换。** `config.SetMode` 按桌面 `applyMode` 的三行规则写本地层（safe 关拦截、default 开拦截、
ow 额外开中继），`CurrentMode` 读回。设置页新增「模式」卡片（默认/安全/增强）。

**界面新增。**

| 页面 | 内容 |
|---|---|
| 流量明细 | 总量 + 按域名排行的请求数、大小、平均延迟、错误数，3 秒自动刷新 |
| GitHub 状态 | 经内核代理逐个探测 6 个 GitHub 端点，显示状态码与耗时 |
| 规则编辑 | 点规则行改该域名的规则 JSON，长按或「恢复默认」删除本地覆盖 |

规则编辑这里踩到一个语义坑：分层合并是**深合并**，用户删掉的字段会被下层"复活"。编辑器展示的是
合并结果，所以写回时对下层有、新文本没有的字段补 `null`（`nullDiff` 递归处理），实现真正的替换。

**验证。**

| 项 | 结果 |
|---|---|
| `go test ./...` | 7 个包全绿，**126 个用例**（新增 21 个） |
| CLI：拦截开关 | 同一规则 `abort`：开启 → 403，`intercept.enabled=false` → 200 |
| CLI：按域名统计 | `example.com req=2 bytes=577 ms=673`、`github.com req=1 bytes=529997` |
| CLI：TLS 下限 | 百度/example/github 均 200（改回 1.2 后） |
| APK | 构建通过，6.9 MB；实机不可用，未安装 |

**帮助页**也补上了：说明拦截原理、证书安装、三种模式的差别、配置层级与常见问题。

**还差的（下一批）**：服务分组里"按配置项"（拦截/DNS/预置IP 各自）的编辑，目前只到域名块级别；
以及一个这轮才发现的性能差异——桌面默认 **keep-alive 复用上游连接**，我们每个请求新建连接。

### 0.22 批次 9：上游连接复用（2026-10-30）

桌面的 agent 是 `keepAlive: true` 的连接池（`freeSocketTimeout` 5s、`keepAliveTimeout` 30s），
我们此前每个请求都新建 TCP + TLS。这一批补上：

| 部分 | 实现 |
|---|---|
| 池 | `connPool`：按 key 分组、TTL 30s、全局上限 64，超限淘汰最旧 |
| key | `scheme://host:port` + 规则里的 `sni` / `verifyHost` / `proxy` + `insecure`，不同握手参数的连接不串用 |
| 归还 | 响应体**完整读完后**才放回池；`Connection: close`、HTTP/1.0、客户端提前断开都不归还 |
| 失效 | 取出的连接若写失败，视为被中间设备回收，记 debug 日志后重新拨号一次 |
| 两条路径 | h1 的 `serveOne` 与 h2 的 `h2Handler` 共用同一个池（浏览器现在多用 h2，只做 h1 等于没做） |

**验证。**

| 项 | 结果 |
|---|---|
| 单测：h1 复用 | 4 次请求 → 源站只接受 **1** 条连接 |
| 单测：h2 复用 | 4 个流 → 源站只接受 **1** 条连接 |
| 单测：禁止复用 | 源站返回 `Connection: close` → 3 次请求 **3** 条连接 |
| 单测：并发隔离 | 20 个并发请求各自拿到自己的 body，无串包 |
| CLI：真实站点 | 同域名连续 5 次，日志 **4 次 pool hit**、0 次失效；github 首次 0.49s → 后续 0.31s |
| CLI：其他站点 | example / cloudflare / gitee / baidu 全部 200 |
| 全量 | 7 包、**130 个用例**全绿 |

h2 一开始命中 0 次，原因是 `h2Handler` 里还留着 `out.Close = true`——请求头写着 `Connection: close`，
源站自然每次关连接。改成 keep-alive 后立刻命中。

---
---

## 0. 一句话现状

**无 VPN 的 WebView MITM 链路已闭环**：手机 WebView → app 内拦截 → 本地 Go 内核 MITM →
上游 TLS → 页面完整渲染（曾实证 GitHub 首页 + 160+ 子资源）。内核侧 https 全链路耗时健康
（PC 直连复测：3 连 200，单次 0.56–0.75s）。当前唯一未解：内核返回全 200 的情况下
WebView 仍白屏（网络层已排除，疑渲染/响应头兼容）。

---

## 1. 目标与约束

| 项 | 内容 |
|---|---|
| 目标 | 桌面 dev-sidecar 的 Go 内核（`D:\dev-sidecar-core-go`）移植到安卓自包含可用 |
| 准绳 | 行为对齐 JS 内核 `D:\JavaScript\ds-core`（用户要求"完整照搬"） |
| 约束 | Go 内核**零第三方依赖**；无 root；免 VPN（方案 B 定稿后无授权弹窗） |
| 包名 | 单 app `org.BlueFrontier.DevSidecar`（内核同进程） |
| 通知 | 不自己造（已用 `whendone-plus` skill，长命令自动桌面提醒） |

## 2. 架构（方案 B，定稿）

```
WebView
  └─ WebViewClient.shouldInterceptRequest(全部 http/https)
       └─ HttpURLConnection(Proxy(HTTP,127.0.0.1:31181))   ← app 内显式代理
            └─ Go 内核 MITM (127.0.0.1:31181)
                 ├─ 动态签发叶子证书（内核 CA，app 端 KernelTrust 校验放行）
                 ├─ DNS 选择链（PreSet / mapping / provider / 系统 DNS）
                 ├─ race dial + 上游 TLS（SNI 覆盖 + 链校验）
                 └─ 请求/响应透传（响应头 10s 超时 + 坏 IP 沉降 + 换 IP 重试）
```

两组件（2026-10-29 合并，内核已并入主 app）：

| 组件 | 路径 | 产物 |
|---|---|---|
| app（WebView 壳 + Go 内核同进程） | `D:\dev-sidecar-android\` | `build\dscore.apk` |
| Go 内核源码 | `D:\dev-sidecar-core-go\` | `kernel.aar` → `libs\classes.jar` + `libs\libgojni.so` |

**方案 A（路由级 VPN + setHttpProxy）已实测判死**：`bindProcessToNetwork(vpn)=true` 后
Chromium（WebView 151）仍不认 VPN 网络的 per-network 代理（只认默认网络 ProxyTracker 广播，
WiFi 空代理）→ WebView 直连超时、内核 0 连接。**不要再尝试方案 A。**

---

## 3. 已完成工作

### 3.1 Go 内核（`D:\dev-sidecar-core-go`，已提交）

| commit | 内容 |
|---|---|
| `08af409` | JSON5 配置、host/path 规则、按需 CA、MITM 代理、CLI |
| `f73c3f7` `f9d4904` | DNS 子系统完整移植（DoH/DoT/TCP/UDP + SNI 改写） |
| `d3512d6` | 无映射域名直走系统 DNS（消除 provider 前置延迟税） |
| `69577e4` | 上游 dial 超时 10s→5s（墙掉的 IP 快速失败） |
| `f87dac1` | **race dial**：每批 6 候选并发 dial+TLS，先可用者胜，败者 `DNS.Note` 沉降 |
| `3a8073f` | **上游响应头 10s 超时 + 坏 IP 沉降 + 同请求换 IP 重试一次** |

### 3.2 安卓侧（`D:\dev-sidecar-android`）

- `ProxyFetchWebViewClient.kt`：方案 B 核心（拦截转发、KernelTrust、Cookie 双向、501 明示）
- `MainActivity.kt`：URL 框显式 `IME_ACTION_GO`（修 MIUI 搜狗输入法吞回车）
- `KernelService.kt`：PARTIAL_WAKE_LOCK + 前台通知，抗 MIUI 息屏冻结（配合"省电无限制"）
- 包名改为 `org.BlueFrontier.*`（避开旧名 `com.bluefrontier.dscore`）
- 构建链：`build-kernel-aar.ps1`（Go，出 AAR）→ `sync-kernel.ps1`（解出 libs/）→ `build.ps1`（打单 APK）

### 3.3 关键修复：502 读超时（本次会话核心）

**现象**：冷启动后 WebView 加载 github.com 主页，30s 后 502（`SocketTimeoutException`）。

**定位过程**（逐步排除）：
1. plain http 同路径 300ms 出 301 → 卡点只在 https
2. 加 dial 逐候选 DEBUG 日志 → race dial 正常（冷启动 57–766ms 出 winner）
3. 加 serveOne 三段日志（写入/收到响应/回写）→ 发现两类不同问题：
   - **一次真正的内核卡死**：winner 后 30s 无任何日志 → 上游地址"握手成功但随后静默丢包"（GFW 黑洞），`http.ReadResponse` 无超时无限阻塞，坏 IP 也不会沉降
   - **其余几次是 PC↔手机 adb 通道卡死**，请求根本没到内核（同期 adb 命令全超时），不是内核问题

**修复**（`3a8073f`）：
- 上游响应头 `SetReadDeadline(10s)`，响应头到达后立即清空（body 不限速）
- 超时/读写失败 → `DNS.Note(host, ip, true)` 沉降该 IP
- 同一请求内换 IP 重拨一次（fresh race dial 自动避开坏 IP），第二次才 502

**验证**（USB adb，PC curl 经内核）：
```
run1: code=200 size=580267 t=0.75s
run2: code=200 size=580271 t=0.63s
run3: code=200 size=580271 t=0.56s
```
winner 稳定 20.27.177.113（<150ms），DNS 学习已把它顶到候选队首；其余候选
"operation was canceled"（被 winner 取消，符合设计）。

---

## 4. 关键结论（实测）

1. **方案 A 不可行**，方案 B 可行（无 VPN、无授权弹窗）。
2. **race dial 是首次连接的关键**：13 候选里墙掉的占前 6 位，串行 5s×6 超过 WebView 30s 预算。
3. **"TLS 握手成功"≠"地址可用"**：GFW 黑洞地址握手能过、后续全丢，必须有响应超时兜底。
4. **DNS.Note 学习有效**：失败沉降后二次连接好 IP 自动排前（日志可见候选顺序变化）。
5. **MIUI 睡死**：空闲 2–3 分钟后整进程线程停摆（State=S、CPU 0 tick、Recv-Q 堆积）；
   wakelock + 省电无限制已通过 10 分钟静置复测。注意：**改包名会重置省电设置**，需重设。
6. **PC curl + `--cacert assets\ca_ca.crt`** 可在 PC 复现手机内核的 https 全路径
   （比 WebView UI 自动化快得多，是排障主力手段）。

---

## 5. 遗留问题

| # | 问题 | 状态 | 备注 |
|---|---|---|---|
| 1 | **WebView 白屏**（内核资源全 200） | 未解 | 网络层已排除；下一步查响应头/渲染（`Transfer-Encoding` 剥离、`Content-Length` 缺失、CSP 等） |
| 2 | 等价性未验证 | 未做 | 见 §6 |
| 3 | 第 3 层加速（脚本注入/响应体改写） | 未移植 | "真快"的关键，见 §7 |
| 4 | ECH / NAT64 | 未移植 | 下一批 DNS 功能 |
| 5 | POST 请求 | 501 | `shouldInterceptRequest` 拿不到请求体（平台限制），深度方案是 netstack |

---

## 6. 与 JS ds-core 的等价性（结论：未证明）

- **ds-core-go 现有 32 个单元测试**，分布在 `proxy/dnsres/config/rules/certcache`，
  是"按移植者理解写的单测"，**不是与 JS 行为的对照验证**。
- **JS 侧几乎没有测试基础设施**：`D:\JavaScript\ds-core` 全仓库仅 `core/test/regex.test.js`，
  没有可作为 oracle 的测试集。
- 因此"证明行为一样"需要自建**差分测试**：固定配置 + 可控 mock origin，同一场景集
  同时喂 JS 内核与 Go 内核，比较状态码/关键 header/body 字节/时序特征。
  预计 15–20 个场景（MITM、redirect、sni、preset、plain http、chunked、gzip/br、
  keep-alive、502 语义、CONNECT 异常…），约 1.5 天工作量。
- **建议顺序**：白屏收尾 → 差分测试骨架 → 第 3 层脚本注入（行为最复杂，正好用差分测试当回归网）。

---

## 7. 下一步（按优先级）

1. **修 WebView 白屏**：对照 app 侧 `WebResourceResponse` 构建（mime/charset/headers）与内核
   `resp.Write` 输出，逐个 header 排查；可在 `http.ReadResponse` 后打印响应头对比 PC curl。
2. **差分测试骨架**（§6）。
3. **第 3 层加速**：`script` / `tampermonkeyScript` 中间件移植
   （参照 `mitmproxy\src\lib\proxy\middleware\InsertScriptMiddleware.js`：
   InsertScript 模式、chunk 级 `</head>`/`</body>` 二进制切拼、gzip/deflate/br、
   `/____ds_script____/` 自托管、CSP nonce 透传、github 特判）。
4. **ECH / NAT64** DNS 功能。
5. 收尾提醒用户：为 `org.BlueFrontier.DevSidecar` 设置 省电无限制 + 自启动 + 最近任务锁卡。

---

## 8. 环境与快速恢复

### 8.1 设备

- 手机：Xiaomi `25053RP5CC`（HyperOS/Android 16，未 root）
- **首选 USB adb**：serial `0789B48009A40540`（Wi-Fi 无线调试会掉线、端口每次变化，
  需要 mDNS 或扫端口找端口，不可靠）
- 备选无线：`192.168.31.113:<动态端口>`（MIUI 无线调试每次重开端口变化；
  可用 PowerShell 扫 30000–50000 找 OPEN 端口）

### 8.2 常用命令

```bash
D=0789B48009A40540
adb -s $D shell 'ss -ltn | grep 31191'                                  # 内核端口（31181 禁用）
adb -s $D shell am force-stop org.BlueFrontier.DevSidecar               # 停 app（内核随进程一起停）
adb -s $D shell am start -n org.BlueFrontier.DevSidecar/.MainActivity \
    --es url "https://github.com/torvalds/linux"                        # 起 app（内核同步拉起）
# 内核日志与统计：app 内点 ⚙ 进「内核设置」看；/__ds_stats__ 也可经代理读取
```

内核与浏览器同进程，**不再有独立的 `DsCoreGo` 进程需要单独启停**；
PC 侧读日志需先给主 app 开 `debuggable` 才能 `run-as`，否则用设置界面看。

PC 复现 https（**注意：转发必须用别的本地端口，见坑 1**）：

```bash
adb -s $D forward tcp:43181 tcp:31191        # 本地端口不要用 31181
curl -sv --proxy http://127.0.0.1:43181 --ssl-no-revoke \
     --cacert D:/dev-sidecar-android/assets/ca_ca.crt \
     -o /tmp/gh.html https://github.com/
```

### 8.3 构建

```powershell
# 三步串行：Go AAR -> 解出 libs -> 打单 APK（不要用 & 并发）
powershell -NoProfile -Command "& 'D:\dev-sidecar-core-go\build-kernel-aar.ps1'"
powershell -NoProfile -File D:\dev-sidecar-android\sync-kernel.ps1
cd D:\dev-sidecar-android; powershell -NoProfile -File build.ps1
# 安装（只有一个包了）
adb -s 0789B48009A40540 install -r D:\dev-sidecar-android\build\dscore.apk
```

装包后建议校验二进制确实更新（zip 内 `libgojni.so` 搜新日志字符串）：

```powershell
# 解压 kernel.aar 后
Select-String -Path '<解压目录>\jni\arm64-v8a\libgojni.so' -Pattern 'dial winner' -Quiet
```

---

## 9. 坑与教训

1. **`adb forward tcp:31181` 会劫持 PC 本机 31181**（重要事故）：
   PC 上桌面版 dev-sidecar 的 mitmproxy 绑 `0.0.0.0:31181`，adb 的 forward 绑
   `127.0.0.1:31181`；Windows 路由把回环流量优先给更具体的监听者 → **浏览器/QQ 等
   系统代理流量全被 adb 吃掉**，CONNECT 无响应 → 全网超时，而桌面版日志仍显示
   "代理服务已启动"（通配绑定不冲突，检测不到）。**铁律：PC 侧复测一律用 41181 等
   不冲突端口；一旦怀疑被劫持，`adb forward --remove-all` 并 `netstat -ano | grep 31181`
   确认只有 mitmproxy 在监听。**
2. **Go AAR 必须重新同步再打包**：改了 Go 代码只跑 `build.ps1` 会打进旧 `libgojni.so`，
   表现为"代码没生效"。正确顺序 `build-kernel-aar.ps1` → `sync-kernel.ps1` → `build.ps1`，
   构建后校验 `.so` 里能搜到新日志字符串。
3. **git 提交可能卡死**：本仓库 `commit.gpgsign=true`，gpg agent 偶发挂起 →
   用 `git commit --no-gpg-sign`。
4. **MIUI 全新包名首装会被拒**（`INSTALL_FAILED_USER_RESTRICTED`）：需手机端确认
   "继续安装"/开启"USB 安装"；同包 `install -r` 一般不需要。
5. **USB 插上仍 offline**：多为"仅充电"模式；下拉通知切成"传输文件"，或关开 USB 调试、
   撤销 USB 调试授权后重插，才会弹授权框。
6. **PC 上 grep 对 aar/apk 无效**（zip 压缩），需解压后查 `libgojni.so`。
7. 手机 shell 里复杂引号会被吃：用 `printf "...$'\r'..."` 或 `echo`，复杂 grep 回 PC 做。
8. 内核日志时间戳为 **UTC**（比本地慢 8h）；WebView 复测前 **force-stop 主 app**，
   否则残留 502/白屏缓存页干扰判断。
9. 早期"线程被暂停"判断部分被推翻：那次是旧 aar（race dial 未进二进制）造成的假象；
   现网卡死证据以 serveOne 阶段日志为准。


### 0.23 批次 10：实机验证与三个真 bug（2026-10-31）

设备接上后把之前只能"单测 + CLI"的项跑了一遍，抓到三个只在真机上才暴露的问题。

**1. 预置 IP 失效后不会回退（影响最大）**

`github.com` 的 `preSetIpList` 是三个固定地址，全部超时后我们**不再回退到真实 DNS**，
于是这个域名永久 502，每次都要等满 8 秒。桌面版同网络下 0.65 秒返回 200。

修法两层：`dnsres.LookupFallback` 跳过预置列表走常规 DNS；拨号失败后记住"这个域名的预置地址已死"
十分钟，期间优先走 DNS。修完首次 0.73 秒、后续 0.32 秒。

**2. GitHub 状态页不认内核 CA**

6 个端点全部 6-12 毫秒"失败"——不是网络问题，是 TLS 校验直接失败：内核用自签 CA 签站点证书，
而这个页面没有用 `KernelTrust`。接上后变为 `可达 5/6`（第 6 个是 404，本来就不可达）。

**3. 可达计数取错字段**

`Pair` 的 `second` 是毫秒，代码却拿它判断 200..399，于是 5 个成功显示成 `可达 1/6`。改取 `first`。

**4. 规则行没有绑定点击**

对话框代码写好了却打不开——`row.isClickable = true` 那次字符串替换静默失败。改用会报错的编辑工具补上，
真机点开能看到合并后的规则 JSON（`{" .*": {"abort": {}}}`）。

这一轮还发现同一类问题第二次：统计接口的 `pool` 字段也曾因静默替换失败而没生效，用真机读数才发现。

**实机验证结果。**

| 项 | 结果 |
|---|---|
| 内核监听 | `127.0.0.1:31191` |
| h1 | example 200 / github 200 / baidu 200 / gitee 200 |
| h2 | `ALPN="h2"`、TLS1.3、`HTTP/2.0 200` |
| 连接复用 | 连续 4 次后 `pool: idle=1 keys=1` |
| 模式切换 | 安全模式下 github 超时（直连不可达），切回默认 200 |
| 流量明细页 | 汇总 + 按域名排行 + 复用计数 |
| GitHub 状态页 | 可达 5/6，各端点 275-870ms |
| 规则编辑 | 对话框显示合并后的 JSON，含恢复默认/保存 |
| 全量测试 | 7 包、**134 个用例**全绿 |

---


### 0.24 DNS 回退顺序：加密优先（2026-10-31）

用户问："为什么回退到真实 DNS 而不是自己的加密 DNS？"——查下来一半是我说错了，一半是真问题。

**说错的一半**：`LookupFallback` 并不是直接跳系统 DNS。顺序是 预置 IP → 配置里的其它提供方
（含 DoH/DoT）→ 最后才系统 DNS。桌面版 `getFallbackDnsList` 的注释写的就是同一个设计。

**真问题的一半**：提供方的顺序。`config.DNS.Providers` 是 Go 的 map，我按名字排序建 `order`，
于是 `aliyun`（223.5.5.5 明文 UDP）排在 `cf-DoH` / `cf-DoT` 前面。对被污染的域名，回退到明文
查询就可能拿到假地址——恰好是 dev-sidecar 要用加密 DNS 绕开的场景。桌面版按配置文件里的顺序
（local → aliyun → safe360 → cf-DoT → …），同样把明文排在前面，但这不构成我们照抄的理由。

**改法**：`providersBySecurity()` 把 DoT/DoH 提到明文之前，只在回退路径生效，主解析路径不变。

实测（真实配置）：`github.com` 的回退现在走 `bebasid`（`tls://dns.bebasid.com`，DoT），
日志 `dnsres: fallback resolved host=github.com provider=bebasid ips=1`；改动前第一条是 `aliyun`。

**代价**：DoT 每次 4 秒、DoH 每次 5 秒超时（`transport.go`）。如果用户网络里加密 DNS 全被墙，
回退会比原来多花几秒才落到明文。后续可以把加密提供方改成并发竞速，而不是串行试。

**设备侧**：这台新接入的设备（2210132C）拒绝安装（`INSTALL_FAILED_USER_RESTRICTED`，需要在
开发者选项里打开"USB 安装"），随后 adb 掉线，因此本次改动只做到 CLI 验证 + 单测，未在设备上复验。

---


### 0.25 批次 11：谷歌访问修复、DNS 竞速、导航栏、证书引导（2026-10-31）

用户问"为什么现在访问不了谷歌"。查下来是两个独立的解析缺陷叠加，都与网络无关。

**1. 负向前瞻的预置键永远不匹配**

官方配置对谷歌的可用地址写在键 `^(?!fonts).*(with)?google(apis|usercontent)?.com.*$` 下。
`hostmatch` 用 RE2 编译，遇到 `(?!` 直接失败，而失败后**静默判为不匹配**，于是这个键等于不存在，
请求退到别的（已失效的）地址。桌面版用同一个键拿到 `142.251.189.206`，0.6 秒就返回 302。

修法：把断言改写成「基础表达式 + 排除集」，与规则路径里的处理一致；`^(?!fonts)...` 的排除集就是 `^fonts`。

**2. 对象形式的预置条目被当成禁用**

同一个键的值是 `{"142.251.189.206":{"desc":"自动地区官方"},"8.137.102.117":false}`。
`entryEnabled` 把除 true/false/字符串外的值一律当禁用，对象（带描述）也落进去了，于是列表为空。
改成：对象视为启用，除非写了 `false` / `enabled:false` / `disabled:true`。

修完实测（真机 + CLI）：

| 目标 | 修前 | 修后 | 桌面 |
|---|---|---|---|
| www.google.com | 502 / 19.4s | 302 / 0.73s | 302 / 0.63s |
| www.google.com/generate_204 | 502 / 16.1s | **204** / 0.25s | 204 / 0.63s |
| accounts.google.com | — | 302 / 0.65s | — |

**3. DNS 并发竞速**

回退路径原来是串行试提供方，一个被墙的 DoT 就是 4 秒、DoH 5 秒。改成 `raceProviders`：
加密优先那一层**并发**发起，先回先赢，失败者取消。单测用一个黑洞地址（192.0.2.1）证明
"即时答案 10ms 返回，不陪慢提供方等满 8 秒"。

**4. 导航栏**

主界面顶部去掉内核地址/端口两个输入框（内核固定 127.0.0.1，端口在设置里），换成 `← → ⟳ ⌂ ⚙`。
真机验证：点 ⌂ 加载 github、前进后退与刷新按钮均响应。

**5. 证书安装引导**

CA 卡片新增：系统信任状态（比对 TrustManager 的 acceptedIssuers）、「安装证书」与「重新检测」。
`KeyChain.createInstallIntent()` 在 MIUI 上会被改成跳设置，而设置里的选择器看不到应用私有目录，
所以安装时**同时把证书导出到 `Download/dev-sidecar-ca.crt`**。真机确认该文件已生成（1172 字节）。

全量 7 包、**138 个用例**全绿；APK 已装到设备。

### 0.26 批次 12：主页可配置、撤掉证书安装（2026-10-31）

**撤掉证书安装。** 上一批加的"安装证书/导出到下载目录"整套移除。理由成立：内置浏览器的请求走应用内拦截，
用内核自带的信任链，不装系统证书也能正常访问；只有想让**其他应用**也走这个内核时才需要装。
CA 卡片保留证书信息（文件、SHA-256）并说明这一点。

**主页默认谷歌，且可配置。**

| 位置 | 行为 |
|---|---|
| 首次启动 | 打开主页（默认 `https://www.google.com/`） |
| ⌂ 按钮 | 打开当前设置的主页 |
| 设置 → 主页 | 输入框 + 保存，写 `ds-app` 偏好；只输入域名会自动补 `https://` |

启动时仍然优先恢复上次访问的页面，`--es url` 传参优先于两者。

**真机验证。**

| 项 | 结果 |
|---|---|
| 点 ⌂ | 打开 `https://www.google.com/` |
| 谷歌首页 | 搜索框、Trending searches、页脚完整渲染（不只是 204） |
| 改主页为 example.com | 保存后字段回显 `https://example.com`，⌂ 跳转到该地址 |
| 改回 www.google.com | 字段回显 `https://www.google.com/` |
| CA 卡片 | 只剩证书信息与说明，`安装证书` 按钮已不存在 |
| 导航栏 | `← → ⟳ ⌂ ⚙`，无内核地址输入框 |

---


### 0.27 批次 13：隐藏增强模式、删 CA 栏目、深色顶栏（2026-10-31）

**界面。**

| 改动 | 说明 |
|---|---|
| 隐藏增强模式 | 模式行只留「默认 / 安全」，说明文字改为「默认：按规则拦截与改写；安全：关闭规则匹配，用于排查是不是规则导致的问题」，不再提增强 |
| 删除 CA 栏目 | 设置页整块移除；随之删掉 `renderCa`、`caBox` 与安装/导出辅助函数 |
| 深色顶栏 | 主界面背景 `#0F1115`，导航按钮与 URL 输入框 `#1B1F26`，浅色文字；URL 不再和页面白底糊在一起 |

**修掉模式开关的一个副作用。** 原来切模式会顺带写 `server.dns.speedTest.enabled` 与
`plugin.overwall.enabled`（照抄桌面 applyMode）。增强模式现在不可达，这个覆盖就只剩坏处：
用户手写的 overwall 配置只要点一次模式按钮就被关掉。改为只写 `server.intercept.enabled`，
增强模式才额外打开 overwall。单测加了断言：非 ow 模式不得写入 overwall / speedTest。

**顺带修了一个测试脆弱点。** `TestSNIRuleUsesForSNIProvider` 用本地 UDP DNS + 5 秒客户端超时，
全量并行跑时偶发丢包就会超时失败（曾出现 5.10s 失败）。该测试断言的是路由而非时延，超时放宽到 20 秒。

**安全模式的真实语义（供说明用）。**

| 关掉的 | 仍然生效的 |
|---|---|
| SNI 改写、本地应答（abort/success）、重定向 | 预置 IP 列表 |
| 请求头/体改写、响应改写、缓存 304、脚本注入 | DNS 提供方配置 |
| 规则里的 proxy 改写 | overwall 中继（判定不看规则匹配） |

真机验证：安全模式下 `collector.github.com/github/collect` 从本地 204 变成上游 301，
github 仍 200（预置 IP 还在）；切回默认后恢复 `Ds-Interceptor: success`。

---


### 0.28 地址栏并入按钮行（2026-10-31）

原来地址栏单独占一整行，工具条区域浪费了一半高度。改为一行：`← → ⟳ ⌂ [ 地址栏 ] ⚙`，
地址栏 `layout_weight=1` 吃掉中间剩余宽度，网页区域因此多出约一行的高度。

实测：地址栏可输入并按回车跳转（example.com → 页面加载）；⌂ 打开设置里的主页（谷歌，页面
Trending 正常渲染）；设置里保存后回显 `https://www.google.com` 并有「已保存」提示。

顺带发现并修正一处我的测试遗留：主页曾被测试改成 example.com 且保存没点中，导致 ⌂ 去了
example.com。现已确认真实写入为谷歌。

---


### 0.29 删掉工具栏下的小字（2026-10-31）

地址栏下面那行「app 内拦截转发，无 VPN」整行删除（`statusView` 从未被更新过，是纯静态文案）。
工具栏现在紧接页面内容。

同时修掉主页输入框的对比度：`homeField` 之前漏设 `setTextColor`，深灰字压深底几乎不可读，
现改为 `#E6E8EB` + 提亮下划线。

验证截图时遇到一次谷歌「unusual traffic」验证页——是我用 adb 反复点 Trending 链接触发的，
清掉后 ⌂ 重新加载正常（页面无验证码），内核直连 `generate_204` 204、github 200。

---


### 0.30 谷歌「unusual traffic」排查（2026-10-31）

用户反馈点任意 trending searches 就会出现验证页。逐项排查：

| 假设 | 实验 | 结果 |
|---|---|---|
| URL 参数触发 | 原样重放 `sclient=mweb-insp`、`gs_lp`、`biw/bih/dpr` | 302/200，无验证页 |
| UA 的 `; wv` 标记 | 带/不带 wv 各一次 | 都正常 |
| Client Hints | 原样重放 `sec-ch-ua*`（含空 arch/bitness） | 都正常 |
| Cookie 回传丢失 | 内核 vs 桌面 cookie jar 对比 | 都是 4 条，一致 |
| 重复查询限流 | 连发 3 次相同 URL、带 `sei` 重放 | 全部 302 |
| 首页→搜索同会话 | curl 模拟（共享 jar） | 200 正常页 |

**WebView 侧的实际链路（logcat）**：搜索主框架 `200` → 页面 JS 再导航一次（URL 多 `&sei=`）→ 谷歌回 **429**，429 的响应体就是那张 "unusual traffic" 页。

出口是共享预置地址 `142.251.189.206`（官方配置注"自动地区官方"），本身不稳定：
TCP 探测 3 次失败 1 次；期间一度所有谷歌请求 502（`no usable address`）。

**尝试的缓解已回滚**：给主框架加"5 秒内相同查询复用上次响应"，结果页面进入导航循环
（10 秒内 52 次复用），验证码依旧 → 全部撤销，恢复原逻辑。

**结论（推断）**：谷歌对共享出口 IP 的风控命中了整页会话，单条请求能过关，状态随时间波动；
再次刷新同一 URL 时页面已正常加载（captcha=False）。

**副产物**：排查中卸载重装清掉了设备上的个人配置地址，已重新填入
`https://cute-omega.github.io/other-assets/ds-config.json` 并启用（设置页显示"当前已启用个人配置"）。

---


### 0.31 uTLS 试验：指纹不是原因（2026-10-31）

用户提议用 uTLS 把 TLS 指纹改成 Chrome。已实现并验证，**没有解决问题**，但结论有价值。

**改动。** 上游 TLS 由 `crypto/tls` 换成 `utls`（`HelloChrome_133` 预设）。Chrome 预设自带 ALPN `h2`，
而本内核上游只讲 HTTP/1.1，因此把预设里的 `ALPNExtension` 改写成 `http/1.1`——不改会让上游误判为 h2
（单测里表现为 `malformed HTTP response` + 服务端报 `bogus greeting GET /`）。
`verifyFor` 收 `tls.ConnectionState`，加了一个 `tlsStateOf` 适配 uTLS 的状态类型。

**指纹验证**（`tls.peet.ws/api/all` 经内核访问）：

```
ja4 = t13d1516h1_8daaf6152771_d8a2da3f94cd
```

`8daaf6152771` 是 Chrome 的扩展哈希，即指纹已经是 Chrome；`t13` 代表 TLS 1.3，`h1` 是钉住的 ALPN。

**结果：谷歌依旧重定向到 /sorry/index。** 说明触发点不在 TLS 层。

随后补了浏览器必带、而 `shouldInterceptRequest` 不提供的头（`Accept-Language`、主框架的
`Sec-Fetch-Site/Mode/Dest/User`），仍然被送到 /sorry。

/sorry 页自报 `IP 地址: 110.255.91.74`——与 PC 上 curl 成功时是同一个出口 IP，因此不是 IP 封禁，
而是谷歌页面脚本对 WebView 客户端的判定。

**保留的改动**：uTLS + 补充请求头（都不改变现有行为，只让上游看到的客户端更接近真实浏览器）。
全量 139 用例通过，APK 已装。

---


### 0.32 定位完成：是谷歌脚本判定 WebView（2026-10-31）

按"是 JS 判定还是我们的拦截弄坏了请求"做了两组对照。

**对照一：关掉 JavaScript。** 启动参数 `--ez nojs true`（新加的临时开关）后再搜：

```
200  google.com.hk/search?q=kotlin+coroutines
200  google.com.hk/httpservice/retry/enablejs      ← 谷歌的"请启用 JS"页
```

没有 `/sorry/`。开着 JS 才会有。

**对照二：统计一次 JS 搜索里我们这边有没有出错。** 加了 `unsupported method` 日志（非 GET/HEAD
会返回 501）并统计转发失败：

```
非 GET/HEAD 请求: 0 次
转发失败:        0 次
主框架: 200 搜索结果 → 429 /sorry/index
```

**结论**：页面所有资源都正常拿到了，我们的 501 路径一次没触发，转发零失败；谷歌页面脚本仍然
自己跳到 `/sorry/`。所以不是拦截器弄坏了子请求，是脚本判定这个 WebView 环境不可信。

网络层的变量此前已逐项排除（URL 参数、UA、Client Hints、Cookie、TLS 指纹已改为 Chrome JA4、
Sec-Fetch、重复请求、出口 IP 与 PC 相同）。

**保留**：`--ez nojs true` 调试开关（只在显式传入时生效）、uTLS、补充的浏览器请求头。

---


### 0.33 默认搜索改 DuckDuckGo、谷歌 429 兜底页（2026-10-31）

**默认主页**改为 `https://duckduckgo.com/`。实测经内核 200（主页与 `html.duckduckgo.com` 都通），
应用内搜索出结果、无验证页。Bing 也可用（`www.bing.com` 302 → `cn.bing.com` 200）。

**谷歌 429 兜底页。** 之前判断错了位置：`/sorry` 不是独立导航，谷歌是**直接拿 429 + 拦截页正文**
回应搜索请求本身（地址栏停在搜索 URL）。因此两处都接管：

| 触发 | 处理 |
|---|---|
| 主框架 `/sorry` 导航 | 换成提示页 |
| 主框架搜索返回 429（google.com） | 换成提示页（实测生效） |

提示页显示关键词，并给 DuckDuckGo / Bing / 百度 三个同词搜索链接。实测点 "用 DuckDuckGo 搜这个"
直接进 DDG 结果页。

**顺带修**：地址栏只在自己加载时更新，页面内跳转不跟随，导致显示的还是旧 URL。给 WebViewClient
加了 `doUpdateVisitedHistory` 回调，输入框有焦点时不打断用户输入。

**结论（回答"为什么"）**：谷歌在防自动化流量，嵌入式 WebView 长期是爬虫与去广告客户端的载体，
所以它把这套环境默认判为可疑，且是长期策略（它同样禁止 WebView 内做 OAuth）。强行阻止跳转救不回
结果——脚本发跳转时已经决定不展示了。

---


### 0.34 撤掉 /sorry 接管（2026-10-31）

用户决定不再拦截谷歌的拦截页——拦下来也拿不到结果，只是换一种失败方式。

删除：
- `shouldInterceptRequest` 里的 `googleAbusePage(...)` 调用
- 主框架 429 替换为提示页的分支
- `googleAbusePage` / `friendlyAbusePage` / `sorryQuery` 三个方法

保留：DuckDuckGo 默认主页、地址栏跟随页面内跳转。

真机确认：谷歌搜索显示它自己的拦截页（无我们的提示页），地址栏正确显示带 `&sei=` 的实际 URL。

---


### 0.35 换成桌面版图标（2026-10-31）

桌面版图标在 `D:\JavaScript\dev-sidecar\packages\gui\dist\icon-1.png`（1083×1083）。取它作源，
裁掉透明边后用 `build-icon.py`（Pillow）生成五档密度：

| 密度 | 尺寸 |
|---|---|
| mdpi | 48 |
| hdpi | 72 |
| xhdpi | 96 |
| xxhdpi | 144 |
| xxxhdpi | 192 |

工程此前**没有 `res/` 目录**，aapt2 也没带资源参数，所以图标一直是系统默认。补齐两处：

- `build.ps1`：先 `aapt2 compile --dir res -o res.zip`，再把 `res.zip` 作为输入传给 `aapt2 link`
  （`link` 不接受 `-S`，资源必须先编译）。
- `AndroidManifest.xml`：`android:icon="@mipmap/ic_launcher"`、`android:roundIcon` 同值。

**验证**（`aapt2 dump badging`）：

```
application-icon-160..640: res/mipmap-*/ic_launcher.png
application: label='ds-core' icon='res/mipmap-mdpi-v4/ic_launcher.png'
```

从 APK 里抽出的 192×192 PNG 就是桌面版那个蓝色分子标志。图标保留原图的透明背景（与桌面一致）。

---


### 0.36 改名 DS NEXT / org.blueFrontier.devSideCar.next.android（2026-10-31）

**改动**：包名 `org.BlueFrontier.DevSidecar` → `org.blueFrontier.devSideCar.next.android`，
应用名 `ds-core` → **DS NEXT**。源码目录同步改为 `src/org/blueFrontier/devSideCar/next/android/`。
Action 常量、`pm` 可见的包名、启动命令一并更新。

验证：`aapt2 dump badging` 输出
`package: name='org.blueFrontier.devSideCar.next.android'`、`application: label='DS NEXT'`；
真机安装后内核监听 31191，DuckDuckGo 与 GitHub 均 200，旧包已卸载。

**过程中的一次事故（必须记录）**：搬迁文件时把新目录写成了
`src\org\blueFrontier\devSideCar\next\android`，而 Windows 路径大小写不敏感，
`blueFrontier\devSideCar` 被解析为已存在的 `BlueFrontier\DevSidecar`，于是新文件落在了旧目录的
**子目录**里；随后的 `Remove-Item 旧目录 -Recurse` 把两者一起删掉了。git 里只有 4 个文件有历史，
另外 8 个从未提交。

**恢复方式**：用 14:52 那次编译产物 `build/classes`（含全部改动）经 CFR 反编译回 Java，
再逐文件重写为 Kotlin（12 个文件）。行为等价，注释与代码组织按反编译结果重建，不是原始文本。
`.recover/src` 保留这份反编译结果作为对照。

教训：大小写不敏感的文件系统上做包名迁移，不要"先写到新路径再删旧路径"；要么先删后写，
要么用 `git mv` 让版本控制承担这次操作。

---


### 0.37 APK 存档机制 + 代理服务改名（2026-10-31）

**为什么旧 APK 没存档。** `build\` 一直被当作可丢弃的产物目录，每次构建原地覆盖；设备上那份又随
卸载一起消失。事故发生时没有任何一份旧二进制留存，这是流程缺口，不是运气问题。

**补上的机制。** `build.ps1` 在签名后新增第 8 步：把产物复制到 `dist\ds-next-<yyyyMMdd-HHmmss>.apk`，
把时间戳与 SHA-256 追加到 `dist\SHA256SUMS.txt`，并只保留最近 20 份。任一时刻都能回到某个具体构建。

首个存档：`dist\ds-next-20261031-164443.apk`
`232F6FFD818628DE95A6C384C59A26754E5CD553F582798130C7D5918A9851B0`

**改名。** `ProxyVpnService` 的用户可见字符串不再是 "ds-core"：
会话名 `DS NEXT`、通知渠道 id `ds-next-proxy`、渠道名与通知标题 `DS NEXT 代理发布`。
理由：这个服务做的是"发布一个 HTTP 代理"（VpnService 只是载体，默认不加路由），既不是 ds-core 也不是 VPN。

验证：class 中 `ds-core` 命中 0 处；安装存档包后内核 github/duckduckgo 均 200。

---


### 0.38 删掉 ProxyVpnService（2026-10-31）

plan A 的遗留物：这个服务没有任何入口（全仓无 `ProxyVpnService.start` 调用），当前架构是
`shouldInterceptRequest` → `HttpURLConnection(Proxy)`，根本不经过 VPN。

删除内容：

| 位置 | 处理 |
|---|---|
| `ProxyVpnService.kt`（约 220 行） | 整个文件删除 |
| manifest `<service .ProxyVpnService>`（含 `BIND_VPN_SERVICE`、`VpnService` intent-filter） | 移除 |
| `ACCESS_NETWORK_STATE` 权限 | 移除（唯一使用者就是被删的 ConnectivityManager 绑定逻辑） |
| 通知渠道 `ds-next-proxy`、「DS NEXT 代理发布」 | 随文件一起消失 |

保留：`KernelService`（前台通知 + WakeLock）、`WAKE_LOCK`、`FOREGROUND_SERVICE(_SPECIAL_USE)`。

验证：class 中 `VpnService` 命中 0 处；manifest 无 VpnService 节点；安装后内核 github/duckduckgo 均 200。

---


### 0.39 残留彻查（2026-10-31）

按"有没有人用"逐个核对，不靠印象。方法：统计每个顶层声明、KernelRuntime 每个方法、每个常量
在全仓库的出现次数，只保留定义处那种即为死代码。

**删掉。**

| 对象 | 证据 |
|---|---|
| `KernelCaWebViewClient` 类 | 全仓仅自身文件出现；实际用的是 `ProxyFetchWebViewClient`（plan A 遗留） |
| `KernelRuntime.stats()` | 外部引用 0（UI 用的是 `traffic()`） |
| `KernelRuntime.userConfigPath()` | 外部与内部引用均为 0 |
| `ProxyFetchWebViewClient.KERNEL_CA_ISSUER` 副本 | 0 处读取，真正的常量在 KernelTrust.kt |
| 帮助页「设置 → CA 证书」段落 | 该卡片已在 0.26 删除，文案悬空 |
| 帮助页「三种模式」含增强 | 增强按钮已隐藏，实际只有两种 |

文件 `KernelCaWebViewClient.kt` 改名为 `KernelTrust.kt`，只保留仍在用的 `KERNEL_CA_ISSUER` 与 `KernelTrust`。
`src` 由 12 个文件降为 11 个。

**品牌残留。** 通知标题 `ds-android`、渠道名 `ds-android kernel`、WakeLock 标签 `dscore:kernel`
改为 `DS NEXT` / `DS NEXT 内核` / `ds-next:kernel`。

**有意保留（附理由）。**

- `modeLabel` 的 `"ow" -> "增强"`：配置里若残留 ow 模式，界面要能显示真实状态，虽然 UI 已不提供该选项。
- SharedPreferences 文件名 `ds-app` / `ds-kernel`：内部标识，改名会丢掉用户已保存的主页与端口。
- 日志 tag `ds-fetch` / `ds-kernel`、通知渠道 id `ds-kernel`：不可见标识。
- `smoke/`：工具链冒烟模板，BUILDING.md 有引用，不属于应用代码；要删需同步改文档。
- `.recover/`：事故前代码的唯一对照物，留作审计。

**清单项复核。** manifest 6 个 activity 全部可到达、1 个 service、4 个权限都有使用者；
assets 三项（CA 证书/私钥、官方配置）均被 KernelRuntime 读取；res 只有 ic_launcher 五档，manifest 引用。

验证：class 中 `VpnService`、`KernelCaWebViewClient` 均 0 命中；安装后内核 github 200。

---


### 0.40 增强模式保留入口隐藏 + 内核改 submodule + 提交（2026-10-31）

**增强模式（ow）**：功能完好，只是没有可见入口。

- 内核侧：`SetMode("ow")` 写入 `app.mode=ow`、`plugin.overwall.enabled=true`、`server.intercept.enabled=true`；
  `CurrentMode` 返回 `ow`；`TestOverwallRoutesThroughRelay` 等三个测试通过。
- 入口：模式行只有「默认 / 安全」。MainActivity 是唯一 exported 的 activity，隐藏开关放在它上面：
  `adb shell am start -n org.blueFrontier.devSideCar.next.android/.MainActivity --es mode ow`
  真机验证显示「当前模式：增强」，模式行仍无「增强」按钮。
  （放 KernelSettingsActivity 会被系统拒绝：`not exported from uid`。）

**内核 submodule**：`kernel/` 指向 `https://github.com/Blue-Frontier/ds-core-go.git`，当前指针 `b7c8682`。
脚本解析顺序：`kernel/` → `$env:DS_KERNEL_DIR` → `D:\dev-sidecar-core-go`（兼容旧环境），
`build.ps1` 与 `sync-kernel.ps1` 一致。全链路（gomobile → sync → build → 安装）已用 submodule 源码跑通。

顺带修了 `build-kernel-aar.ps1`：全新 clone 没有 `build\`，gomobile 不会自建父目录，直接报
`open build\kernel.aar: The system cannot find the path specified`。现在先 `New-Item -Directory build`。

**提交**：内核 `b7b48f3`（+ `b7c8682` 修 AAR 脚本，已推送）；android `a568606`（本地提交，未推送）。

---

