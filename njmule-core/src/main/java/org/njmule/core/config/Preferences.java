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
package org.njmule.core.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, validated engine preferences.
 *
 * @param maxUploadKiBps {@code 0} = unlimited
 * @param maxDownloadKiBps {@code 0} = unlimited
 * @param incomingDir empty = platform default ({@link AppDirectories#downloads()})
 * @param tempDir empty = platform default ({@link AppDirectories#temp()})
 * @param logLevel one of {@link #LOG_LEVELS}
 * @param upnp whether the router is asked to forward our ports
 */
public record Preferences(
        String nick,
        int tcpPort,
        int udpPort,
        boolean udpEnabled,
        boolean ed2kEnabled,
        boolean kadEnabled,
        boolean autoConnect,
        boolean reconnect,
        int maxUploadKiBps,
        int maxDownloadKiBps,
        int maxConnections,
        int maxSourcesPerFile,
        ObfuscationMode obfuscation,
        boolean secureIdent,
        boolean creditSystem,
        Optional<Path> incomingDir,
        Optional<Path> tempDir,
        String logLevel,
        ProxySettings proxy,
        boolean upnp) {

    public static final int MAX_NICK_LENGTH = 50;
    public static final Set<String> LOG_LEVELS = Set.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE");
    public static final int DEFAULT_TCP_PORT = 4662;
    public static final int DEFAULT_UDP_PORT = 4672;

    public Preferences {
        Objects.requireNonNull(nick, "nick");
        nick = nick.strip();
        if (nick.isEmpty() || nick.length() > MAX_NICK_LENGTH || nick.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("nick must be 1-" + MAX_NICK_LENGTH + " printable characters");
        }
        requireRange("tcpPort", tcpPort, 1, 65_535);
        requireRange("udpPort", udpPort, 1, 65_535);
        requireRange("maxUploadKiBps", maxUploadKiBps, 0, 10_000_000);
        requireRange("maxDownloadKiBps", maxDownloadKiBps, 0, 10_000_000);
        requireRange("maxConnections", maxConnections, 1, 10_000);
        requireRange("maxSourcesPerFile", maxSourcesPerFile, 1, 10_000);
        Objects.requireNonNull(obfuscation, "obfuscation");
        Objects.requireNonNull(incomingDir, "incomingDir").ifPresent(p -> requireAbsolute("incomingDir", p));
        Objects.requireNonNull(tempDir, "tempDir").ifPresent(p -> requireAbsolute("tempDir", p));
        if (!LOG_LEVELS.contains(Objects.requireNonNull(logLevel, "logLevel"))) {
            throw new IllegalArgumentException("logLevel must be one of " + LOG_LEVELS);
        }
        Objects.requireNonNull(proxy, "proxy");
    }

    /** Defaults of a fresh installation, with njMule's own ports. */
    public static Preferences defaults() {
        return new Preferences(
                "njMule",
                DEFAULT_TCP_PORT,
                DEFAULT_UDP_PORT,
                true,
                true,
                true,
                true,
                true,
                0,
                0,
                500,
                400,
                ObfuscationMode.REQUESTED,
                true,
                true,
                Optional.empty(),
                Optional.empty(),
                "INFO",
                ProxySettings.NONE,
                false);
    }

    public Path effectiveIncomingDir(AppDirectories dirs) {
        return this.incomingDir.orElse(dirs.downloads());
    }

    public Path effectiveTempDir(AppDirectories dirs) {
        return this.tempDir.orElse(dirs.temp());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]: " + value);
        }
    }

    private static void requireAbsolute(String name, Path path) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be an absolute path: " + path);
        }
    }

    /** Mutable copy; {@link #build()} validates. */
    public static final class Builder {
        private String nick;
        private int tcpPort;
        private int udpPort;
        private boolean udpEnabled;
        private boolean ed2kEnabled;
        private boolean kadEnabled;
        private boolean autoConnect;
        private boolean reconnect;
        private int maxUploadKiBps;
        private int maxDownloadKiBps;
        private int maxConnections;
        private int maxSourcesPerFile;
        private ObfuscationMode obfuscation;
        private boolean secureIdent;
        private boolean creditSystem;
        private Optional<Path> incomingDir;
        private Optional<Path> tempDir;
        private String logLevel;
        private ProxySettings.Type proxyType;
        private String proxyHost;
        private int proxyPort;
        private String proxyUser;
        private String proxyPassword;
        private boolean upnp;

        private Builder(Preferences p) {
            this.nick = p.nick;
            this.tcpPort = p.tcpPort;
            this.udpPort = p.udpPort;
            this.udpEnabled = p.udpEnabled;
            this.ed2kEnabled = p.ed2kEnabled;
            this.kadEnabled = p.kadEnabled;
            this.autoConnect = p.autoConnect;
            this.reconnect = p.reconnect;
            this.maxUploadKiBps = p.maxUploadKiBps;
            this.maxDownloadKiBps = p.maxDownloadKiBps;
            this.maxConnections = p.maxConnections;
            this.maxSourcesPerFile = p.maxSourcesPerFile;
            this.obfuscation = p.obfuscation;
            this.secureIdent = p.secureIdent;
            this.creditSystem = p.creditSystem;
            this.incomingDir = p.incomingDir;
            this.tempDir = p.tempDir;
            this.logLevel = p.logLevel;
            this.proxy(p.proxy);
            this.upnp = p.upnp;
        }

        public Builder nick(String value) {
            this.nick = value;
            return this;
        }

        public Builder tcpPort(int value) {
            this.tcpPort = value;
            return this;
        }

        public Builder udpPort(int value) {
            this.udpPort = value;
            return this;
        }

        public Builder udpEnabled(boolean value) {
            this.udpEnabled = value;
            return this;
        }

        public Builder ed2kEnabled(boolean value) {
            this.ed2kEnabled = value;
            return this;
        }

        public Builder kadEnabled(boolean value) {
            this.kadEnabled = value;
            return this;
        }

        public Builder autoConnect(boolean value) {
            this.autoConnect = value;
            return this;
        }

        public Builder reconnect(boolean value) {
            this.reconnect = value;
            return this;
        }

        public Builder maxUploadKiBps(int value) {
            this.maxUploadKiBps = value;
            return this;
        }

        public Builder maxDownloadKiBps(int value) {
            this.maxDownloadKiBps = value;
            return this;
        }

        public Builder maxConnections(int value) {
            this.maxConnections = value;
            return this;
        }

        public Builder maxSourcesPerFile(int value) {
            this.maxSourcesPerFile = value;
            return this;
        }

        public Builder obfuscation(ObfuscationMode value) {
            this.obfuscation = value;
            return this;
        }

        public Builder secureIdent(boolean value) {
            this.secureIdent = value;
            return this;
        }

        public Builder creditSystem(boolean value) {
            this.creditSystem = value;
            return this;
        }

        public Builder incomingDir(Optional<Path> value) {
            this.incomingDir = value;
            return this;
        }

        public Builder tempDir(Optional<Path> value) {
            this.tempDir = value;
            return this;
        }

        public Builder logLevel(String value) {
            this.logLevel = value;
            return this;
        }

        public Builder proxy(ProxySettings value) {
            this.proxyType = value.type();
            this.proxyHost = value.host();
            this.proxyPort = value.port();
            this.proxyUser = value.user();
            this.proxyPassword = value.password();
            return this;
        }

        public Builder proxyType(ProxySettings.Type value) {
            this.proxyType = value;
            return this;
        }

        public Builder proxyHost(String value) {
            this.proxyHost = value;
            return this;
        }

        public Builder proxyPort(int value) {
            this.proxyPort = value;
            return this;
        }

        public Builder proxyUser(String value) {
            this.proxyUser = value;
            return this;
        }

        public Builder proxyPassword(String value) {
            this.proxyPassword = value;
            return this;
        }

        public Builder upnp(boolean value) {
            this.upnp = value;
            return this;
        }

        public Preferences build() {
            return new Preferences(
                    this.nick,
                    this.tcpPort,
                    this.udpPort,
                    this.udpEnabled,
                    this.ed2kEnabled,
                    this.kadEnabled,
                    this.autoConnect,
                    this.reconnect,
                    this.maxUploadKiBps,
                    this.maxDownloadKiBps,
                    this.maxConnections,
                    this.maxSourcesPerFile,
                    this.obfuscation,
                    this.secureIdent,
                    this.creditSystem,
                    this.incomingDir,
                    this.tempDir,
                    this.logLevel,
                    new ProxySettings(
                            this.proxyType, this.proxyHost, this.proxyPort, this.proxyUser, this.proxyPassword),
                    this.upnp);
        }
    }
}
