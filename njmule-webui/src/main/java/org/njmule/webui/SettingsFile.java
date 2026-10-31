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
package org.njmule.webui;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.njmule.core.remote.WebInterface;

/** The web interface's settings in {@value WebUiSettings#FILE_NAME} of the config directory, as the API sees them. */
final class SettingsFile implements WebInterface {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LOOPBACK = "127.0.0.1";

    // Asked at each call: an engine gives its directories once it has started.
    private final Supplier<Path> configDir;
    private final Consumer<WebUiSettings> saved;
    private final Object changing = new Object();

    SettingsFile(Supplier<Path> configDir) {
        this(configDir, settings -> {});
    }

    /** As above, handing {@code saved} the settings each change leaves. */
    SettingsFile(Supplier<Path> configDir, Consumer<WebUiSettings> saved) {
        this.configDir = configDir;
        this.saved = saved;
    }

    @Override
    public Settings settings() {
        return view(WebUiSettings.load(this.configDir.get()));
    }

    @Override
    public Settings change(Change change) throws IOException {
        synchronized (this.changing) {
            Path dir = this.configDir.get();
            WebUiSettings now = WebUiSettings.load(dir);
            String admin = change.password().map(SettingsFile::hash).orElse(now.passwordHash());
            String guest = change.guestPassword()
                    .map(p -> p.isEmpty() ? "" : guestHash(p, admin))
                    .orElse(now.guestPasswordHash());
            WebUiSettings next = new WebUiSettings(
                    change.enabled().orElse(now.enabled()),
                    change.port().orElse(now.port()),
                    change.lan().orElse(now.lan()),
                    admin,
                    change.api().orElse(now.api()),
                    change.hosts().orElse(now.hosts()),
                    change.proxies().orElse(now.proxies()),
                    guest);
            if (next.enabled() && next.passwordHash().isEmpty()) {
                throw new IllegalArgumentException("the web interface needs a password");
            }
            next.save(dir);
            this.saved.accept(next);
            return view(next);
        }
    }

    private static String hash(String text) {
        char[] password = text.toCharArray();
        try {
            if (password.length < Passwords.MIN_LENGTH) {
                throw new IllegalArgumentException(
                        "the password needs at least " + Passwords.MIN_LENGTH + " characters");
            }
            return Passwords.hash(password, RANDOM);
        } finally {
            Passwords.wipe(password);
        }
    }

    // A guest's password may not be the admin's, who would log in as a guest otherwise.
    private static String guestHash(String text, String admin) {
        char[] password = text.toCharArray();
        try {
            if (!admin.isEmpty() && Passwords.matches(password, admin)) {
                throw new IllegalArgumentException("the guest password must differ from yours");
            }
        } finally {
            Passwords.wipe(password);
        }
        return hash(text);
    }

    private static Settings view(WebUiSettings s) {
        Optional<String> url = Optional.empty();
        if (s.usable()) {
            String host = s.lan() ? lanAddress().orElse(LOOPBACK) : LOOPBACK;
            url = Optional.of("http://" + host + ":" + s.port() + "/");
        }
        return new Settings(
                s.enabled(),
                s.port(),
                s.lan(),
                s.hosts(),
                s.proxies(),
                s.api(),
                !s.passwordHash().isEmpty(),
                s.hasGuest(),
                url);
    }

    // The address of this computer on its LAN: the one a datagram to a public address would leave from (none is sent).
    private static Optional<String> lanAddress() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(new InetSocketAddress(InetAddress.ofLiteral("192.0.2.1"), 9));
            InetAddress local = probe.getLocalAddress();
            return local.isAnyLocalAddress() ? Optional.empty() : Optional.of(local.getHostAddress());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
