# quicprobe

同一条网络上，对比一个站点**能不能用 TCP+TLS 连上**和**UDP 443 有没有回应**。

做的是这件事：向 443/UDP 发一个 QUIC 长包头，里面写一个没人实现的版本号。QUIC 服务端会回一个 Version Negotiation 包——不需要任何加密;而如果网络把 UDP 443 丢了，就什么都收不到。所以它能把「UDP 被封」和「这个站点不支持 QUIC」区分开。

## 为什么需要它

一条网络的封锁策略可能只针对 TCP+TLS（按 SNI 重置），而 UDP 443 仍通。这种情况下把代理的**上游**换成 QUIC 才有效——浏览器侧的 HTTP3 帮不上忙，因为 WebView 一旦配置了代理就会禁用 QUIC。

判断值不值得做上游 QUIC，先跑这个探针。

## 用法

地址要自己传进去：Android 上的 Go 二进制没有 `/etc/resolv.conf`，用不了设备自己的解析器。

```bash
go build -o quicprobe .
./quicprobe x.com=203.0.113.10 example.com=203.0.113.11
```

输出：

```
host                         tcp+tls                        udp443 (quic)
x.com                        FAIL  5344ms tls: read tcp ...  ok   324ms version negotiation
example.com                  ok     688ms ok                 ok   287ms version negotiation
```

## 在手机上跑

```bash
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -o quicprobe-arm64 .
adb push quicprobe-arm64 /data/local/tmp/quicprobe && adb shell chmod 755 /data/local/tmp/quicprobe

# 先用设备自己的解析器拿到 IP，再交给探针
adb shell 'for h in x.com example.com; do \
  ip=$(ping -c 1 -W 2 "$h" | head -1 | sed -n "s/^PING [^(]*(\([0-9.]*\)).*/\1/p"); \
  [ -n "$ip" ] && echo "$h=$ip"; done' \
  | tr '\n' ' ' | xargs -I{} adb shell "/data/local/tmp/quicprobe {}"
```

## 读结果

* **TCP ok、UDP ok**：两条路都通，不需要 QUIC。
* **TCP FAIL、UDP ok**：这正是上游 QUIC 有用的场景。
* **两条都 FAIL**：这个网络对该站点的封锁不分协议。
* **UDP 偶发超时**：探针只发一个包、不重传。真实的 QUIC 有重传，所以偶发超时不算判死刑，多跑几次看比例。
