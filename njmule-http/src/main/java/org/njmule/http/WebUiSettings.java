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
package org.njmule.http;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
import org.njmule.core.api.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the web interface runs, on which port, whether other computers may reach it, the hash of its password and the
 * host names it answers to; kept in {@code webui.properties} of the config directory.
 *
 * @param lan whether to listen on every address instead of this computer's only
 * @param passwordHash empty until a password is set; the interface does not start without one
 * @param api whether the remote-control API answers too, to holders of its token
 * @param hosts names besides localhost under which browsers open the interface, such as a LAN name or a reverse
 *     proxy's, as {@link #hostName} keeps them
 * @param proxies the reverse proxies trusted to name their client in X-Forwarded-For, as {@link #proxy} keeps them;
 *     none by default
 * @param guestPasswordHash the hash of the password of a guest, who may only look; empty for no guest
 */
public record WebUiSettings(
        boolean enabled,
        int port,
        boolean lan,
        String passwordHash,
        boolean api,
        List<String> hosts,
        List<String> proxies,
        String guestPasswordHash) {

    public static final String FILE_NAME = "webui.properties";

    /** The port eMule's web interface uses too. */
    public static final int DEFAULT_PORT = 4711;

    public static final WebUiSettings DEFAULTS =
            new WebUiSettings(false, DEFAULT_PORT, false, "", false, List.of(), List.of(), "");

    private static final Logger logger = LoggerFactory.getLogger(WebUiSettings.class);

    // Between the host names of the file and of the settings screen.
    private static final Pattern SEPARATORS = Pattern.compile("[,\\s]+");

    public WebUiSettings {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be in [1, 65535]: " + port);
        }
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(guestPasswordHash, "guestPasswordHash");
        // List.copyOf, which SpotBugs knows to be unmodifiable.
        hosts = List.copyOf(Objects.requireNonNull(hosts, "hosts").stream()
                .map(WebUiSettings::hostName)
                .distinct()
                .toList());
        proxies = List.copyOf(Objects.requireNonNull(proxies, "proxies").stream()
                .map(WebUiSettings::proxy)
                .distinct()
                .toList());
    }

    /** Settings without a guest. */
    public WebUiSettings(
            boolean enabled,
            int port,
            boolean lan,
            String passwordHash,
            boolean api,
            List<String> hosts,
            List<String> proxies) {
        this(enabled, port, lan, passwordHash, api, hosts, proxies, "");
    }

    /** These settings with the guest password of {@code hash}, or none when it is empty. */
    public WebUiSettings withGuest(String hash) {
        return new WebUiSettings(
                this.enabled, this.port, this.lan, this.passwordHash, this.api, this.hosts, this.proxies, hash);
    }

    /** Whether a guest may log in. */
    public boolean hasGuest() {
        return !this.guestPasswordHash.isEmpty();
    }

    /** Settings that trust no proxy. */
    public WebUiSettings(boolean enabled, int port, boolean lan, String passwordHash, boolean api, List<String> hosts) {
        this(enabled, port, lan, passwordHash, api, hosts, List.of());
    }

    /** Settings without other host names. */
    public WebUiSettings(boolean enabled, int port, boolean lan, String passwordHash, boolean api) {
        this(enabled, port, lan, passwordHash, api, List.of());
    }

    /** Settings without the remote-control API. */
    public WebUiSettings(boolean enabled, int port, boolean lan, String passwordHash) {
        this(enabled, port, lan, passwordHash, false);
    }

    /** Whether the interface can start: enabled, with a password. */
    public boolean usable() {
        return this.enabled && !this.passwordHash.isEmpty();
    }

    /**
     * A host name or IP address as the interface compares it with the Host and Origin headers: in lower case, an IPv6
     * address in brackets.
     *
     * @throws IllegalArgumentException when {@code name} is neither, or comes with a port or a scheme
     */
    public static String hostName(String name) {
        String lower = name.strip().toLowerCase(Locale.ROOT);
        boolean bracketed = lower.startsWith("[") && lower.endsWith("]");
        String bare = bracketed ? lower.substring(1, lower.length() - 1) : lower;
        boolean ipv6 = bare.indexOf(':') >= 0;
        // Browsers send an international name in its ASCII form (xn--), which is the one to give.
        if (!bare.chars().allMatch(c -> c < 0x80) || !ServerAddress.isValidHost(bare) || (bracketed && !ipv6)) {
            throw new IllegalArgumentException("not a host name or an IP address: " + name);
        }
        return ipv6 ? "[" + bare + "]" : bare;
    }

    /**
     * A reverse proxy as the interface trusts it: an address ({@code 172.17.0.1}, {@code ::1}), a CIDR block
     * ({@code 10.0.0.0/8}) or a range ({@code 10.0.0.1-10.0.0.9}).
     *
     * @throws IllegalArgumentException when {@code text} is none of those, a host name included
     */
    public static String proxy(String text) {
        return TrustedProxies.entry(text);
    }

    /** The proxies of a text that separates them with commas or white space. */
    public static List<String> proxies(String text) {
        return SEPARATORS
                .splitAsStream(text.strip())
                .filter(n -> !n.isEmpty())
                .map(WebUiSettings::proxy)
                .distinct()
                .toList();
    }

    /** The host names of a text that separates them with commas or white space. */
    public static List<String> hostNames(String text) {
        return SEPARATORS
                .splitAsStream(text.strip())
                .filter(n -> !n.isEmpty())
                .map(WebUiSettings::hostName)
                .distinct()
                .toList();
    }

    public static WebUiSettings load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return DEFAULTS;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
            return new WebUiSettings(
                    Boolean.parseBoolean(p.getProperty("enabled", "false").strip()),
                    Integer.parseInt(
                            p.getProperty("port", String.valueOf(DEFAULT_PORT)).strip()),
                    Boolean.parseBoolean(p.getProperty("lan", "false").strip()),
                    p.getProperty("password", "").strip(),
                    Boolean.parseBoolean(p.getProperty("api", "false").strip()),
                    hostNames(p.getProperty("hosts", "")),
                    proxies(p.getProperty("proxies", "")),
                    p.getProperty("guestPassword", "").strip());
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("The web interface stays off: cannot read {}: {}", file, e.toString());
            return DEFAULTS;
        }
    }

    /** Writes the settings, replacing the old file in one step. */
    public void save(Path configDir) throws IOException {
        Properties values = new Properties();
        values.setProperty("enabled", String.valueOf(this.enabled));
        values.setProperty("port", String.valueOf(this.port));
        values.setProperty("lan", String.valueOf(this.lan));
        values.setProperty("password", this.passwordHash);
        values.setProperty("api", String.valueOf(this.api));
        values.setProperty("hosts", String.join(",", this.hosts));
        values.setProperty("proxies", String.join(",", this.proxies));
        if (hasGuest()) {
            values.setProperty("guestPassword", this.guestPasswordHash);
        }
        StringWriter stored = new StringWriter();
        values.store(stored, null);
        StringBuilder text = new StringBuilder("# njMule web interface\n");
        // store() escapes a leading '#', so the only comment it writes is the date, left out.
        stored.toString()
                .lines()
                .filter(l -> !l.startsWith("#"))
                .forEach(l -> text.append(l).append('\n'));
        Files.createDirectories(configDir);
        Path tmp = configDir.resolve(FILE_NAME + ".tmp");
        Files.deleteIfExists(tmp);
        // Only its owner may read the password hashes, where the file system can say so.
        if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(
                tmp, configDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public String toString() {
        return "WebUiSettings[enabled=" + this.enabled + ", port=" + this.port + ", lan=" + this.lan + ", api="
                + this.api + ", hosts=" + this.hosts + ", proxies=" + this.proxies + ", guest=" + hasGuest() + "]";
    }
}
