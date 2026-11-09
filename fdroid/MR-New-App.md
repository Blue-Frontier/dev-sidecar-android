# New app: DS NEXT

**Name:** DS NEXT
**Package:** `org.blueFrontier.devSideCar.next.android`
**License:** MPL-2.0
**Source:** https://github.com/Blue-Frontier/dev-sidecar-android
**Version:** 0.5.0 (versionCode 500)

## What it is

A browser for networks where sites are blocked by poisoning their names or by
resetting the connection. Everything the browser loads goes through a proxy
kernel that runs inside the app, applying a ruleset published by the dev-sidecar
project: name resolution overrides, per-host connection rules, pre-set addresses,
encrypted DNS and TLS Encrypted Client Hello where the network allows it.

Only pages opened in DS NEXT go through the kernel. There is no VPN, no
system-wide proxy, and no certificate installed outside the app.

## Why there is no Gradle

The build is a shell chain: `aapt2 → kotlinc → d8 → jar → zipalign → apksigner`,
driven by `build.sh`. It started as a way to keep the toolchain visible and
pinned, and it stayed because reproducibility is easier to argue when every
input is named in one file instead of being produced by a plugin's defaults.

`fdroidserver` only needs commands, so this is:

```yaml
build: |
  ./deps/fetch-deps.sh
  ( cd kernel && ./build-kernel-aar.sh android/arm64 )
  ./sync-kernel.sh
  ./build.sh --unsigned
output: build/dscore-unsigned.apk
```

The kernel is a Go module in the `kernel/` submodule; `build-kernel-aar.sh` runs
`gomobile bind`, which needs the NDK (pinned to 29.0.14206865) and a JDK on
`PATH`.

## What the build needs, and where it comes from

| Input | Source |
| --- | --- |
| Kotlin 2.4.21 | downloaded in `prebuild` from JetBrains, SHA-256 `7cc140e76daf416a0424a5557f99afd7ca89ebd463ea1101261e3e01c33e75cd` |
| androidx.webkit 1.12.1 | checked in as a 170 KB AAR under `deps/vendor/` |
| Go modules (utls, x/net, x/crypto, …) | `kernel/goproxy`, a `file://` module proxy in the tree (65 MB) |
| build-tools 37.0.0, platform 35, NDK 29.0.14206865 | Android SDK from the image |
| Go 1.27.3, gomobile + gobind | installed from `kernel/goproxy`, no network |

The Go proxy is the largest thing in the tree. It is there because the kernel
uses `github.com/refraction-networking/utls`, whose `zstd` import alone is a
37 MB module; a `vendor/` directory is not an option because it flips Go into
`-mod=vendor` and gomobile can then no longer find its own package.

## Reproducible builds

The recipe uses `Binaries` with `AllowedAPKSigningKeys`
(`13e142299f4e42ebca1d034a339e3b789ee379a618abcf186c9b49431bcb6108`), verified
against the published 0.5.0 APK.

What was done to make a rebuild match:

- zip entry mtimes pinned to a fixed instant; `jar` runs with
  `-Duser.timezone=UTC` (the JVM ignores `TZ` on Windows)
- `.gitattributes` forces LF, so `assets/` bytes do not depend on the checkout
- signing is v2/v3 only, so no `META-INF` entries carry a build timestamp
- Go is built with `-trimpath -buildvcs=false -ldflags="-s -w"`; without these
  `libgojni.so` embeds the checkout path, the module cache and the NDK path
- the same tool versions are pinned in the recipe and in CI

CI rebuilds twice from the same commit and fails if the two APKs differ, so
nondeterminism is caught before a tag is cut. Measured result: the same commit
built twice on ubuntu-latest gives identical bytes
(`bffa3733f2af0ca8f0751cdfc3a302b61a0797cbb06e9cc4c0e6622f65fb703d`).

One known difference: a Windows build and a Linux build of `libgojni.so` differ
by a few dozen bytes (ELF section layout, the same platform effect the F-Droid
docs describe for NDK output). Releases are therefore built on Linux only.

## Antifeatures

None declared.

- No advertising, no analytics, no crash reporting, no account.
- The rule bundle ships inside the APK and works offline. Updating it from the
  project's server is optional and the format is public, so it is not
  `NonFreeNet`.
- The only third-party jar is androidx.webkit (Apache-2.0).

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE` — a browser whose proxy runs in-process
- `CAMERA`, `RECORD_AUDIO` — WebRTC pages (video calls) inside the browser;
  media never leaves the WebView
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`,
  `WAKE_LOCK` — keeps the kernel alive while the screen is off, and shows a
  notification while it runs

## Why the first submitted version is 0.5.0

The 0.4.0 release was signed with a keystore that had been committed to the
repository. That key is public, so anyone could sign an "update" that Android
would install over the real app. 0.5.0 is the first release signed with a
private key in a protected CI environment; it is the first build whose
signature can meaningfully be listed in `AllowedAPKSigningKeys`. Users moving
from 0.4.0 or earlier have to reinstall once.

## Testing

Built and run on a Xiaomi 2210132C (Android 16, arm64) and on the CI runner for
x86_64. Browsing, downloads, camera/WebRTC and the notification channel were
checked on the device; the build itself is exercised on every push.
