# DevSidecar 安卓版（Go 内核）· DSH 会话迁移

> ⚠️ **端口已变更（2026-10-28）**：本项目一律使用 **31191**，不得再绑定 31181
> （31181 属桌面 dev-sidecar）。本文旧命令里的 `31181` 请按 `31191` 理解，
> `forward tcp:31181` 一律换成 `43181` 等空闲端口。当前进度以 `PROGRESS.md` 为准。
>
> ⚠️ **已合并为单 app（2026-10-29）**：内核并入 `org.BlueFrontier.DevSidecar`，
> 伴生 app 与 `kernel-app/` 已删除；本文中启停 `DsCoreGo` 的步骤作废，见 `PROGRESS.md` §0.7。

> 本文件把 OpenCode 会话 `ses_e9089738`（项目 `D:/dev-sidecar-android`）的上下文迁给 DSH 会话直接接手。
> 它是 `SESSION-SUMMARY.md`（OpenCode 写于凌晨、到"手机截图确认"为止）的**续篇**：本文覆盖凌晨之后到 12:26 会话死亡的全部内容，并修正一处环境坑。
> 生成于 2026-10-25 12:55 (+08)，git/DB 状态均经实查。先读 §0/§1，再按 §3 优先级接手。

---

## 0. 一句话现状

- **手机本地内核已跑通并提速**：伴生 app `org.BlueFrontier.DsCoreGo` 里 gomobile 编出的 `libgojni.so` 在手机上听 `127.0.0.1:31181`（镜像桌面端口）；baidu/github 实测 200、子秒级，winner 稳定 `20.27.177.113` 且被 DNS.Note 顶到队首。502 已修、首连已提速（并行 race dial）。
- **唯一未解的活 bug：主 app 的 WebView 白屏**——内核侧所有 github 资源都 200（160+ 子资源，github.githubassets.com 在 11:52:55–11:53:18 全量流出），但页面渲染白屏、输入法还停在 URL 输入框。OpenCode 正要去读 `ProxyFetchWebViewClient.kt` / 开 WebView 远程调试时被 429 打断。
- **PC 侧网络劫持已解除**：OpenCode 的 `adb forward tcp:31181` 顶掉桌面 dev-sidecar 回环口导致"浏览器代理失效"，已被 `adb kill-server` 清掉；桌面 DS 已重启、服务正常。**这条是本次迁移最重要的环境教训，见 §4，务必遵守。**

---

## 1. 三个组件（不变，详见 SESSION-SUMMARY.md §2/§6/§9）

| 组件 | 路径 | 包名 | 产物 |
|---|---|---|---|
| 主 app（WebView + 显式代理转发，方案 B） | `D:\dev-sidecar-android\` | `org.BlueFrontier.DevSidecar` | `build\dscore.apk` |
| 伴生内核 app（跑 Go 内核） | `D:\dev-sidecar-android\kernel-app\` | `org.BlueFrontier.DsCoreGo` | `build\ds-kernel.apk` |
| Go 内核（桌面 CLI + 可嵌入 mobile 库） | `D:\dev-sidecar-core-go\` | — | 桌面 `cmd\ds-core`；手机 `mobile/`→`libgojni.so` |

- 手机：Xiaomi `25053RP5CC`（"violin"，HyperOS/Android 16，**未 root**），USB serial `0789B48009A40540`，mDNS serial `adb-0789B48009A40540-aL18tj._adb-tls-connect._tcp`，Wi-Fi `192.168.31.113`（adb 端口 34019→37333），PC `192.168.31.73`。
- 无 Gradle：aapt2→kotlinc→d8×3→jar uf→zipalign→apksigner；**两个 app 共用 `D:\dev-sidecar-android\debug.keystore`**（换 key 需先 uninstall 旧包）。
- 工具链在 `C:\Users\Nobody\android-toolchain\`（env.ps1 + NDK 29.0.14206865 + SDK）；go 模块代理 `GOPROXY=https://goproxy.cn,direct`。

---

## 2. 已提交 vs 未提交（git 实查，12:50）

