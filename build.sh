#!/usr/bin/env bash
# No-Gradle build for DS NEXT: aapt2 -> kotlinc -> d8 -> jar uf -> zipalign
# -> apksigner. Runs on Debian (F-Droid's build server) and in Git Bash on
# Windows; the only differences are the tool suffixes and the classpath
# separator, both handled below.
#
# Usage:  ./build.sh [--debuggable]
#         Toolchain comes from the environment (JAVA_HOME, ANDROID_JAR,
#         BUILD_TOOLS, KOTLINC) or from a file named env.sh next to this script.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DEBUGGABLE=0
for arg in "$@"; do
    case "$arg" in
        --debuggable) DEBUGGABLE=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

if [[ -f "$ROOT/env.sh" ]]; then
    # shellcheck disable=SC1091
    . "$ROOT/env.sh"
fi

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) WINDOWS=1 ;;
    *) WINDOWS=0 ;;
esac

# Native tools want native paths; MSYS does not rewrite arguments that carry a
# separator or a backslash, so convert explicitly.
native() {
    if ((WINDOWS)); then cygpath -w "$1"; else printf '%s' "$1"; fi
}
if ((WINDOWS)); then
    JAVA_HOME=$(cygpath -u "$JAVA_HOME")
    ANDROID_JAR=$(cygpath -u "$ANDROID_JAR")
    BUILD_TOOLS=$(cygpath -u "$BUILD_TOOLS")
    KOTLINC=$(cygpath -u "$KOTLINC")
fi

for v in JAVA_HOME ANDROID_JAR BUILD_TOOLS KOTLINC; do
    if [[ -z "${!v:-}" ]]; then
        echo "$v is not set: point it at the toolchain or write env.sh" >&2
        exit 1
    fi
done

# kotlinc ships as a shell script on both platforms; the .bat next to it is not
# usable from bash.
if [[ "$KOTLINC" == *.bat ]]; then KOTLINC="${KOTLINC%.bat}"; fi
JAVA="$JAVA_HOME/bin/java"
JAR="$JAVA_HOME/bin/jar"
case "$KOTLINC" in "$KOTLINC.bat") ;; esac

if ((WINDOWS)); then CP_SEP=';'; else CP_SEP=':'; fi
EXE=""
if ((WINDOWS)); then EXE=".exe"; fi
AAPT2="$BUILD_TOOLS/aapt2$EXE"
ZIPALIGN="$BUILD_TOOLS/zipalign$EXE"
D8_JAR="$BUILD_TOOLS/lib/d8.jar"
APKSIGNER_JAR="$BUILD_TOOLS/lib/apksigner.jar"

for t in "$AAPT2" "$ZIPALIGN" "$D8_JAR" "$APKSIGNER_JAR" "$JAVA" "$JAR" "$KOTLINC"; do
    if [[ ! -e "$t" ]]; then echo "missing tool: $t" >&2; exit 1; fi
done

# Zip timestamps are written in local time, so a build in another timezone would
# produce different bytes from the same source.
export TZ=UTC
export LANG=C
export LC_ALL=C

# kotlin-stdlib ships next to the compiler; KOTLIN_STDLIB wins when it lives
# somewhere else (the CI action installs it under lib/).
STD_LIB="${KOTLIN_STDLIB:-$(cd "$(dirname "$KOTLINC")/../lib" && pwd)/kotlin-stdlib.jar}"
if [[ ! -f "$STD_LIB" ]]; then echo "kotlin-stdlib.jar not found at $STD_LIB" >&2; exit 1; fi

# The Go kernel ships as a gomobile AAR (classes.jar + one libgojni.so per ABI),
# synced by sync-kernel.sh. It lives in the same APK as the browser.
GOMOBILE_JAR="$ROOT/libs/classes.jar"
GOMOBILE_CLASSES="$ROOT/libs/classes"
if [[ ! -f "$GOMOBILE_JAR" || ! -d "$GOMOBILE_CLASSES" ]]; then
    echo "missing prerequisite: libs/ (run sync-kernel.sh after building kernel.aar)" >&2
    exit 1
fi

