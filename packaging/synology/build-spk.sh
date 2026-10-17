#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the Synology DSM 7 package (.spk) around a static Linux executable of the headless
# launcher (tools/native-static-docker.sh). Run on Linux (or in a Linux container), so that the
# archives keep the scripts' modes. DSM versions are numbers only: the version of
# gradle.properties without its pre-release, then a build number that grows with each package
# (SPK_BUILD, by default the number of commits).
#
# Usage: packaging/synology/build-spk.sh <njmule executable> <x86_64|armv8> [<output dir>]
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
binary=$1
arch=$2
out=${3:-$root/build/synology}
case "$arch" in
    x86_64 | armv8) ;;
    *) echo "unknown arch $arch: x86_64 or armv8" >&2; exit 2 ;;
esac

semver=$(sed -n 's/^version=//p' "$root/gradle.properties")
build=${SPK_BUILD:-$(git -C "$root" rev-list --count HEAD)}
version="${semver%%-*}-$build"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
pkg=$work/package
spk=$work/spk
mkdir -p "$pkg/bin" "$pkg/app/images" "$pkg/doc" "$spk"

install -m 755 "$binary" "$pkg/bin/njmule"
install -m 644 "$here/app/config" "$here/app/njmule.sc" "$pkg/app/"
for size in 16 24 32 48 64 72 256; do
    install -m 644 "$root/branding/generated/njmule-$size.png" "$pkg/app/images/njmule-$size.png"
done
# The executable holds musl and zlib, linked statically: their licenses go with it.
install -m 644 "$root/LICENSE" "$root/NOTICE" "$here/licenses/musl-COPYRIGHT" \
    "$here/licenses/zlib-LICENSE" "$pkg/doc/"
# xz despite the name, as Synology's own package toolkit makes it: a third smaller than gzip.
tar -C "$pkg" --owner=0 --group=0 --sort=name -cJf "$spk/package.tgz" bin app doc

cp -r "$here/scripts" "$here/conf" "$here/WIZARD_UIFILES" "$spk/"
chmod 755 "$spk"/scripts/*
chmod 644 "$spk"/conf/* "$spk"/WIZARD_UIFILES/*
install -m 644 "$root/LICENSE" "$spk/LICENSE"
install -m 644 "$root/branding/generated/njmule-64.png" "$spk/PACKAGE_ICON.PNG"
install -m 644 "$root/branding/generated/njmule-256.png" "$spk/PACKAGE_ICON_256.PNG"

cat > "$spk/INFO" << EOF
package="njmule"
version="$version"
os_min_ver="7.0-40000"
arch="$arch"
displayname="njMule"
description="eD2K and Kad client compatible with eMule, used from its web interface: search, download and share on the eMule network."
description_ita="Client eD2K e Kad compatibile con eMule, da usare dalla sua interfaccia web: cerca, scarica e condividi sulla rete eMule."
maintainer="njMule"
maintainer_url="https://github.com/njMule/njMule"
support_url="https://github.com/njMule/njMule/issues"
adminprotocol="http"
adminport="4711"
dsmuidir="app"
dsmappname="org.njmule.webui"
checksum="$(md5sum "$spk/package.tgz" | cut -d ' ' -f 1)"
EOF
chmod 644 "$spk/INFO"

mkdir -p "$out"
file="$out/njmule-$version-$arch.spk"
tar -C "$spk" --owner=0 --group=0 --sort=name -cf "$file" \
    INFO package.tgz scripts conf WIZARD_UIFILES LICENSE PACKAGE_ICON.PNG PACKAGE_ICON_256.PNG
echo "Package: $file"
