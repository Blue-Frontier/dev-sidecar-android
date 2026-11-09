# Security Policy

## Supported versions

Only the latest stable release is supported. Once the latest stable version is
published, all older builds are no longer supported immediately. Pre-release and
local development builds are unsupported.

**0.4.0 and earlier are not only unsupported but unsafe to upgrade from.** Those
releases were signed with a keystore that had been committed to the repository,
so the signing key is public: anyone could produce an "update" that Android would
install over the real app. 0.5.0 is the first release signed with a private key
in a protected CI environment. If you are on 0.4.0 or older, uninstall and
reinstall — do not upgrade in place.

## Reporting a vulnerability

Please do **not** open a public issue for security problems.

Two channels are accepted:

1. **GitHub private vulnerability reporting** on this repository
   (Security -> Advisories -> Report a vulnerability). This is preferred.
2. **PGP-encrypted email** to **cuteomega@foxmail.com**. Plain-text email reports are
   not accepted; encrypt the report with the public key below.

The public key fingerprint is:

```
6C04 3D82 B6A3 5CC8 9C79  7773 47E3 F360 491D F7F9
```

Please include:

- DS NEXT version (Settings shows it) and Android version, plus device model if
  the bug is device-specific;
- steps to reproduce, or a minimal PoC;
- affected component (Go kernel, CA/certificate handling, DNS/SNI rules, WebView
  trust bridge, downloader, build and release pipeline);
- logs if relevant, with cookies, tokens and personal data removed;
- impact assessment if you have one.

## Scope

In scope:

- the Go kernel under `kernel/` — the MITM proxy itself, request routing, the
  CONNECT path, and the HTTP/2 handling;
- certificate generation and CA handling (`kernel/certcache`), including leaf
  minting, lifetimes and the on-disk CA pair;
- DNS resolution, SNI rules, preset addresses and configuration merging;
- the WebView trust bridge (`KernelTrust`, `onReceivedSslError`): anything that
  makes the app accept a certificate the kernel did not mint, or keep accepting
  one after the kernel stopped;
- the downloader (`Downloads.kt`), including its proxy and TLS setup;
- intent handling and the activity's exported surface;
- the build and release pipeline: the tag/version guard, the signing step and its
  environment protection, the reproducibility gate, and anything that would let a
  build other than a tagged one be signed.

Out of scope:

- vulnerabilities in third-party dependencies (report them upstream, but tell us
  if DS NEXT needs a version bump);
- the user installing the app's CA into the system store — the app never asks for
  this and does not need it;
- attacks that require the device to be rooted, or that only work against a
  modified APK;
- a site being unreachable. Censorship behaviour is a functional bug, not a
  vulnerability.

## Trust model, so you know what counts

The kernel mints a CA on the device at first start and signs the sites it
intercepts with it. That CA never leaves the device, is generated per install,
and is not installed into the system trust store. Only the app uses it.

Because the CA is dynamic, the WebView cannot know it in advance, so
`onReceivedSslError` decides per connection: a certificate is accepted when it
verifies against this device's CA, or — when the WebView does not hand the
certificate over at all — when the in-process kernel is running. That second
branch is the interesting one from a review standpoint: while the app's proxy
override is applied, the WebView has no route to anything but the kernel, so no
other party can be presenting that TLS session. If you can reach a state where
the app accepts a certificate under weaker conditions than that, it is a finding.

A compromise of the CA means an attacker can read everything the browser loads.
Treat CA handling, and anything that writes to `files/ca`, as the highest-value
target.

## Response

There is no fixed SLA and no guaranteed fix deadline. We will acknowledge a report
as soon as we can, share our assessment, and schedule a fix based on severity and
the release plan. There is no bug bounty, but we are happy to credit reporters in
the release notes unless you prefer to stay anonymous.

For releases you can verify independently: the build is reproducible, CI rebuilds
every tag twice and compares hashes, and the F-Droid recipe pins the same
toolchain and records the signing certificate in `AllowedAPKSigningKeys`.

## PGP public key

```
-----BEGIN PGP PUBLIC KEY BLOCK-----

  mDMEatv48xYJKwYBBAHaRw8BAQdAc1VD4WyhnlKwgvzVsghPZfvs/NVOZfJtLiSy
  So9CN1a0ImN1dGUtb21lZ2EgPGN1dGVvbWVnYUBmb3htYWlsLmNvbT6IrwQTFgoA
  VxYhBGwEPYK2o1zInHl3c0fj82BJHff5BQJq2/jzGxSAAAAAAAQADm1hbnUyLDIu
  NSsxLjEyLDIsMQIbAwULCQgHAgIiAgYVCgkICwIEFgIDAQIeBwIXgAAKCRBH4/Ng
  SR33+e4DAQCU4sdbhcbbHpkIqze9PhvJvaIxiUB+T/3+SuyRcYEFsQEApH3YKJim
  1QFoTv34JrLEMHSMKnFZz0epnxUy3TGxMAS4OARq2/jzEgorBgEEAZdVAQUBAQdA
  J46Jku2z1LSeI73O7PG/7gPe5TLHKDTovjL7+AJXyEkDAQgHiJQEGBYKADwWIQRs
  BD2CtqNcyJx5d3NH4/NgSR33+QUCatv48xsUgAAAAAAEAA5tYW51MiwyLjUrMS4x
  MiwyLDECGwwACgkQR+PzYEkd9/lktwEAj/i1e0MAMiQ/3Xjq1cy2RhBdZ7Dzugr7
  GTzMPxwmh3sBAKjgCrQC3tel8wP1hmiheioGoX/Lv55hD2+khk1ET/YP
  =utib
  -----END PGP PUBLIC KEY BLOCK-----
```
