#!/usr/bin/env bash
# One-shot developer setup on Linux / WSL2 (Ubuntu or Debian): installs the exact
# toolchain Bivouac is built with, under ~/.local and ~/Android (no system Java/SDK).
#
#   git clone https://github.com/Wolverr/bivouac && cd bivouac && tools/setup_dev.sh
#
# Afterwards: `source tools/dev.env` in each new shell, then ./gradlew assembleDebug.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD

JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20_8.tar.gz"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"
SDK_PACKAGES=("platform-tools" "platforms;android-36" "build-tools;36.1.0" "ndk;29.0.14206865" "cmake;3.31.6")

JDK_DIR="$HOME/.local/opt/jdk-17.0.20+8"
SDK_DIR="$HOME/Android/Sdk"

echo "== system packages (sudo)"
if command -v apt-get >/dev/null; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq build-essential clang git curl unzip bzip2 ca-certificates >/dev/null
fi

echo "== JDK 17 (Temurin 17.0.20+8)"
if [ ! -x "$JDK_DIR/bin/java" ]; then
    mkdir -p "$JDK_DIR"
    curl -fsSL "$JDK_URL" | tar -xz -C "$JDK_DIR" --strip-components=1
fi

echo "== Android SDK"
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/clt.zip" "$CMDLINE_TOOLS_URL"
    unzip -q "$tmp/clt.zip" -d "$tmp"
    mkdir -p "$SDK_DIR/cmdline-tools"
    mv "$tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$tmp"
fi
export JAVA_HOME="$JDK_DIR"
SDKM="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK_DIR"
yes | $SDKM --licenses >/dev/null 2>&1 || true
$SDKM "${SDK_PACKAGES[@]}" | grep -vE '^\[|Downloading|Unzipping' || true

echo "== sources"
git submodule update --init --depth 1
echo "sdk.dir=$SDK_DIR" > local.properties

echo "== Python tools (uv)"
if ! command -v uv >/dev/null; then
    curl -LsSf https://astral.sh/uv/install.sh | sh
    export PATH="$HOME/.local/bin:$PATH"
fi
uv venv -q --python 3.12 tools/.venv
uv pip install -q --python tools/.venv/bin/python -r tools/requirements.txt

cat > tools/dev.env <<ENV
# source tools/dev.env
export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export PATH="$JDK_DIR/bin:$SDK_DIR/platform-tools:$HOME/.local/bin:\$PATH"
ENV

echo
echo "Done. Next:"
echo "  source tools/dev.env && ./gradlew assembleDebug     # build the APK"
echo "  tools/run_data_jobs.sh                              # download + build the knowledge packs"
