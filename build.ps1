# No-Gradle build for the dscore app: aapt2 -> kotlinc -> d8 (x2: app + stdlib)
# -> jar uf -> zipalign -> apksigner. Run after dot-sourcing the toolchain env.
#
# Usage:  . C:\Users\Nobody\android-toolchain\env.ps1; & D:\dev-sidecar-android\build.ps1
[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
. C:\Users\Nobody\android-toolchain\env.ps1

$stdLib = "C:\Users\Nobody\android-toolchain\kotlin\lib\kotlin-stdlib.jar"
if (-not (Test-Path $stdLib)) { throw "kotlin-stdlib.jar not found at $stdLib" }

$out = Join-Path $root "build"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

# 1) aapt2: link the manifest (the app is resource-free: no res/ at all)
& "$env:BUILD_TOOLS\aapt2.exe" link -I $env:ANDROID_JAR --manifest "$root\AndroidManifest.xml" -o "$out\base.apk"
if ($LASTEXITCODE) { throw "aapt2 link failed" }
Write-Host "[1/7] aapt2 link OK"

# 2) kotlinc: compile all sources against android.jar
$sources = (Get-ChildItem "$root\src" -Recurse -Filter *.kt).FullName
& $env:KOTLINC -classpath $env:ANDROID_JAR $sources -d "$out\classes" 2>&1 |
    Where-Object { $_ -notmatch "^warning: unable to find kotlin-stdlib.*" } | Out-Host
if ($LASTEXITCODE) { throw "kotlinc failed" }
Write-Host "[2/7] kotlinc OK"

# 3) d8: dex our classes
New-Item -ItemType Directory -Path "$out\dex\app" | Out-Null
& "$env:BUILD_TOOLS\d8.bat" --lib $env:ANDROID_JAR --min-api 29 --output "$out\dex\app" `
    (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName
if ($LASTEXITCODE) { throw "d8 (app) failed" }
Write-Host "[3/7] d8 app OK"

# 4) d8: dex kotlin-stdlib (bundles the runtime; no third-party jars involved)
New-Item -ItemType Directory -Path "$out\dex\stdlib" | Out-Null
& "$env:BUILD_TOOLS\d8.bat" --lib $env:ANDROID_JAR --min-api 29 --output "$out\dex\stdlib" $stdLib
if ($LASTEXITCODE) { throw "d8 (stdlib) failed" }
Write-Host "[4/7] d8 stdlib OK"

# 5) merge dex: app = classes.dex, stdlib follows as classes2.dex...
New-Item -ItemType Directory -Path "$out\finaldex" | Out-Null
Copy-Item "$out\dex\app\classes.dex" "$out\finaldex\classes.dex"
$next = 2
foreach ($f in (Get-ChildItem "$out\dex\stdlib" -Filter *.dex | Sort-Object Name)) {
    Copy-Item $f.FullName "$out\finaldex\classes$next.dex"
    $next++
}
$dexFiles = (Get-ChildItem "$out\finaldex" -Filter *.dex | Sort-Object Name | % Name)
# jar's -C takes exactly ONE file; chain one -C per dex
$jarArgs = @("uf", "$out\base.apk")
foreach ($n in $dexFiles) { $jarArgs += @("-C", "$out\finaldex", $n) }
& "$env:JAVA_HOME\bin\jar.exe" @jarArgs
if ($LASTEXITCODE) { throw "jar uf failed" }
Write-Host "[5/7] dex merged ($($dexFiles -join ', '))"

# 6) zipalign + 7) sign (debug keystore generated on first run)
& "$env:BUILD_TOOLS\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }
$ks = Join-Path $out "debug.keystore"
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
