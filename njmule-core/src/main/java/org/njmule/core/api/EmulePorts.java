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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.njmule.core.persist.format.IniFile;

/**
 * The ports an eMule profile listens on, read from its {@code preferences.ini} (only read, never written), so that
 * njMule can take others and both can run on one computer.
 *
 * @param tcp the client TCP port
 * @param udp the client UDP port, empty when eMule's UDP is off
 */
public record EmulePorts(int tcp, OptionalInt udp) {

    private static final String SECTION = "eMule";
    private static final int MAX_PORT = 65_535;

    /** The ports of the eMule profile in {@code configDir}, empty when it has no readable TCP port. */
    public static Optional<EmulePorts> read(Path configDir) {
        Path file = configDir.resolve("preferences.ini");
        IniFile ini;
        try {
            ini = IniFile.parse(Files.readAllBytes(file));
        } catch (IOException e) {
            return Optional.empty();
        }
        OptionalInt tcp = port(ini.getLong(SECTION, "Port"));
        if (tcp.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EmulePorts(tcp.getAsInt(), port(ini.getLong(SECTION, "UDPPort"))));
    }

    /** Whether eMule listens on {@code port}, over TCP or UDP. */
    public boolean uses(int port) {
        return this.tcp == port || (this.udp.isPresent() && this.udp.getAsInt() == port);
    }

    private static OptionalInt port(OptionalLong value) {
        return value.isPresent() && value.getAsLong() > 0 && value.getAsLong() <= MAX_PORT
                ? OptionalInt.of((int) value.getAsLong())
                : OptionalInt.empty();
    }
}
