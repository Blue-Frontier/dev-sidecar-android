#!/usr/bin/env bash
# Unpacks the plain-jar dependencies the app links against. The build has no
# Gradle, so an AAR is reduced to its classes.jar: that is all kotlinc and d8
# need.
#
# A checked-in AAR under deps/vendor/ wins over the network. That is what makes
# the build work inside F-Droid's network-less build VM, and it keeps CI from
# depending on Google's maven being reachable.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
JARS="$HERE/jars"
VENDOR="$HERE/vendor"
mkdir -p "$JARS"

unpack() { # aar out
    local tmp
    tmp=$(mktemp -d)
    unzip -q "$1" classes.jar -d "$tmp"
    cp "$tmp/classes.jar" "$2"
    rm -rf "$tmp"
}

fetch() { # name url out vendored-name
    local name=$1 url=$2 out=$3 vendored=$4 aar tmp downloaded=0
    if [[ -f "$out" ]]; then
        echo "$name: cached"
        return
    fi
    aar="$VENDOR/$vendored"
    if [[ -f "$aar" ]]; then
        echo "$name: from $aar"
    else
        tmp=$(mktemp)
        echo "fetching $url"
        if ! curl --fail --location --silent --show-error --max-time 120 -o "$tmp" "$url"; then
            rm -f "$tmp"
            echo "download failed: $url (drop the artifact into deps/vendor/$vendored for an offline build)" >&2
            return 1
        fi
        aar=$tmp
        downloaded=1
    fi
    unpack "$aar" "$out"
    if ((downloaded)); then rm -f "$aar"; fi
    echo "  -> $out"
}

# androidx.webkit provides ProxyController/ProxyConfig: pointing the WebView at
# the kernel as an HTTP proxy is the only way a POST keeps its body.
fetch webkit \
    https://dl.google.com/dl/android/maven2/androidx/webkit/webkit/1.12.1/webkit-1.12.1.aar \
    "$JARS/webkit.jar" \
    webkit-1.12.1.aar

