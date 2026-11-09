#!/usr/bin/env bash
# Syncs the gomobile AAR into this app: classes.jar (dex + kotlinc classpath)
# and one libgojni.so per ABI the AAR carries.
#
# Run after kernel/build-kernel-aar.sh, which decides the ABIs.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# The kernel is a git submodule at kernel/. DS_KERNEL_DIR overrides it for a
# working copy elsewhere.
if [[ -f "$ROOT/kernel/go.mod" ]]; then
    KERNEL_DIR="$ROOT/kernel"
elif [[ -n "${DS_KERNEL_DIR:-}" ]]; then
    KERNEL_DIR="$DS_KERNEL_DIR"
else
    echo "kernel source not found: run 'git submodule update --init' or set DS_KERNEL_DIR" >&2
    exit 1
fi

AAR="$KERNEL_DIR/build/kernel.aar"
if [[ ! -f "$AAR" ]]; then
    echo "kernel.aar not built yet: $AAR (run kernel/build-kernel-aar.sh)" >&2
    exit 1
fi

LIBS="$ROOT/libs"
rm -rf "$LIBS"
mkdir -p "$LIBS"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q "$AAR" -d "$TMP"

cp "$TMP/classes.jar" "$LIBS/classes.jar"

# jni/<abi>/libgojni.so -- keep the ABI directory name: the APK's lib/<abi>/
# layout has to match it.
found=0
while IFS= read -r so; do
    abi=$(basename "$(dirname "$so")")
    mkdir -p "$LIBS/lib/$abi"
    cp "$so" "$LIBS/lib/$abi/libgojni.so"
    found=1
done < <(find "$TMP" -name libgojni.so | sort)
if ((found == 0)); then echo "libgojni.so missing from $AAR" >&2; exit 1; fi

# kotlinc needs the .class files on disk (a classpath entry pointing at a jar
# works too, but this keeps the flag identical to the old flow).
mkdir -p "$LIBS/classes"
unzip -q "$LIBS/classes.jar" -d "$LIBS/classes"

# build.sh refuses to package a stale kernel; this records what was synced.
AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
{
    printf '{\n  "at": "%s",\n  "abi": [\n' "$AT"
    first=1
    while IFS= read -r so; do
        abi=$(basename "$(dirname "$so")")
        bytes=$(wc -c <"$so" | tr -d ' ')
        sha=$(sha256sum "$so" | cut -d' ' -f1)
        ((first)) || printf ',\n'
        printf '    { "abi": "%s", "bytes": %s, "soSha256": "%s" }' "$abi" "$bytes" "$sha"
        first=0
    done < <(find "$LIBS/lib" -name libgojni.so | sort)
    printf '\n  ]\n}\n'
} >"$LIBS/.sync-stamp"

echo "kernel synced: $(wc -c <"$LIBS/classes.jar" | tr -d ' ') bytes classes.jar; $(find "$LIBS/lib" -name libgojni.so | wc -l | tr -d ' ') abi(s)"
