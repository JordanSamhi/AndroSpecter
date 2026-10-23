#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "Usage: bash build.sh <android-sdk> <build-tools-version> <platform-folder>" >&2
    exit 2
fi
fixture_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
fixture_sdk="$1"
fixture_tools="$fixture_sdk/build-tools/$2"
fixture_platform="$fixture_sdk/platforms/$3/android.jar"
fixture_output="$fixture_dir/../../resources/apks/managed-analysis.apk"
fixture_work="$(mktemp -d)"
trap 'rm -rf "$fixture_work"' EXIT
fixture_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"

mkdir -p "$fixture_work/classes" "$fixture_work/dex"
"$fixture_javac" --release 8 -encoding UTF-8 -classpath "$fixture_platform" \
    -d "$fixture_work/classes" "$fixture_dir/src/demo/taint/MainActivity.java"
"$fixture_tools/d8" --min-api 21 --lib "$fixture_platform" \
    --output "$fixture_work/dex" "$fixture_work/classes/demo/taint/MainActivity.class"
"$fixture_tools/aapt" package -f -M "$fixture_dir/AndroidManifest.xml" \
    -S "$fixture_dir/res" -I "$fixture_platform" -F "$fixture_work/managed-analysis.apk"
(
    cd "$fixture_work/dex"
    "$fixture_tools/aapt" add "$fixture_work/managed-analysis.apk" classes.dex
)
mkdir -p "$(dirname -- "$fixture_output")"
cp "$fixture_work/managed-analysis.apk" "$fixture_output"
echo "Built unsigned static-analysis fixture: $fixture_output"
