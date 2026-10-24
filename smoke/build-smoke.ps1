# No-Gradle APK build chain: aapt2 -> kotlinc -> d8 -> jar uf -> zipalign -> apksigner.
# Usage:  . C:\Users\Nobody\android-toolchain\env.ps1; & C:\Users\Nobody\android-toolchain\smoke\build-smoke.ps1
[CmdletBinding()]
param(
    [string]$SrcDir = "C:\Users\Nobody\android-toolchain\smoke"
)
$ErrorActionPreference = "Stop"
. C:\Users\Nobody\android-toolchain\env.ps1

$root = $SrcDir
$out = Join-Path $root "out"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

# 1) aapt2: compile + link manifest into a base APK (no res/ in this smoke app)
& "$env:BUILD_TOOLS\aapt2.exe" link -I $env:ANDROID_JAR --manifest "$root\AndroidManifest.xml" -o "$out\base.apk"
if ($LASTEXITCODE) { throw "aapt2 link failed" }
Write-Host "[1/6] aapt2 link OK"

# 2) kotlinc: compile Kotlin against android.jar
& $env:KOTLINC -classpath $env:ANDROID_JAR "$root\MainActivity.kt" -d "$out\classes"
if ($LASTEXITCODE) { throw "kotlinc failed" }
Write-Host "[2/6] kotlinc OK"

# 3) d8: dex the class files (output dir must exist for R8)
New-Item -ItemType Directory -Path "$out\dex" | Out-Null
$classes = (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName
& "$env:BUILD_TOOLS\d8.bat" --lib $env:ANDROID_JAR --min-api 24 --output "$out\dex" $classes
if ($LASTEXITCODE) { throw "d8 failed" }
Write-Host "[3/6] d8 OK"

# 4) merge classes.dex into the APK
& "$env:JAVA_HOME\bin\jar.exe" uf "$out\base.apk" -C "$out\dex" classes.dex
if ($LASTEXITCODE) { throw "jar uf failed" }
Write-Host "[4/6] dex merged"

# 5) zipalign
& "$env:BUILD_TOOLS\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }
Write-Host "[5/6] zipalign OK"

# 6) sign with a local debug keystore
$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -dname "CN=dev-sidecar-smoke, OU=dev, O=docmirror" `
        -keyalg RSA -keysize 2048 -validity 10000 | Out-Null
}
& "$env:BUILD_TOOLS\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$root\smoke.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner sign failed" }
& "$env:BUILD_TOOLS\apksigner.bat" verify --print-certs "$root\smoke.apk"
Write-Host "[6/6] signed -> $root\smoke.apk"
