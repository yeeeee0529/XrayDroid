#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM="$PROJECT_ROOT/upstream/3x-ui"
UPSTREAM_COMMIT="7ef22f94c950ff09f0870e2295fa65ad5968742c"
XRAY_VERSION="v26.6.27"
XRAY_SOURCE="$PROJECT_ROOT/upstream/Xray-core"
XRAY_COMMIT="45cf2898ab12e97a55dd8f1f3d78d903340bdc9e"
XRAY_SHA256="9621d72c2f706f47d7bc3c79b5326c12aa29d29013beada1c60df84ff8fe3a0f"
XRAY_DGST_SHA256="3ccf5810df31b013b73bc05f6391d0a5f0687a4d322e9cf86f2eeabd9892558b"
CACHE_DIR="$PROJECT_ROOT/.core-cache"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-ndk}"

if [[ ! -d "$UPSTREAM/.git" ]]; then
    mkdir -p "$(dirname "$UPSTREAM")"
    git clone --branch v3.8.5 --depth 1 https://github.com/MHSanaei/3x-ui.git "$UPSTREAM"
fi
if [[ "$(git -C "$UPSTREAM" rev-parse HEAD)" != "$UPSTREAM_COMMIT" ]]; then
    echo "Unexpected 3x-ui source commit; use the pinned v3.8.5 checkout." >&2
    exit 1
fi
if git -C "$UPSTREAM" apply --check "$PROJECT_ROOT/patches/3x-ui-android.patch" 2>/dev/null; then
    git -C "$UPSTREAM" apply "$PROJECT_ROOT/patches/3x-ui-android.patch"
elif ! git -C "$UPSTREAM" apply --reverse --check "$PROJECT_ROOT/patches/3x-ui-android.patch" 2>/dev/null; then
    echo "Source does not match the Android patch; preserve changes and restore a clean pinned checkout." >&2
    exit 1
fi

if [[ ! -d "$XRAY_SOURCE/.git" ]]; then
    mkdir -p "$(dirname "$XRAY_SOURCE")"
    git clone --branch "$XRAY_VERSION" --depth 1 https://github.com/XTLS/Xray-core.git "$XRAY_SOURCE"
fi
if [[ "$(git -C "$XRAY_SOURCE" rev-parse HEAD)" != "$XRAY_COMMIT" ]]; then
    echo "Unexpected Xray source commit; use the pinned v26.6.27 checkout." >&2
    exit 1
fi
if git -C "$XRAY_SOURCE" apply --check "$PROJECT_ROOT/patches/xray-android-network.patch" 2>/dev/null; then
    git -C "$XRAY_SOURCE" apply "$PROJECT_ROOT/patches/xray-android-network.patch"
elif ! git -C "$XRAY_SOURCE" apply --reverse --check "$PROJECT_ROOT/patches/xray-android-network.patch" 2>/dev/null; then
    echo "Source does not match the Android network patch; preserve changes and restore a clean pinned checkout." >&2
    exit 1
fi

case "$(uname -s)-$(uname -m)" in
    Darwin-*) NDK_HOST="darwin-x86_64" ;;
    Linux-x86_64) NDK_HOST="linux-x86_64" ;;
    *) echo "Unsupported NDK build host." >&2; exit 1 ;;
esac
CC_PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST/bin/aarch64-linux-android26-clang"
if [[ ! -x "$CC_PATH" ]]; then
    echo "Android NDK compiler not found: $CC_PATH" >&2
    exit 1
fi
node -e 'if (Number(process.versions.node.split(".")[0]) < 24) process.exit(1)'
go version
mkdir -p "$CACHE_DIR" "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a" "$PROJECT_ROOT/app/src/main/assets/core"
ZIP="$CACHE_DIR/Xray-android-arm64-v8a.zip"
DGST="$ZIP.dgst"
URL="https://github.com/XTLS/Xray-core/releases/download/$XRAY_VERSION/Xray-android-arm64-v8a.zip"
[[ -f "$ZIP" ]] || curl --fail --location --retry 3 "$URL" --output "$ZIP"
[[ -f "$DGST" ]] || curl --fail --location --retry 3 "$URL.dgst" --output "$DGST"
python3 "$PROJECT_ROOT/scripts/extract-xray.py" "$ZIP" "$DGST" "$XRAY_SHA256" "$XRAY_DGST_SHA256" "$PROJECT_ROOT/app/src/main"
(
    cd "$XRAY_SOURCE"
    go mod verify
    CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC_PATH" \
        CGO_LDFLAGS="${CGO_LDFLAGS:-} -Wl,-z,max-page-size=16384" \
        go build -mod=readonly -trimpath -buildvcs=false -buildmode=pie \
        -ldflags="-s -w -buildid= -checklinkname=0 -X github.com/xtls/xray-core/core.build=${XRAY_COMMIT:0:7}-android-network" \
        -o "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a/libxray.so" ./main
)
mkdir -p "$PROJECT_ROOT/app/src/main/assets/licenses/3x-ui"
cp "$UPSTREAM/LICENSE" "$PROJECT_ROOT/app/src/main/assets/licenses/3x-ui/LICENSE"
(
    cd "$UPSTREAM/frontend"
    npm ci --ignore-scripts --no-audit --no-fund
    npm run build
)
(
    cd "$UPSTREAM"
    CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC_PATH" \
        CGO_LDFLAGS="${CGO_LDFLAGS:-} -Wl,-z,max-page-size=16384" \
        go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' \
        -o "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a/libxui.so" .
)
echo "Built Android arm64 3x-ui v3.8.5 and Xray $XRAY_VERSION with Android network binding."
