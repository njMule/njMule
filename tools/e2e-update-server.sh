#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The release server of the end-to-end test of the updates, on http://127.0.0.1:8765: GitHub's layout
# (the API's list of releases, then the update information of the tag and its packages) made of the
# packages in <dist> and the update information signed for the run in <signed>. A test build of njMule
# reaches it through update.url; it runs in the background until the job ends.
#   e2e-update-server.sh <dist> <signed> <version> <root>
set -euo pipefail

dist=$1
signed=$2
version=$3
root=$4
tag="v${version}"

mkdir -p "$root/api" "$root/releases/download/$tag" "$root/releases/latest/download"
cp "$dist"/* "$root/releases/download/$tag/"
for file in njmule-update.json njmule-update.json.minisig; do
    cp "$signed/$file" "$root/releases/download/$tag/"
    cp "$signed/$file" "$root/releases/latest/download/"
done
printf '[{"tag_name": "%s", "draft": false, "assets": [{"name": "njmule-update.json"}, {"name": "njmule-update.json.minisig"}]}]\n' \
    "$tag" > "$root/api/releases"

# The first Python that runs: on Windows python3 may only be the Store's placeholder.
python=""
for candidate in python3 python; do
    if "$candidate" -c "import http.server" > /dev/null 2>&1; then
        python=$candidate
        break
    fi
done
if [ -z "$python" ]; then
    echo "no Python to serve the release" >&2
    exit 1
fi
nohup "$python" -m http.server 8765 --bind 127.0.0.1 --directory "$root" > "$root.log" 2>&1 &
for _ in $(seq 1 50); do
    if curl -sf -o /dev/null "http://127.0.0.1:8765/api/releases"; then
        echo "Release server of ${tag} on http://127.0.0.1:8765"
        exit 0
    fi
    sleep 0.2
done
cat "$root.log" >&2
echo "the release server did not start" >&2
exit 1
