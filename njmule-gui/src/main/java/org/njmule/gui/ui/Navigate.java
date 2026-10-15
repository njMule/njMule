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
package org.njmule.gui.ui;

import java.io.Serial;
import java.util.Objects;
import javafx.event.Event;
import javafx.event.EventType;

/** Asks the window to show another screen, by its id: a screen's button fires it and the window answers. */
public final class Navigate extends Event {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final EventType<Navigate> TO_SCREEN = new EventType<>(Event.ANY, "NAVIGATE_TO_SCREEN");

    private final String screen;

    public Navigate(String screen) {
        super(TO_SCREEN);
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    public String screen() {
        return this.screen;
    }
}
