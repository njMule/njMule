#!/bin/sh
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# At a removal rather than an upgrade (deb: remove; rpm: none left), every njMule still running from the package,
# whoever runs it, saves and stops first. SIGTERM ends a desktop window, whose engine then saves and stops as its
# input closes, and has an engine without a window save and exit; each gets the minute a systemd stop gives it.
case "$1" in
    remove | 0) ;;
    *) exit 0 ;;
esac

dir=/usr/lib/njmule

# The program the process $1 runs, without the mark of a file an upgrade replaced.
program() {
    target=$(readlink "/proc/$1/exe" 2> /dev/null) || return 1
    echo "${target% (deleted)}"
}

windows=""
engines=""
signalled=""
for proc in /proc/[0-9]*; do
    pid=${proc#/proc/}
    case "$(program "$pid")" in
        "$dir/njmule-gui") windows="$windows $pid" ;;
        "$dir/njmule") engines="$engines $pid" ;;
    esac
done
signalled=$windows
# The engine of a window stops with it: signalled too, it would stop twice at once.
for pid in $engines; do
    parent=$(sed -n 's/^PPid:[[:space:]]*//p' "/proc/$pid/status" 2> /dev/null)
    if [ "$(program "$parent")" != "$dir/njmule-gui" ]; then
        signalled="$signalled $pid"
    fi
done
[ -n "$windows$engines" ] || exit 0

echo "Stopping njMule, which saves its state first..."
for pid in $signalled; do
    kill -TERM "$pid" 2> /dev/null || true
done
waited=0
while [ "$waited" -lt 60 ]; do
    left=""
    for pid in $windows $engines; do
        if kill -0 "$pid" 2> /dev/null; then
            left="$left $pid"
        fi
    done
    [ -n "$left" ] || exit 0
    sleep 1
    waited=$((waited + 1))
done
echo "njMule still runs (process$left); it goes on until it stops."
exit 0
