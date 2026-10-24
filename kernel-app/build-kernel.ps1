# No-Gradle build for the ds-kernel companion app.
# aapt2(--assets) -> kotlinc(classpath+gomobile classes.jar) -> d8(x3: app/gomobile/stdlib)
# -> jar uf(dex + lib/arm64-v8a/libgojni.so) -> zipalign -> apksigner.
#
# Prereqs:  . C:\Users\Nobody\android-toolchain\env.ps1
#           kernel.aar already built (D:\dev-sidecar-core-go\build\kernel.aar)
#           and extracted to kernel-app\libs\ (classes.jar + libgojni.so).
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot                 # .../kernel-app
$repo = Split-Path $root -Parent      # .../dev-sidecar-android
. C:\Users\Nobody\android-toolchain\env.ps1

$stdLib = "C:\Users\Nobody\android-toolchain\kotlin\lib\kotlin-stdlib.jar"
$gomobileJar = Join-Path $root "libs\classes.jar"
$goSo = Join-Path $root "libs\libgojni.so"
foreach ($f in @($stdLib, $gomobileJar, $goSo)) {
    if (-not (Test-Path $f)) { throw "missing prerequisite: $f" }
}

# The kotlinc classpath needs the gomobile .class files; PowerShell mangles a
# bare ";" inside -classpath, so we dex from the jar but compile against an
# extracted directory and pass the classpath with escaped outer quotes.
$gomobileClasses = Join-Path $root "libs\classes"
if (-not (Test-Path $gomobileClasses)) {
    $tmp = [System.IO.Path]::GetTempFileName()
    Copy-Item $gomobileJar $tmp -Force
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($tmp, $gomobileClasses)
    Remove-Item $tmp -Force
}

$out = Join-Path $root "build"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

# 1) aapt2: manifest + assets (config + CA)
& "$env:BUILD_TOOLS\aapt2.exe" link -I $env:ANDROID_JAR --manifest "$root\AndroidManifest.xml" `
    -A "$root\assets" -o "$out\base.apk"
if ($LASTEXITCODE) { throw "aapt2 link failed" }
Write-Host "[1/6] aapt2 link OK"

# 2) kotlinc: companion sources, classpath = android.jar + gomobile classes dir
#    (escaped outer quotes keep the ';' classpath separator intact).
$sources = (Get-ChildItem "$root\src" -Recurse -Filter *.kt).FullName
# kotlinc prints warnings (stderr) that Stop would turn fatal; keep going and
# check the real exit code instead.
$eap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $env:KOTLINC -classpath "`"$env:ANDROID_JAR;$gomobileClasses`"" $sources -d "$out\classes" 2>&1 |
    Where-Object { $_ -notmatch "^warning: (unable to find kotlin-stdlib|Deprecated)" -and $_ -notmatch "Fun getSystemWindowInsetTop" } | Out-Host
$ErrorActionPreference = $eap
if ($LASTEXITCODE) { throw "kotlinc failed: exit $LASTEXITCODE" }
Write-Host "[2/6] kotlinc OK"

# 3) d8: dex the three class sources
foreach ($d in @("app","gomobile","stdlib")) { New-Item -ItemType Directory -Path "$out\dex\$d" | Out-Null }
& "$env:BUILD_TOOLS\d8.bat" --lib $env:ANDROID_JAR --min-api 29 --output "$out\dex\app" `
    (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName
if ($LASTEXITCODE) { throw "d8 (app) failed" }
& "$env:BUILD_TOOLS\d8.bat" --lib $env:ANDROID_JAR --min-api 29 --output "$out\dex\gomobile" $gomobileJar
if ($LASTEXITCODE) { throw "d8 (gomobile) failed" }
& "$env:BUILD_TOOLS\d8.bat" --lib $env:ANDROID_JAR --min-api 29 --output "$out\dex\stdlib" $stdLib
if ($LASTEXITCODE) { throw "d8 (stdlib) failed" }
Write-Host "[3/6] d8 x3 OK"

# 4) merge dex + native lib into the apk
New-Item -ItemType Directory -Path "$out\finaldex" | Out-Null
Copy-Item "$out\dex\app\classes.dex" "$out\finaldex\classes.dex"
$idx = 2
foreach ($src in @("$out\dex\gomobile","$out\dex\stdlib")) {
    foreach ($f in (Get-ChildItem $src -Filter *.dex | Sort-Object Name)) {
        Copy-Item $f.FullName "$out\finaldex\classes$idx.dex"
        $idx++
    }
}
# native lib staged under lib/arm64-v8a/
New-Item -ItemType Directory -Path "$out\stage\lib\arm64-v8a" | Out-Null
Copy-Item $goSo "$out\stage\lib\arm64-v8a\libgojni.so"
# jar -C takes one file per -C; build the arg list with one -C per entry.
$jarArgs = @("uf", "$out\base.apk")
foreach ($n in (Get-ChildItem "$out\finaldex" -Filter *.dex | Sort-Object Name | % Name)) {
    $jarArgs += @("-C", "$out\finaldex", $n)
}
$jarArgs += @("-C", "$out\stage", "lib/arm64-v8a/libgojni.so")
& "$env:JAVA_HOME\bin\jar.exe" @jarArgs
if ($LASTEXITCODE) { throw "jar uf failed" }
Write-Host "[4/6] dex + .so merged"

# 5) zipalign + 6) sign (reuse the repo-root debug keystore)
& "$env:BUILD_TOOLS\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }
$ks = Join-Path $repo "debug.keystore"
& "$env:BUILD_TOOLS\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$out\ds-kernel.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner sign failed" }
& "$env:BUILD_TOOLS\apksigner.bat" verify "$out\ds-kernel.apk"
Write-Host "[5/6] zipalign OK"
Write-Host "[6/6] signed -> $out\ds-kernel.apk"
