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
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.njmule.core.remote.DesktopIntegration;

/** The eD2K links and the start at login of this computer, as the desktop window on it sets them through the API. */
final class DesktopSystem implements DesktopIntegration {

    /** A setting of the system, on or off. */
    interface Toggle {
        boolean isOn() throws IOException;

        void set(boolean on) throws IOException;
    }

    private final Optional<Toggle> linkToggle;
    private final Optional<Toggle> startToggle;
    private final Object changing = new Object();

    DesktopSystem(Optional<Toggle> linkToggle, Optional<Toggle> startToggle) {
        this.linkToggle = Objects.requireNonNull(linkToggle, "linkToggle");
        this.startToggle = Objects.requireNonNull(startToggle, "startToggle");
    }

    /** This computer's, for the profile in {@code configDir}; a start at login passes {@code arguments}. */
    static DesktopSystem forThisSystem(Path configDir, List<String> arguments) {
        return new DesktopSystem(
                LinkAssociation.forThisSystem(configDir).map(DesktopSystem::ofLinks),
                Autostart.forThisSystem(configDir, arguments).map(DesktopSystem::ofStart));
    }

    private static Toggle ofLinks(LinkAssociation association) {
        return new Toggle() {
            @Override
            public boolean isOn() throws IOException {
                return association.isOurs();
            }

            // Given up, the links go back to the program that opened them before.
            @Override
            public void set(boolean on) throws IOException {
                if (on) {
                    association.take();
                } else {
                    association.giveBack();
                }
            }
        };
    }

    private static Toggle ofStart(Autostart autostart) {
        return new Toggle() {
            @Override
            public boolean isOn() throws IOException {
                return autostart.isOn();
            }

            @Override
            public void set(boolean on) throws IOException {
                if (on) {
                    autostart.turnOn();
                } else {
                    autostart.turnOff();
                }
            }
        };
    }

    @Override
    public Settings settings() throws IOException {
        synchronized (this.changing) {
            return new Settings(state(this.linkToggle), state(this.startToggle));
        }
    }

    @Override
    public Settings change(Change change) throws IOException {
        if (change.links().isPresent() && this.linkToggle.isEmpty()) {
            throw new IllegalArgumentException("this njMule cannot open ed2k links on this system");
        }
        if (change.autostart().isPresent() && this.startToggle.isEmpty()) {
            throw new IllegalArgumentException("this njMule cannot start at login on this system");
        }
        synchronized (this.changing) {
            if (change.links().isPresent()) {
                this.linkToggle.orElseThrow().set(change.links().get());
            }
            if (change.autostart().isPresent()) {
                this.startToggle.orElseThrow().set(change.autostart().get());
            }
            return settings();
        }
    }

    private static Optional<Boolean> state(Optional<Toggle> toggle) throws IOException {
        return toggle.isPresent() ? Optional.of(toggle.get().isOn()) : Optional.empty();
    }
}
