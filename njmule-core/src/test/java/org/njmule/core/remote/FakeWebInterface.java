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

import java.util.List;
import java.util.Optional;

/** Web interface settings kept in memory, with a password, off, on port 4711; turned on only with a password. */
final class FakeWebInterface implements WebInterface {

    private volatile Settings kept =
            new Settings(false, 4711, false, List.of(), List.of(), false, true, false, Optional.empty());

    /** Takes the password away, as before one was ever set. */
    void forget() {
        Settings s = this.kept;
        this.kept =
                new Settings(s.enabled(), s.port(), s.lan(), s.hosts(), s.proxies(), s.api(), false, false, s.url());
    }

    @Override
    public Settings settings() {
        return this.kept;
    }

    @Override
    public Settings change(Change change) {
        Settings now = this.kept;
        boolean password = now.hasPassword() || change.password().isPresent();
        boolean enabled = change.enabled().orElse(now.enabled());
        if (enabled && !password) {
            throw new IllegalArgumentException("the web interface needs a password");
        }
        int port = change.port().orElse(now.port());
        this.kept = new Settings(
                enabled,
                port,
                change.lan().orElse(now.lan()),
                change.hosts().orElse(now.hosts()),
                change.proxies().orElse(now.proxies()),
                change.api().orElse(now.api()),
                password,
                change.guestPassword().map(g -> !g.isEmpty()).orElse(now.hasGuest()),
                enabled ? Optional.of("http://127.0.0.1:" + port + "/") : Optional.empty());
        return this.kept;
    }
}
