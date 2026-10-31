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

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * What the desktop app sets in the system of njMule's computer: whether njMule opens ed2k links from browsers and other
 * programs, and whether it starts when the user logs in. Only the desktop window on that computer reaches it.
 */
public interface DesktopIntegration {

    /** The settings as the system has them now. */
    Settings settings() throws IOException;

    /**
     * Applies what {@code change} names and answers the settings as they are then.
     *
     * @throws IllegalArgumentException when it names a setting this system does not offer; nothing changes then
     */
    Settings change(Change change) throws IOException;

    /**
     * Each setting, empty where this system or this installation does not offer it.
     *
     * @param links whether njMule opens ed2k links
     * @param autostart whether njMule starts when the user logs in
     */
    record Settings(Optional<Boolean> links, Optional<Boolean> autostart) {
        public Settings {
            Objects.requireNonNull(links, "links");
            Objects.requireNonNull(autostart, "autostart");
        }
    }

    /** What to change; what is empty stays. */
    record Change(Optional<Boolean> links, Optional<Boolean> autostart) {
        public Change {
            Objects.requireNonNull(links, "links");
            Objects.requireNonNull(autostart, "autostart");
        }
    }
}
