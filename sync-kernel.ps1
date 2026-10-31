# Syncs the gomobile AAR into this app: classes.jar (dex + kotlinc classpath)
# and one libgojni.so per ABI the AAR carries. Run after
# <kernel>\build-kernel-aar.ps1 (which decides the ABIs via -Target).
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# The kernel is a git submodule at kernel/. DS_KERNEL_DIR overrides it for a
# working copy elsewhere; the old absolute path stays as the last resort so an
# existing checkout keeps building before the submodule is initialised.
$kernelDir = if (Test-Path (Join-Path $root 'kernel\go.mod')) {
    Join-Path $root 'kernel'
} elseif ($env:DS_KERNEL_DIR) {
    $env:DS_KERNEL_DIR
} elseif (Test-Path 'D:\dev-sidecar-core-go\go.mod') {
    'D:\dev-sidecar-core-go'
} else {
    throw 'kernel source not found: run `git submodule update --init` or set DS_KERNEL_DIR'
}
$aar = Join-Path $kernelDir 'build\kernel.aar'
if (-not (Test-Path $aar)) { throw "kernel.aar not built yet: $aar (run $(Join-Path $kernelDir 'build-kernel-aar.ps1'))" }

$libs = Join-Path $root 'libs'
if (Test-Path $libs) { Remove-Item $libs -Recurse -Force }
New-Item -ItemType Directory -Path $libs | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$tmp = Join-Path $env:TEMP ('aar-' + [guid]::NewGuid())
[System.IO.Compression.ZipFile]::ExtractToDirectory($aar, $tmp)
try {
    Copy-Item (Join-Path $tmp 'classes.jar') (Join-Path $libs 'classes.jar') -Force

    # jni/<abi>/libgojni.so -- keep the ABI directory name, it is what the
    # APK's lib/<abi>/ layout has to match.
    $sos = Get-ChildItem $tmp -Recurse -Filter libgojni.so
    if (-not $sos) { throw 'libgojni.so missing from aar' }
    foreach ($so in $sos) {
        $abi = Split-Path $so.DirectoryName -Leaf
        $dst = Join-Path $libs "lib\$abi"
        New-Item -ItemType Directory -Path $dst -Force | Out-Null
        Copy-Item $so.FullName (Join-Path $dst 'libgojni.so') -Force
    }
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# kotlinc needs the .class files on disk (a bare ";" in -classpath gets mangled).
[System.IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $libs 'classes.jar'), (Join-Path $libs 'classes'))

# build.ps1 refuses to package a stale kernel; this records what was synced.
$synced = Get-ChildItem (Join-Path $libs 'lib') -Directory | ForEach-Object {
    $so = Join-Path $_.FullName 'libgojni.so'
    [pscustomobject]@{
        abi      = $_.Name
        bytes    = (Get-Item $so).Length
        soSha256 = (Get-FileHash $so -Algorithm SHA256).Hash
    }
}
[pscustomobject]@{
    at  = (Get-Date).ToString('o')
    abi = @($synced)
} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $libs '.sync-stamp') -Encoding UTF8

Write-Host ("kernel synced: {0:N0} bytes classes.jar; {1}" -f `
    (Get-Item (Join-Path $libs 'classes.jar')).Length,
    (($synced | ForEach-Object { "$($_.abi) $([math]::Round($_.bytes / 1MB, 1))MB" }) -join ', '))
