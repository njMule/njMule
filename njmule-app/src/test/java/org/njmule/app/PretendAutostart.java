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
package org.njmule.app;

import java.io.IOException;

/** A start at login that only remembers whether it is on. */
final class PretendAutostart implements Autostart {
    private boolean on;
    // As a system that does not let it go.
    boolean refusesToTurnOff;

    PretendAutostart(boolean on) {
        this.on = on;
    }

    @Override
    public boolean isOn() {
        return this.on;
    }

    @Override
    public void turnOn() {
        this.on = true;
    }

    @Override
    public void turnOff() throws IOException {
        if (this.refusesToTurnOff) {
            throw new IOException("access denied");
        }
        this.on = false;
    }
}
