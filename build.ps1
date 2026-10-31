# No-Gradle build for the dscore app: aapt2 -> kotlinc -> d8 (x2: app + stdlib)
# -> jar uf -> zipalign -> apksigner.
#
# Usage:  & build.ps1
#         The local toolchain env.ps1 is dot-sourced when present; otherwise the
#         CI environment must provide JAVA_HOME, ANDROID_JAR, BUILD_TOOLS and
#         KOTLINC (see .github/workflows/build.yml).
[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$localEnv = 'C:\Users\Nobody\android-toolchain\env.ps1'
if (Test-Path $localEnv) { . $localEnv }

foreach ($v in 'JAVA_HOME', 'ANDROID_JAR', 'BUILD_TOOLS', 'KOTLINC') {
    if (-not (Get-Item "env:$v" -ErrorAction SilentlyContinue).Value) {
        throw "$v is not set and no local toolchain env.ps1 was found"
    }
}

# kotlin-stdlib ships next to the compiler; KOTLIN_STDLIB wins when it lives
# somewhere else (the CI action installs it under lib/).
$stdLib = if ($env:KOTLIN_STDLIB) {
    $env:KOTLIN_STDLIB
} else {
    Join-Path (Split-Path $env:KOTLINC -Parent) '..\lib\kotlin-stdlib.jar'
}
$stdLib = [System.IO.Path]::GetFullPath($stdLib)
if (-not (Test-Path $stdLib)) { throw "kotlin-stdlib.jar not found at $stdLib" }

# The Go kernel ships as a gomobile AAR (classes.jar + one libgojni.so per ABI),
# synced by sync-kernel.ps1. It lives in the same APK as the browser now.
$gomobileJar = Join-Path $root "libs\classes.jar"
$gomobileClasses = Join-Path $root "libs\classes"
$abiLibs = Get-ChildItem (Join-Path $root 'libs\lib') -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { [pscustomobject]@{ Abi = $_.Name; So = (Join-Path $_.FullName 'libgojni.so') } } |
    Where-Object { Test-Path $_.So }
foreach ($f in @($gomobileJar, $gomobileClasses)) {
    if (-not (Test-Path $f)) { throw "missing prerequisite: $f (run sync-kernel.ps1 after building kernel.aar)" }
}
if (-not $abiLibs) { throw "no libgojni.so under libs\lib (run sync-kernel.ps1 after building kernel.aar)" }

# Refuse to package a stale kernel. libgojni.so carries no usable mtime, so the
# sync stamp is compared against the newest Go source instead; shipping a
# pre-fix binary is exactly the kind of drift this guards against.
# Same resolution order as sync-kernel.ps1: submodule, DS_KERNEL_DIR, legacy path.
$goRepo = if (Test-Path (Join-Path $root 'kernel\go.mod')) {
    Join-Path $root 'kernel'
} elseif ($env:DS_KERNEL_DIR) {
    $env:DS_KERNEL_DIR
} else {
    'D:\dev-sidecar-core-go'
}
$stampFile = Join-Path $root 'libs\.sync-stamp'
if (Test-Path $goRepo) {
    $newestGo = Get-ChildItem $goRepo -Recurse -Include *.go -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*_test.go' -and $_.FullName -notmatch '\\\.smoke\\' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($newestGo) {
        if (-not (Test-Path $stampFile)) {
            throw "libs/ has no sync stamp: run sync-kernel.ps1 (otherwise an old libgojni.so gets packaged)"
        }
        $syncedAt = [datetime](Get-Content $stampFile -Raw | ConvertFrom-Json).at
        if ($syncedAt -lt $newestGo.LastWriteTime) {
            throw ("libs/libgojni.so is older than {0} ({1}): run build-kernel-aar.ps1 then sync-kernel.ps1" -f `
                $newestGo.Name, $newestGo.LastWriteTime)
        }
    }
}

$out = Join-Path $root "build"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

# 1) aapt2: compile res (launcher icon), then link manifest + assets + those
#    compiled resources (aapt2 has no -S; compiled flat files are inputs).
& "$env:BUILD_TOOLS\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }
& "$env:BUILD_TOOLS\aapt2.exe" link -I $env:ANDROID_JAR --manifest "$root\AndroidManifest.xml" `
    -A "$root\assets" -o "$out\base.apk" "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 link failed" }
Write-Host "[1/7] aapt2 link OK"

# 2) kotlinc: compile all sources against android.jar + gomobile classes.
#    Escaped outer quotes keep the ';' classpath separator intact.
$sources = (Get-ChildItem "$root\src" -Recurse -Filter *.kt).FullName
# kotlinc prints deprecation warnings on stderr, which "Stop" would turn fatal.
$eap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $env:KOTLINC -classpath "`"$env:ANDROID_JAR;$gomobileClasses`"" $sources -d "$out\classes" 2>&1 |
    Where-Object { $_ -notmatch "^warning: unable to find kotlin-stdlib.*" } | Out-Host
$ErrorActionPreference = $eap
if ($LASTEXITCODE) { throw "kotlinc failed: exit $LASTEXITCODE" }
Write-Host "[2/7] kotlinc OK"

# 3) d8: dex our classes
New-Item -ItemType Directory -Path "$out\dex\app" | Out-Null
& "$env:BUILD_TOOLS\d8.bat" --lib $env:ANDROID_JAR --min-api 29 --output "$out\dex\app" `
    (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName
if ($LASTEXITCODE) { throw "d8 (app) failed" }
Write-Host "[3/7] d8 app OK"

# 4) d8: dex kotlin-stdlib and the gomobile bridge
New-Item -ItemType Directory -Path "$out\dex\stdlib" | Out-Null
& "$env:BUILD_TOOLS\d8.bat" --lib $env:ANDROID_JAR --min-api 29 --output "$out\dex\stdlib" $stdLib
if ($LASTEXITCODE) { throw "d8 (stdlib) failed" }
Write-Host "[4/7] d8 stdlib OK"

New-Item -ItemType Directory -Path "$out\dex\gomobile" | Out-Null
& "$env:BUILD_TOOLS\d8.bat" --lib $env:ANDROID_JAR --min-api 29 --output "$out\dex\gomobile" $gomobileJar
if ($LASTEXITCODE) { throw "d8 (gomobile) failed" }
Write-Host "[4b/7] d8 gomobile OK"

# 5) merge dex + native kernel into the apk
New-Item -ItemType Directory -Path "$out\finaldex" | Out-Null
Copy-Item "$out\dex\app\classes.dex" "$out\finaldex\classes.dex"
$next = 2
foreach ($src in @("$out\dex\gomobile", "$out\dex\stdlib")) {
    foreach ($f in (Get-ChildItem $src -Filter *.dex | Sort-Object Name)) {
        Copy-Item $f.FullName "$out\finaldex\classes$next.dex"
        $next++
    }
}
New-Item -ItemType Directory -Path "$out\stage" | Out-Null
foreach ($entry in $abiLibs) {
    $d = Join-Path "$out\stage" "lib\$($entry.Abi)"
    New-Item -ItemType Directory -Path $d -Force | Out-Null
    Copy-Item $entry.So (Join-Path $d 'libgojni.so')
}
$dexFiles = (Get-ChildItem "$out\finaldex" -Filter *.dex | Sort-Object Name | % Name)
# jar's -C takes exactly ONE file; chain one -C per dex
$jarArgs = @("uf", "$out\base.apk")
foreach ($n in $dexFiles) { $jarArgs += @("-C", "$out\finaldex", $n) }
foreach ($entry in $abiLibs) { $jarArgs += @("-C", "$out\stage", "lib/$($entry.Abi)/libgojni.so") }
& "$env:JAVA_HOME\bin\jar.exe" @jarArgs
if ($LASTEXITCODE) { throw "jar uf failed" }
$abis = ($abiLibs | ForEach-Object { $_.Abi }) -join ', '
Write-Host "[5/7] dex + .so merged ($($dexFiles -join ', '); abi: $abis)"

# 6) zipalign + 7) sign (debug keystore generated on first run)
& "$env:BUILD_TOOLS\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }
# Keep the debug keystore OUTSIDE build/ (build/ is wiped every run) so the key
# stays stable across builds and `adb install -r` keeps working.
$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore $ks -storepass android -keypass android `
        -alias dscore-debug -dname "CN=dscore-debug, OU=dev, O=blue-frontier" `
        -keyalg RSA -keysize 2048 -validity 10000 | Out-Null
}
& "$env:BUILD_TOOLS\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$out\dscore.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner sign failed" }
& "$env:BUILD_TOOLS\apksigner.bat" verify "$out\dscore.apk"
Write-Host "[6/7] zipalign OK"
Write-Host "[7/7] signed -> $out\dscore.apk"

# Archive every signed build. build\ is disposable by design, so without this a
# rebuild silently replaces the only copy — which is exactly how the
# pre-rename APK was lost. dist\ keeps the last 20, newest first.
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$archive = Join-Path $dist "ds-next-$stamp.apk"
Copy-Item "$out\dscore.apk" $archive
$sha = (Get-FileHash $archive -Algorithm SHA256).Hash
"$stamp  $sha  $archive" | Add-Content (Join-Path $dist "SHA256SUMS.txt")
Get-ChildItem $dist -Filter "ds-next-*.apk" | Sort-Object LastWriteTime -Descending |
    Select-Object -Skip 20 | ForEach-Object { Remove-Item $_.FullName -Force }
Write-Host "[8/7] archived -> dist\ds-next-$stamp.apk"

