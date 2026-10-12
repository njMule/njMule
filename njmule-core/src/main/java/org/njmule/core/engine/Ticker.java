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
package org.njmule.core.engine;

/**
 * Monotonic clock for timeouts and schedules: immune to wall-clock changes and, unlike eMule's
 * 32-bit {@code GetTickCount}, it never wraps.
 */
@FunctionalInterface
public interface Ticker {

    long nanoTime();

    default long millis() {
        return this.nanoTime() / 1_000_000L;
    }

    static Ticker system() {
        return System::nanoTime;
    }
}
