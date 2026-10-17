/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package org.njmule.core.api;

import org.njmule.core.files.FileTypes;

/** What a file holds, as its extension tells: the eD2K types, with archives and disc images apart from programs. */
public enum FileKind {
    VIDEO,
    AUDIO,
    IMAGE,
    DOCUMENT,
    ARCHIVE,
    DISC_IMAGE,
    PROGRAM,
    OTHER;

    /** The kind of a file named {@code name}; {@link #OTHER} when its extension does not tell. */
    public static FileKind of(String name) {
        return FileTypes.of(name)
                .map(type -> switch (type) {
                    case FileTypes.VIDEO -> VIDEO;
                    case FileTypes.AUDIO -> AUDIO;
                    case FileTypes.IMAGE -> IMAGE;
                    case FileTypes.DOCUMENT -> DOCUMENT;
                    default ->
                        FileTypes.isA(FileTypes.ARCHIVE, name)
                                ? ARCHIVE
                                : FileTypes.isA(FileTypes.DISC_IMAGE, name) ? DISC_IMAGE : PROGRAM;
                })
                .orElse(OTHER);
    }
}
