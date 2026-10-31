#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The desktop window as a package installs it: njmule-gui tells its version, opens on a profile of its own with the
# networks off and starts njmule beside it, whose control endpoint answers; closed, it takes the engine with it.
# release.yml runs it on the files it packs (under xvfb-run on Linux); on failure it prints the engine's log.
#   desktop-smoke-test.sh <folder with njmule-gui and njmule>
set -euo pipefail

folder=$1
window="$folder/njmule-gui"

"$window" --version | grep '^njMule '

data=$(mktemp -d)
mkdir -p "$data/config"
printf 'net.ed2k.enabled=false\nnet.kad.enabled=false\nnet.upnp=false\nupdate.mode=OFF\n' > "$data/config/njmule.properties"

started() {
    grep -qs "Control endpoint for the desktop app" "$data"/logs/*.log
}

"$window" --config-dir "$data" &
gui=$!
for _ in $(seq 1 120); do
    started && break
    sleep 1
done
if ! started; then
    tail -n 40 "$data"/logs/*.log 2> /dev/null || true
    kill "$gui" 2> /dev/null || true
    echo "the desktop window did not start the engine" >&2
    exit 1
fi
echo "The window started the engine"

# Closed, its input pipe to the engine closes too: the engine saves and stops.
kill "$gui"
wait "$gui" 2> /dev/null || true
for _ in $(seq 1 60); do
    if ! pgrep -f -- "--config-dir $data" > /dev/null; then
        echo "The engine stopped with the window"
        rm -rf "$data"
        exit 0
    fi
    sleep 1
done
echo "the engine did not stop with the window" >&2
exit 1
