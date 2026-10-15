#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the Linux x64 native executable inside the GraalVM container. Gradle runs on a copy of the
# sources in the container's own file system: on Docker Desktop (Windows, macOS) building on the
# bind-mounted checkout is several times slower. Its user home is a Docker volume (njmule-gradle):
# sharing the host's ~/.gradle across operating systems would mix their file locks.
# Result: njmule-app/build/native-linux/njmule.
#
# Usage, from the repository root (extra arguments go to Gradle):
#   docker run --rm -v "$PWD:/work" -v njmule-gradle:/root/.gradle --entrypoint bash \
#       ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1 /work/tools/native-docker.sh
set -euo pipefail

src=/work
build=$(mktemp -d)
tar -C "$src" --exclude=./.git --exclude=./docs --exclude=./.local --exclude=./fixtures-private \
    --exclude=build --exclude=.gradle --exclude=.kotlin -cf - . | tar -C "$build" -xf -
cd "$build"
./gradlew --no-daemon --console=plain :njmule-app:nativeCompile "$@"
mkdir -p "$src/njmule-app/build/native-linux"
cp njmule-app/build/native/nativeCompile/njmule "$src/njmule-app/build/native-linux/njmule"
echo "Native executable: njmule-app/build/native-linux/njmule"
