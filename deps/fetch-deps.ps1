# Fetches the plain-jar dependencies the app links against. The build has no
# Gradle, so an AAR is unpacked and its classes.jar is kept: that is all kotlinc
# and d8 need. Run once per checkout (CI runs it before build.ps1).
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jars = Join-Path $root 'jars'
New-Item -ItemType Directory -Path $jars -Force | Out-Null

# androidx.webkit provides ProxyController/ProxyConfig: pointing the WebView at
# the kernel as an HTTP proxy is the only way a POST keeps its body.
# freedroidwarn shows the "this app will stop working after Google's developer
# verification" notice; its resources are merged by the build, so the whole res
# directory is kept next to the jar.
$deps = @(
    @{ Name = 'webkit'; Url = 'https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.12.1/webkit-1.12.1.aar' }
    @{ Name = 'freedroidwarn'; Url = 'https://jitpack.io/com/github/woheller69/FreeDroidWarn/V1.14/FreeDroidWarn-V1.14.aar'; Res = $true }
)

Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($dep in $deps) {
    $out = Join-Path $jars "$($dep.Name).jar"
    $resDir = if ($dep.Res) { Join-Path $root "res-$($dep.Name)" } else { $null }
    # An AAR with resources is only usable when both halves are present.
    if ((Test-Path $out) -and ($resDir -eq $null -or (Test-Path $resDir))) { Write-Host "$($dep.Name): cached"; continue }

    $aar = Join-Path $env:TEMP "$($dep.Name).aar"
    Write-Host "fetching $($dep.Url)"
    # schannel needs revocation checks disabled for dl.google.com here.
    & curl.exe --max-time 120 -sS --ssl-no-revoke -L -o $aar $dep.Url
    if ($LASTEXITCODE) { throw "download failed: $($dep.Url)" }

    $tmp = Join-Path $env:TEMP ('aar-' + [guid]::NewGuid())
    [System.IO.Compression.ZipFile]::ExtractToDirectory($aar, $tmp)
    Copy-Item (Join-Path $tmp 'classes.jar') $out -Force
    if ($dep.Res) {
        $resDir = Join-Path $root "res-$($dep.Name)"
        Remove-Item $resDir -Recurse -Force -ErrorAction SilentlyContinue
        Copy-Item (Join-Path $tmp 'res') $resDir -Recurse -Force
        Write-Host "  -> $resDir"
    }
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $aar -Force -ErrorAction SilentlyContinue
    Write-Host "  -> $out"
}

