#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the headless launcher as a fully static Linux x64 executable, linked with musl instead of
# the system's glibc, inside the GraalVM container: it runs on any x64 Linux, Synology DSM (glibc
# 2.26) included. The musl toolchain (with zlib) is the one GraalVM documents, checked against its
# SHA-256 and kept in the Docker volume njmule-musl. Like native-docker.sh, Gradle runs on a copy
# of the sources. Result: njmule-app/build/native-static/njmule.
#
# Usage, from the repository root (extra arguments go to Gradle):
#   docker run --rm -v "$PWD:/work" -v njmule-gradle:/root/.gradle -v njmule-musl:/musl \
#       --entrypoint bash ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1 \
#       /work/tools/native-static-docker.sh
set -euo pipefail

toolchain=musl-toolchain-1.2.5-oracle-00001-linux-amd64.tar.gz
sha256=77a60e1d31303f214e1c9d8e5843abcce2743a0c6f6b436e251f69a3161801ad
cache=/musl
mkdir -p "$cache"
if ! echo "$sha256  $cache/$toolchain" | sha256sum --check --status 2>/dev/null; then
    curl -fsSL -o "$cache/$toolchain" "https://gds.oracle.com/download/bfs/archive/$toolchain"
    echo "$sha256  $cache/$toolchain" | sha256sum --check
fi
musl=$(mktemp -d)
tar -C "$musl" -xzf "$cache/$toolchain"
export PATH="$musl/musl-toolchain/bin:$PATH"

src=/work
build=$(mktemp -d)
tar -C "$src" --exclude=./.git --exclude=./docs --exclude=./.local --exclude=./fixtures-private \
    --exclude=build --exclude=.gradle --exclude=.kotlin -cf - . | tar -C "$build" -xf -
cd "$build"
./gradlew --no-daemon --console=plain :njmule-app:nativeCompile -Pnative.static=musl "$@"
out="$src/njmule-app/build/native-static"
mkdir -p "$out"
cp njmule-app/build/native/nativeCompile/njmule "$out/njmule"
if ldd "$out/njmule" > /dev/null 2>&1; then
    echo "njmule is not static" >&2
    exit 1
fi
"$out/njmule" --version
echo "Static executable: njmule-app/build/native-static/njmule"