**`D:\dev-sidecar-core-go`（ahead 5，全部已 commit）**：

| commit | 内容 |
|---|---|
| `f73c3f7` | dnsres：移植桌面内核 DNS 子系统（DoH/DoT/TCP/UDP + SNI 改写） |
| `d3512d6` | 未映射域名直走系统 DNS |
| `69577e4` | 上游 dial 超时 10s→5s（被墙 IP 快速失败） |
| `f87dac1` | 并行 race dial（候选 IP 分批并发，先到先用）= 首连提速 |
| `3a8073f` | **502 修复**：上游响应头等待加 10s 上限（SetReadDeadline，到表后 body 流式不限时）；超时地址经 DNS.Note 降权后同请求 redial，二次失败才吐 502。`go test ./proxy/ ok` |

**`D:\dev-sidecar-android`（ahead 2，两个 commit 已推；工作区有未提交改动）**：
- `27d39e6` WebView MITM via in-app interception（方案 B，无 VPN）
- `2d97161` 包名重命名：kernel=org.BlueFrontier.DsCoreGo，browser=org.BlueFrontier.DevSidecar
- **未提交**：
  - `src/org/BlueFrontier/DevSidecar/MainActivity.kt`（+2 行：`setRawInputType(TYPE_CLASS_TEXT|URI)` + `imeOptions=IME_ACTION_GO`，URL 输入框让回车可触发 loadUrl——是白屏排查的一部分）
  - `kernel-app/libs/libgojni.so`（为新内核重编的二进制，随源码一起提交）
  - 接手时先 `git status`/`git diff` 确认，别把 .so 当源码乱改。

---

## 3. 未解线程（按优先级接手）

1. **【最高】主 app WebView 白屏**。内核资源全 200、`ds-fetch` 无日志、IME 停在输入框。OpenCode 被打断前正要做：读 `ProxyFetchWebViewClient.kt` 的响应构建（Content-Type / body / Set-Cookie 回传是否正确）+ 开 WebView 远程调试（`setWebContentsDebuggingEnabled` / chrome://inspect）定位白屏。**疑点**：新内核 `serveStream` 的 keep-alive/`resp.Write` 改动可能让 WebView 解析出错，或 app 拦截层组装响应有问题。
2. **【未确认】MIUI 省电冻结静置复测**。现象：内核进程活着但 accept 死循环（监听队列 Recv-Q=3、不出包、nc 全挂），重启内核恢复。用户已把 ds-kernel 设"无限制"，OpenCode 挂了个后台 nc 复测任务，**结果从未确认**——先查那个任务/重新静置 11min 后发请求是否立即 200。
3. **【未做】第 3 层加速（脚本注入 / 响应体改写）**。这是"真·高速下载"的来源（JS 内核有 `tampermonkeyScript`+`GithubEnhanced`，把资源/下载链接改写到境内快线）；Go 内核没有。打包内核只解决自包含、不解决快。
4. **【未做】sni 语义对齐 JS 内核**。已知 bug：`sni: baidu.com` 套在 GitHub 真实 CDN IP 上时，多租户 CDN 回别家子域证书 → `x509: certificate is valid for *.actions.githubusercontent.com, not collector.github.com`。JS 内核里 sni 是配合"境内镜像 DNS/预设 IP"用的，Go 内核目前 DNS 解到真实 IP，sni 单用会崩。
5. **【已知限制】POST body 拿不到**。`shouldInterceptRequest` 收不到请求体，登录类 POST 直接返回 501；深度方案是 netstack VPN 模式（暂不做）。

---

## 4. 环境大坑：`adb forward` 别用 31181（本次事故根因）

