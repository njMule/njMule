#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the Linux x64 native executable inside the GraalVM container. Maven runs on a copy of the
# sources in the container's own file system: on Docker Desktop (Windows, macOS) building on the
# bind-mounted checkout is several times slower.
#
# Usage, from the repository root (extra arguments go to Maven):
#   docker run --rm -v "$PWD:/work" -v "$HOME/.m2:/root/.m2" --entrypoint bash \
#       ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1 /work/tools/native-docker.sh
set -euo pipefail

src=/work
build=$(mktemp -d)
tar -C "$src" --exclude=./.git --exclude=./docs --exclude=./.local --exclude=./fixtures-private \
    --exclude=target -cf - . | tar -C "$build" -xf -
cd "$build"
./mvnw -B -ntp -Pnative -pl njmule-app -am package -DskipTests "$@"
mkdir -p "$src/njmule-app/target"
cp njmule-app/target/njmule "$src/njmule-app/target/njmule"
echo "Native executable: njmule-app/target/njmule"
