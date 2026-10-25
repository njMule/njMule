#!/bin/sh
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Lets the desktop forget the entry and icons, then, at a removal rather than an upgrade (deb: remove or purge;
# rpm: none left), tells where each user's profile stays: a package touches no home folder.
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q /usr/share/applications || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
fi
case "$1" in
    remove | purge | 0)
        echo "njMule is removed. Each user's profile stays in their home: ~/.config/njmule, ~/.local/share/njmule,"
        echo "~/.local/state/njmule and ~/.cache/njmule (the downloads in ~/Downloads/njMule stay too)."
        echo "Delete those folders to remove it; before uninstalling, njmule --remove-profile does it."
        ;;
esac
exit 0
