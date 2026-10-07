#!/usr/bin/env bash
# Build the avpnp_tun helper as a static aarch64 binary and place it where AGP picks it up as a
# native library. We do not use the NDK: a static binary is enough, and it is what lets the helper
# run under `su` without depending on bionic.
#
# Requires: aarch64-linux-gnu-gcc (or set CC=).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
out_dir="$root/app/src/main/jniLibs/arm64-v8a"
out="$out_dir/libavpnp_tun.so"

CC="${CC:-aarch64-linux-gnu-gcc}"
if ! command -v "$CC" >/dev/null 2>&1; then
    echo "error: $CC not found; install aarch64 cross toolchain or set CC=" >&2
    exit 1
fi

mkdir -p "$out_dir"
"$CC" -static -O2 -Wall -Wextra -o "$out" "$here/avpnp_tun.c"
chmod 755 "$out"

echo "built $out"
file "$out" 2>/dev/null || true
