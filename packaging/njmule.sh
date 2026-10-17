#!/bin/sh
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# njmule, the command line of the Linux and macOS desktop packages: the desktop app's executable,
# found beside this script (or beside what it links to), runs without a window.
app="$(dirname "$(readlink -f "$0")")/njmule-gui"
for arg in "$@"; do
    if [ "$arg" = --headless ]; then
        exec "$app" "$@"
    fi
done
exec "$app" --headless "$@"
