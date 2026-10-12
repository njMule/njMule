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
package org.njmule.testkit;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.njmule.core.engine.Ticker;

/** A {@link Ticker} that only moves when a test advances it. */
public final class ManualTicker implements Ticker {

    private final AtomicLong nanos;

    public ManualTicker(long startNanos) {
        this.nanos = new AtomicLong(startNanos);
    }

    public ManualTicker() {
        this(0L);
    }

    @Override
    public long nanoTime() {
        return this.nanos.get();
    }

    public ManualTicker advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("a monotonic clock cannot go back: " + amount);
        }
        this.nanos.addAndGet(amount.toNanos());
        return this;
    }
}
