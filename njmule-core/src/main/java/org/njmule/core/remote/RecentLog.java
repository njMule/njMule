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
package org.njmule.core.remote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.njmule.core.api.LogLine;

/** The latest lines of the engine's log, numbered so that a caller asks only for those it has not seen. */
public final class RecentLog {

    public static final int KEPT = 500;

    public record Entry(long sequence, LogLine line) {}

    private final ArrayDeque<Entry> lines = new ArrayDeque<>();
    private long next = 1;

    public synchronized void add(LogLine line) {
        this.lines.addLast(new Entry(this.next++, line));
        if (this.lines.size() > KEPT) {
            this.lines.removeFirst();
        }
    }

    /** The lines numbered above {@code sequence}, oldest first. */
    public synchronized List<Entry> after(long sequence) {
        List<Entry> newer = new ArrayList<>();
        for (Entry e : this.lines) {
            if (e.sequence() > sequence) {
                newer.add(e);
            }
        }
        return newer;
    }
}
