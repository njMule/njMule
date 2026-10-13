#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Linux x64 native executables of the desktop app and the launcher, inside the GraalVM container
# with GTK and a virtual display. The GUI tour first runs on the JVM under the tracing agent and
# merges the Linux metadata into the checkout; the native app then takes the same tour as a smoke
# test. Results: njmule-gui/target/native-linux/ and njmule-app/target/njmule.
#
# Usage, from the repository root:
#   docker run --rm -v "$PWD:/work" -v "$HOME/.m2:/root/.m2" --entrypoint bash \
#       ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1 /work/tools/native-gui-docker.sh
set -euo pipefail

microdnf install -y --nodocs gtk3 libXtst xorg-x11-server-Xvfb mesa-dri-drivers dejavu-sans-fonts \
    > /dev/null
Xvfb :99 -screen 0 1280x800x24 > /dev/null 2>&1 &
export DISPLAY=:99

src=/work
build=$(mktemp -d)
tar -C "$src" --exclude=./.git --exclude=./docs --exclude=./.local --exclude=./fixtures-private \
    --exclude=target -cf - . | tar -C "$build" -xf -
cd "$build"
metadata=njmule-gui/src/main/resources/META-INF/native-image/org.njmule/njmule-gui/tour
./mvnw -B -ntp -Pnative-tour -pl njmule-gui -am package -DskipTests -Dgraalvm.home="$JAVA_HOME"
cp "$metadata/reachability-metadata.json" "$src/$metadata/"
./mvnw -B -ntp -Pnative -pl njmule-gui -am package -DskipTests -Dgraalvm.home="$JAVA_HOME"

tour=$(mktemp -d)
cp -r njmule-gui/native/tour/. "$tour/"
njmule-gui/target/native/njmule-gui -Dnjmule.gui.tour=true --config-dir "$tour"
grep -q "Tour finished" "$tour"/logs/*.log
echo "The native desktop app took the tour"

mkdir -p "$src/njmule-gui/target/native-linux" "$src/njmule-app/target"
cp -r njmule-gui/target/native/. "$src/njmule-gui/target/native-linux/"
cp njmule-app/target/njmule "$src/njmule-app/target/njmule"
