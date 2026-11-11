#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM_VERSION="v3.11.0"
UPSTREAM="$PROJECT_ROOT/upstream/3x-ui-$UPSTREAM_VERSION"
UPSTREAM_COMMIT="c621ba9014a6aa21fd7c9ecf45655e44e24ebf13"
XRAY_VERSION="v26.11.9"
XRAY_SOURCE="$PROJECT_ROOT/upstream/Xray-core-$XRAY_VERSION"
XRAY_COMMIT="676eaf66f8f05d4d558ee7a74b4736623d3feeb9"
XRAY_SHA256="edcefc25b9b3195b11e18624c8269a969d6aabc39c6298a9a5ab29de15d46f3b"
XRAY_DGST_SHA256="e6b8fb07f1eceb36641badf845bcf880cdd93d21802f662be6aa922e01933e16"
CACHE_DIR="$PROJECT_ROOT/.core-cache"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-ndk}"

if [[ ! -d "$UPSTREAM/.git" ]]; then
    mkdir -p "$(dirname "$UPSTREAM")"
    git clone --branch "$UPSTREAM_VERSION" --depth 1 https://github.com/MHSanaei/3x-ui.git "$UPSTREAM"
fi
if [[ "$(git -C "$UPSTREAM" rev-parse HEAD)" != "$UPSTREAM_COMMIT" ]]; then
    echo "Unexpected 3x-ui source commit; use the pinned $UPSTREAM_VERSION checkout." >&2
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
    echo "Unexpected Xray source commit; use the pinned $XRAY_VERSION checkout." >&2
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
node -e 'if (Number(process.versions.node.split(".")[0]) < 26) process.exit(1)'
go version
mkdir -p "$CACHE_DIR" "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a" "$PROJECT_ROOT/app/src/main/assets/core"
ZIP="$CACHE_DIR/Xray-$XRAY_VERSION-android-arm64-v8a.zip"
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
"$PROJECT_ROOT/scripts/build-frpc.sh"
echo "Built Android arm64 3x-ui $UPSTREAM_VERSION, Xray $XRAY_VERSION with Android network binding, and frpc v0.72.0."
