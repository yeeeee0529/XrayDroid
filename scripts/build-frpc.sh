#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRP_VERSION="v0.71.0"
FRP_COMMIT="4a23aa181c1d7e28eecaa8216024ed753b9d27c8"
FRP_SOURCE="$PROJECT_ROOT/upstream/frp"
CACHE_DIR="$PROJECT_ROOT/.core-cache"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/homebrew/share/android-ndk}"

if [[ ! -d "$FRP_SOURCE/.git" ]]; then
    mkdir -p "$(dirname "$FRP_SOURCE")"
    git clone --branch "$FRP_VERSION" --depth 1 https://github.com/fatedier/frp.git "$FRP_SOURCE"
fi
if [[ "$(git -C "$FRP_SOURCE" rev-parse HEAD)" != "$FRP_COMMIT" ]]; then
    echo "Unexpected frp source commit; use the pinned v0.71.0 checkout." >&2
    exit 1
fi
if git -C "$FRP_SOURCE" apply --check "$PROJECT_ROOT/patches/frp-android.patch" 2>/dev/null; then
    git -C "$FRP_SOURCE" apply "$PROJECT_ROOT/patches/frp-android.patch"
elif ! git -C "$FRP_SOURCE" apply --reverse --check "$PROJECT_ROOT/patches/frp-android.patch" 2>/dev/null; then
    echo "Source does not match the Android frpc patch; preserve changes and restore a clean pinned checkout." >&2
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
mkdir -p "$CACHE_DIR" "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a" "$PROJECT_ROOT/app/src/main/assets/licenses/frp"
cp "$FRP_SOURCE/LICENSE" "$PROJECT_ROOT/app/src/main/assets/licenses/frp/LICENSE"
(
    cd "$FRP_SOURCE/web"
    npm ci --ignore-scripts --no-audit --no-fund
    npm run build --workspace frpc
    npm run build --workspace frps
)
(
    cd "$FRP_SOURCE"
    go mod verify
    CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$CC_PATH" \
        CGO_LDFLAGS="${CGO_LDFLAGS:-} -Wl,-z,max-page-size=16384" \
        go build -mod=readonly -trimpath -buildvcs=false -buildmode=pie -tags frpc \
        -ldflags='-s -w -buildid=' \
        -o "$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a/libfrpc.so" ./cmd/frpc
    CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false -tags frpc \
        -o "$CACHE_DIR/frpc" ./cmd/frpc
    CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false -tags frps \
        -o "$CACHE_DIR/frps" ./cmd/frps
)
echo "Built Android arm64 frpc $FRP_VERSION with full upstream dashboard and host validation tools."