mapfile -t DEP_JARS < <(find "$ROOT/deps/jars" -maxdepth 1 -name '*.jar' | sort)
mapfile -t ABI_LIBS < <(find "$ROOT/libs/lib" -mindepth 2 -maxdepth 2 -name 'libgojni.so' | sort)
if ((${#ABI_LIBS[@]} == 0)); then
    echo "no libgojni.so under libs/lib (run sync-kernel.sh after building kernel.aar)" >&2
    exit 1
fi

# Refuse to package a stale kernel: libgojni.so carries no usable mtime, so the
# sync stamp is compared against the newest Go source instead.
GO_REPO="$ROOT/kernel"
if [[ ! -f "$GO_REPO/go.mod" ]]; then
    GO_REPO="${DS_KERNEL_DIR:-/D/dev-sidecar-core-go}"
fi
STAMP="$ROOT/libs/.sync-stamp"
if [[ -d "$GO_REPO" ]]; then
    NEWEST_GO=$(find "$GO_REPO" -name '*.go' -not -name '*_test.go' -not -path '*/.smoke/*' -printf '%T@ %p\n' 2>/dev/null |
        sort -n | tail -1 | cut -d' ' -f2-)
    if [[ -n "$NEWEST_GO" ]]; then
        if [[ ! -f "$STAMP" ]]; then
            echo "libs/ has no sync stamp: run sync-kernel.sh (an old libgojni.so would get packaged)" >&2
            exit 1
        fi
        if [[ "$NEWEST_GO" -nt "$STAMP" ]]; then
            echo "libs/libgojni.so is older than $NEWEST_GO: run build-kernel-aar.sh then sync-kernel.sh" >&2
            exit 1
        fi
    fi
fi

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT"

version_code() {
    local name=$1 major minor patch
    IFS=. read -r major minor patch _ <<<"$name"
    major=${major//[!0-9]/}
    minor=${minor//[!0-9]/}
    patch=${patch//[!0-9]/}
    printf '%d' $((10#${major:-0} * 10000 + 10#${minor:-0} * 100 + 10#${patch:-0}))
}

VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/AndroidManifest.xml" | head -1)
VERSION_NAME=${VERSION_NAME:-0.0.0}
VERSION_CODE=$(version_code "$VERSION_NAME")

# 1) aapt2: compile res, then link manifest + assets + those compiled resources.
"$AAPT2" compile --dir "$(native "$ROOT/res")" -o "$(native "$OUT/res.zip")"
LINK_ARGS=(link
    -I "$(native "$ANDROID_JAR")"
    --manifest "$(native "$ROOT/AndroidManifest.xml")"
    --version-code "$VERSION_CODE"
    --version-name "$VERSION_NAME"
    -A "$(native "$ROOT/assets")"
    -o "$(native "$OUT/base.apk")"
    "$(native "$OUT/res.zip")")
# aapt2 treats an empty argument as an input file, so the switch is only added
# when it is actually requested.
if ((DEBUGGABLE)); then LINK_ARGS+=(--debug-mode); fi
"$AAPT2" "${LINK_ARGS[@]}"
echo "[1/7] aapt2 link OK"

# 2) kotlinc: compile every source against android.jar and the gomobile classes.
mapfile -t SOURCES < <(find "$ROOT/src" -name '*.kt' | sort)
CP="$(native "$ANDROID_JAR")$CP_SEP$(native "$GOMOBILE_CLASSES")"
for j in "${DEP_JARS[@]}"; do CP="$CP$CP_SEP$(native "$j")"; done
"$KOTLINC" -classpath "$CP" "${SOURCES[@]}" -d "$(native "$OUT/classes")" 2>&1 |
    grep -v '^warning: unable to find kotlin-stdlib' || true
if [[ ! -d "$OUT/classes" ]]; then echo "kotlinc produced no classes" >&2; exit 1; fi
echo "[2/7] kotlinc OK"

dex() {
    local out_dir=$1
    shift
    mkdir -p "$out_dir"
    "$JAVA" -cp "$(native "$D8_JAR")" com.android.tools.r8.D8 \
        --lib "$(native "$ANDROID_JAR")" --min-api 29 --output "$(native "$out_dir")" \
        "$@"
}

# 3) d8: our classes.
mapfile -t CLASSES < <(find "$OUT/classes" -name '*.class' | sort)
NATIVE_CLASSES=()
for c in "${CLASSES[@]}"; do NATIVE_CLASSES+=("$(native "$c")"); done
dex "$OUT/dex/app" "${NATIVE_CLASSES[@]}"
echo "[3/7] d8 app OK"

# 4) d8: kotlin-stdlib, the gomobile bridge and the AAR dependencies.
dex "$OUT/dex/stdlib" "$(native "$STD_LIB")"
echo "[4/7] d8 stdlib OK"
dex "$OUT/dex/gomobile" "$(native "$GOMOBILE_JAR")"
echo "[4b/7] d8 gomobile OK"
if ((${#DEP_JARS[@]} > 0)); then
    NATIVE_DEPS=()
    for j in "${DEP_JARS[@]}"; do NATIVE_DEPS+=("$(native "$j")"); done
    dex "$OUT/dex/deps" "${NATIVE_DEPS[@]}"
    echo "[4c/7] d8 deps OK"
fi

# 5) merge dex + the native kernel into the apk.
mkdir -p "$OUT/finaldex" "$OUT/stage"
cp "$OUT/dex/app/classes.dex" "$OUT/finaldex/classes.dex"
next=2
for src in "$OUT/dex/gomobile" "$OUT/dex/stdlib" "$OUT/dex/deps"; do
    [[ -d "$src" ]] || continue
    while IFS= read -r f; do
        cp "$f" "$OUT/finaldex/classes$next.dex"
        next=$((next + 1))
    done < <(find "$src" -maxdepth 1 -name '*.dex' | sort)
done
for so in "${ABI_LIBS[@]}"; do
    abi=$(basename "$(dirname "$so")")
    mkdir -p "$OUT/stage/lib/$abi"
    cp "$so" "$OUT/stage/lib/$abi/libgojni.so"
done

# A zip entry carries the file mtime, so two builds of the same source differed
# only in those timestamps. Pin them: this is where a reproducible build starts.
while IFS= read -r f; do touch -t 198001010000 "$f"; done < <(find "$OUT/finaldex" "$OUT/stage" -type f)

JAR_ARGS=(uf "$(native "$OUT/base.apk")")
while IFS= read -r n; do JAR_ARGS+=(-C "$(native "$OUT/finaldex")" "$n"); done < <(find "$OUT/finaldex" -maxdepth 1 -name '*.dex' -printf '%f\n' | sort)
for so in "${ABI_LIBS[@]}"; do
    abi=$(basename "$(dirname "$so")")
    JAR_ARGS+=(-C "$(native "$OUT/stage")" "lib/$abi/libgojni.so")
done
"$JAR" "${JAR_ARGS[@]}"
echo "[5/7] dex + .so merged"

# 6) zipalign + 7) sign.
"$ZIPALIGN" -f 4 "$(native "$OUT/base.apk")" "$(native "$OUT/aligned.apk")"

# The key comes from the environment when it is provided, otherwise a local
# development key in the user's home directory is generated once so that a
# locally installed build can be replaced by the next one.
#
# A tagged release is what users install, so it has to carry the private release
# key: falling back to a keystore that ships with the source would let anyone
# sign an "update" that Android accepts over the real app.
if [[ "${GITHUB_REF_TYPE:-}" == "tag" && -z "${DS_KEYSTORE_B64:-}" ]]; then
    echo "release tags must be signed with DS_KEYSTORE_B64 (a public keystore cannot authenticate an update)" >&2
    exit 1
fi
# A local development key lives in the user's home directory, never in the
# checkout: a keystore committed here would let anyone sign an "update" that
# Android installs over the real app.
KS="${DS_DEBUG_KEYSTORE:-$HOME/.ds-next/debug.keystore}"
mkdir -p "$(dirname "$KS")"
KS_PASS=android
KEY_PASS=android
ALIAS=dscore-debug
if [[ -n "${DS_KEYSTORE_B64:-}" ]]; then
    KS="$OUT/release.keystore"
    printf '%s' "$DS_KEYSTORE_B64" | base64 -d >"$KS"
    [[ -n "${DS_KEYSTORE_PASS:-}" ]] && KS_PASS=$DS_KEYSTORE_PASS
    KEY_PASS=${DS_KEY_PASS:-$KS_PASS}
    [[ -n "${DS_KEY_ALIAS:-}" ]] && ALIAS=$DS_KEY_ALIAS
    echo "signing with the keystore from DS_KEYSTORE_B64 (alias $ALIAS)"
elif [[ ! -f "$KS" ]]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$(native "$KS")" \
        -storepass "$KS_PASS" -keypass "$KEY_PASS" -alias "$ALIAS" \
        -dname "CN=dscore-debug, OU=dev, O=blue-frontier" \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null
    echo "generated a local development keystore at $KS"
fi
# v1 (JAR) signing stamps META-INF entries with the build time. minSdk is 29, so
# the v3 block alone covers every supported device and v1 is pure noise.
"$JAVA" -jar "$(native "$APKSIGNER_JAR")" sign \
    --ks "$(native "$KS")" --ks-key-alias "$ALIAS" \
    --v1-signing-enabled false \
    "--ks-pass=pass:$KS_PASS" "--key-pass=pass:$KEY_PASS" \
    --out "$(native "$OUT/dscore.apk")" "$(native "$OUT/aligned.apk")"
"$JAVA" -jar "$(native "$APKSIGNER_JAR")" verify "$(native "$OUT/dscore.apk")" >/dev/null
echo "[6/7] zipalign OK"
echo "[7/7] signed -> $OUT/dscore.apk"

# Archive every signed build: build/ is disposable, dist/ keeps the last 20.
DIST="$ROOT/dist"
mkdir -p "$DIST"
STAMP=$(date -u +%Y%m%d-%H%M%S)
ARCHIVE="$DIST/ds-next-$STAMP.apk"
cp "$OUT/dscore.apk" "$ARCHIVE"
if command -v sha256sum >/dev/null; then
    SHA=$(sha256sum "$ARCHIVE" | cut -d' ' -f1)
else
    SHA=$(shasum -a 256 "$ARCHIVE" | cut -d' ' -f1)
fi
printf '%s  %s  %s\n' "$STAMP" "$SHA" "$ARCHIVE" >>"$DIST/SHA256SUMS.txt"
find "$DIST" -maxdepth 1 -name 'ds-next-*.apk' -printf '%T@ %p\n' | sort -rn | tail -n +21 | cut -d' ' -f2- |
    while IFS= read -r old; do rm -f "$old"; done
echo "[8/7] archived -> dist/ds-next-$STAMP.apk"
