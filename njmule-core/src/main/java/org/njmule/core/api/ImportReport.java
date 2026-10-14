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

import java.util.List;
import java.util.Objects;

/**
 * What an import of an eMule profile did, file by file.
 *
 * @param startAtLogin eMule starts when the user logs in: the desktop app does the same after the import
 */
public record ImportReport(boolean dryRun, List<Item> items, boolean startAtLogin) {

    /** The outcome for one file. */
    public enum Outcome {
        /** Taken into njMule. */
        IMPORTED,
        /** Would be taken, in a dry run. */
        WOULD_IMPORT,
        /** Left alone, for the reason given. */
        SKIPPED,
        /** Not in the profile. */
        MISSING,
        /** Unreadable or refused. */
        FAILED
    }

    /** One file of the profile and what happened to it. */
    public record Item(String file, Outcome outcome, String detail) {
        public Item {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public ImportReport {
        items = List.copyOf(items);
    }

    /** A report of a profile that does not start at login. */
    public ImportReport(boolean dryRun, List<Item> items) {
        this(dryRun, items, false);
    }
}