- OpenCode 反复执行 `adb -s <serial> forward tcp:31181 tcp:31181`，把"PC 127.0.0.1:31181 → 手机 31181"当 PC 直连手机内核的压测通道。
- **`adb forward` 的 PC 侧 = adb 服务在 127.0.0.1:31181 起监听**。而桌面 dev-sidecar 的 mitmproxy 绑 `0.0.0.0:31181`、系统代理填 `127.0.0.1:31181`。Windows 允许通配+回环监听共存，但**回环流量优先路由到更具体的 127.0.0.1:31181（adb）**→ 浏览器/QQ/DSH 全部被 adb 吞掉、全超时。这就是"代理没对浏览器生效"，与 2.3.3 版本无关。
- OpenCode 已自认（DB seq 4588："这是我的疏忽——建 forward 时没检查 PC 本机 31181 被 ds-core 占着"），但清理命令 `adb forward --remove-all` 撞 429 没跑完。
- **接手规则（硬性）**：桌面 dev-sidecar 运行期间，**PC 侧 forward 端口永远避开 31181**。要 PC 直连手机内核压测：
  ```bash
  adb -s <serial> forward tcp:43181 tcp:31181        # 用任意空闲 PC 端口（示例 43181）
  curl --proxy http://127.0.0.1:43181 --cacert D:/dev-sidecar-android/kernel-app/assets/ca_ca.crt https://github.com/
  adb -s <serial> forward --remove-all               # 用完立即撤
  ```
  手机侧继续听 31181（镜像桌面端口）没问题；**只有 PC 侧 forward 的本地端口不能是 31181**。

---

## 5. OpenCode 会话/额度备注（别让 DSH 重蹈）

- OpenCode 会话 `ses_e9089738`（model `glm-5.3-flash-free`）累计 **input 922,454 / output 124,824 token**，把日免费额度烧穿；用户质问 adb 事件时回 `429 Free usage limit reached`，回复被截断、没产出摘要——这就是"连会话摘要都拿不出来"。
- 全量会话在 SQLite `C:\Users\Nobody\.local\share\opencode\opencode.db`（表 `session_v2` / `session_message` / `part`，其中 `type=compaction` 行带自动摘要）。只读查询脚本已备好：`node D:\JavaScript\dev-sidecar\.probe-opencode.cjs`（按需改 SELECT）。
- 项目自维护的迁移文档：`D:\dev-sidecar-android\SESSION-SUMMARY.md`（§7 验证记录、§8 下一步、§9 快速恢复、§10 坑清单）。
- 建议 DSH 接手时：分会话 / 控 token，别让单会话再堆到 ~1M token；关键进展随手写回 `SESSION-SUMMARY.md`。

---

## 6. 快速恢复 / 常用命令（SESSION-SUMMARY.md §9 + §4 修正版）

```powershell
# 手机内核状态（forward 用 43181，别用 31181）
$S='adb-0789B48009A40540-aL18tj._adb-tls-connect._tcp'
adb -s $S shell 'ss -ltn | grep 31181'
adb -s $S shell 'run-as org.BlueFrontier.DsCoreGo tail -8 files/kernel.log'   # 日志时间戳是 UTC，慢 8h
# 重启伴生内核
adb -s $S shell am force-stop org.BlueFrontier.DsCoreGo
adb -s $S shell am start -n org.BlueFrontier.DsCoreGo/.MainActivity
# 主 app 指向本地内核并测
adb -s $S shell am force-stop org.BlueFrontier.DevSidecar
adb -s $S shell am start -n org.BlueFrontier.DevSidecar/.MainActivity --es host 127.0.0.1 --es port 31181
# PC 侧压测（43181！）
adb -s $S forward tcp:43181 tcp:31181
curl -s --proxy http://127.0.0.1:43181 --cacert D:/dev-sidecar-android/kernel-app/assets/ca_ca.crt https://github.com/ | head
adb -s $S forward --remove-all
```

- 重建 .so：`D:\dev-sidecar-core-go\build.ps1`（gomobile bind，需 env.ps1 + NDK + ANDROID_HOME，走 goproxy.cn）。
- 装包：`adb install -r`（小米需手机端允许 USB 安装，可能弹小米账号确认）。

---

## 7. 桌面侧（2.3.3）一句话

桌面 dev-sidecar 2.3.3 的 draft release（35 资产 + 完整公告）已备好未发布，端口现由主进程持有、服务正常；细节另见 `D:\JavaScript\dev-sidecar\context-migration-2.3.3.md`，**与安卓版任务无关，别混在一起改**。
