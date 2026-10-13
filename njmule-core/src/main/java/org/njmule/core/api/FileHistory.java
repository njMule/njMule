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

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The files we downloaded or cancelled before, as far as they are remembered: the known files, and the cancelled
 * downloads, which are kept under keys that do not list them.
 */
public final class FileHistory {

    public static final FileHistory NONE = new FileHistory(Set.of(), h -> false);

    private final Set<Hash16> downloaded;
    private final Predicate<Hash16> cancelled;

    private FileHistory(Set<Hash16> downloaded, Predicate<Hash16> cancelled) {
        this.downloaded = Set.copyOf(downloaded);
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
    }

    /** {@code cancelled} must not change afterwards. */
    public static FileHistory of(Set<Hash16> downloaded, Predicate<Hash16> cancelled) {
        return new FileHistory(downloaded, cancelled);
    }

    public boolean downloaded(Hash16 file) {
        return this.downloaded.contains(Objects.requireNonNull(file, "file"));
    }

    public boolean cancelled(Hash16 file) {
        return this.cancelled.test(Objects.requireNonNull(file, "file"));
    }
}
