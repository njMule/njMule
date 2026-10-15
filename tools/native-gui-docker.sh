#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Linux x64 native executables of the desktop app and the launcher, in an Ubuntu container (as on
# the CI runners) with GTK and a virtual display. The GUI tour first runs on the JVM under the
# tracing agent and merges the Linux metadata into the checkout; the native app then takes the
# same tour as a smoke test. Results: njmule-gui/build/native-linux/ and
# njmule-app/build/native-linux/njmule. Gradle's user home is a Docker volume (njmule-gradle).
#
# Usage, from the repository root, with the GraalVM Community Linux x64 archive (the release used
# in CI) saved as <dir>/graalvm.tar.gz:
#   docker run --rm -v "$PWD:/work" -v njmule-gradle:/root/.gradle -v "<dir>:/graalvm:ro" \
#       ubuntu:24.04 bash /work/tools/native-gui-docker.sh
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends build-essential zlib1g-dev xvfb libgtk-3-0t64 \
    libxtst6 libgl1 libglx-mesa0 libgl1-mesa-dri fonts-dejavu-core > /dev/null
mkdir -p /opt/graalvm
tar -C /opt/graalvm --strip-components=1 -xzf /graalvm/graalvm.tar.gz
export JAVA_HOME=/opt/graalvm GRAALVM_HOME=/opt/graalvm PATH="/opt/graalvm/bin:$PATH"
Xvfb :99 -screen 0 1280x800x24 > /dev/null 2>&1 &
export DISPLAY=:99

src=/work
build=$(mktemp -d)
tar -C "$src" --exclude=./.git --exclude=./docs --exclude=./.local --exclude=./fixtures-private \
    --exclude=build --exclude=.gradle --exclude=.kotlin -cf - . | tar -C "$build" -xf -
cd "$build"
metadata=njmule-gui/src/main/resources/META-INF/native-image/org.njmule/njmule-gui/tour
./gradlew --no-daemon --console=plain :njmule-gui:nativeTour
cp "$metadata/reachability-metadata.json" "$src/$metadata/"
./gradlew --no-daemon --console=plain :njmule-app:nativeCompile :njmule-gui:nativeGui

tour=$(mktemp -d)
cp -r njmule-gui/native/tour/. "$tour/"
njmule-gui/build/native/njmule-gui -Dnjmule.gui.tour=true --config-dir "$tour"
grep -q "Tour finished" "$tour"/logs/*.log
echo "The native desktop app took the tour"

mkdir -p "$src/njmule-gui/build/native-linux" "$src/njmule-app/build/native-linux"
cp -r njmule-gui/build/native/. "$src/njmule-gui/build/native-linux/"
cp njmule-app/build/native/nativeCompile/njmule "$src/njmule-app/build/native-linux/njmule"
