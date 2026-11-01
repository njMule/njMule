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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import org.njmule.http.Passwords;
import org.njmule.http.WebUiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sets the web interface password of the command line, typed or read from a file such as a Docker secret. */
public final class WebUiPassword {

    /** A password file holds one line: anything longer is not one. */
    static final int MAX_FILE_BYTES = 4096;

    private static final Logger logger = LoggerFactory.getLogger(WebUiPassword.class);

    private WebUiPassword() {}

    /**
     * Sets the password of {@code --webui-password-file}, when the command line names one: with
     * {@code --webui-password} as a set-up of its own, otherwise as at each start, where the options the command line
     * does not give stay as the web interface saved them.
     *
     * @return whether it named one
     * @throws LaunchOptions.UsageException when the file cannot be read or holds no valid password
     * @throws IOException when the settings cannot be saved
     */
    public static boolean setFromFile(LaunchOptions cli, Path config) throws LaunchOptions.UsageException, IOException {
        Optional<LaunchOptions.WebUiSetup> setup = cli.webUi();
        if (setup.isEmpty() || setup.get().passwordFile().isEmpty()) {
            return false;
        }
        Path file = setup.get().passwordFile().get();
        char[] password = read(file);
        try {
            if (cli.action() == LaunchOptions.Action.WEBUI_PASSWORD) {
                save(config, setup.get(), password);
            } else {
                update(config, setup.get(), password);
            }
            logger.info("Web interface password set from {}", file);
            return true;
        } finally {
            Passwords.wipe(password);
        }
    }

    /**
     * Turns the web interface on with {@code password} and the port, LAN, API, host name, proxy and guest choices of
     * {@code setup}.
     */
    static WebUiSettings save(Path config, LaunchOptions.WebUiSetup setup, char[] password)
            throws LaunchOptions.UsageException, IOException {
        WebUiSettings old = WebUiSettings.load(config);
        String admin = Passwords.hash(password, new SecureRandom());
        WebUiSettings settings = new WebUiSettings(
                true,
                setup.port().orElse(old.port()),
                setup.lan(),
                admin,
                setup.api() || old.api(),
                setup.hosts(),
                setup.proxies(),
                guestHash(setup, admin, old));
        settings.save(config);
        return settings;
    }

    /**
     * Turns the web interface on with {@code password}, the saved one when null, changing only the choices
     * {@code setup} gives: what was set in the web interface itself stays.
     */
    static WebUiSettings update(Path config, LaunchOptions.WebUiSetup setup, char[] password)
            throws LaunchOptions.UsageException, IOException {
        WebUiSettings old = WebUiSettings.load(config);
        String admin = password == null ? old.passwordHash() : Passwords.hash(password, new SecureRandom());
        WebUiSettings settings = new WebUiSettings(
                true,
                setup.port().orElse(old.port()),
                setup.lan() || old.lan(),
                admin,
                setup.api() || old.api(),
                setup.hosts().isEmpty() ? old.hosts() : setup.hosts(),
                setup.proxies().isEmpty() ? old.proxies() : setup.proxies(),
                guestHash(setup, admin, old));
        settings.save(config);
        return settings;
    }

    // The guest's password may not be the web interface's, whose holder would log in as a guest otherwise.
    private static String guestHash(LaunchOptions.WebUiSetup setup, String admin, WebUiSettings old)
            throws LaunchOptions.UsageException {
        if (setup.noGuest()) {
            return "";
        }
        if (setup.guestPasswordFile().isEmpty()) {
            return old.guestPasswordHash();
        }
        Path file = setup.guestPasswordFile().get();
        char[] guest = read(file, "web interface guest password");
        try {
            if (!admin.isEmpty() && Passwords.matches(guest, admin)) {
                throw new LaunchOptions.UsageException(
                        "The guest password in " + file + " is the web interface password: choose another one.");
            }
            String hash = Passwords.hash(guest, new SecureRandom());
            logger.info("Web interface guest password set from {}", file);
            return hash;
        } finally {
            Passwords.wipe(guest);
        }
    }

    static char[] read(Path file) throws LaunchOptions.UsageException {
        return read(file, "web interface password");
    }

    // The first line of the file, without its line ending or a byte order mark, kind naming the password in the
    // messages. No String holds the password, so that it can be wiped.
    private static char[] read(Path file, String kind) throws LaunchOptions.UsageException {
        byte[] bytes = null;
        CharBuffer text = null;
        try {
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) {
                throw new LaunchOptions.UsageException(
                        "The " + kind + " file " + file + " is longer than " + MAX_FILE_BYTES + " bytes.");
            }
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            int start = text.hasRemaining() && text.get(0) == '﻿' ? 1 : 0;
            int end = start;
            while (end < text.limit() && text.get(end) != '\n' && text.get(end) != '\r') {
                end++;
            }
            if (end - start < Passwords.MIN_LENGTH) {
                throw new LaunchOptions.UsageException(
                        "The password in " + file + " needs at least " + Passwords.MIN_LENGTH + " characters.");
            }
            char[] password = new char[end - start];
            text.get(start, password);
            return password;
        } catch (NoSuchFileException e) {
            throw new LaunchOptions.UsageException("The " + kind + " file " + file + " does not exist.");
        } catch (AccessDeniedException e) {
            throw new LaunchOptions.UsageException("The " + kind + " file " + file + " cannot be read: access denied.");
        } catch (CharacterCodingException e) {
            throw new LaunchOptions.UsageException("The " + kind + " file " + file + " is not UTF-8 text.");
        } catch (IOException e) {
            throw new LaunchOptions.UsageException(
                    "The " + kind + " file " + file + " cannot be read: " + e.getMessage());
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
            if (text != null && text.hasArray()) {
                Arrays.fill(text.array(), '\0');
            }
        }
    }
}
