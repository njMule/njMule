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
package org.njmule.core.persist;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@code njmule.properties} (UTF-8, sorted keys, no timestamp so that the file
 * diffs cleanly). Loading is lenient: an invalid value keeps its default and logs a warning, so a
 * hand-edited typo never prevents start-up.
 */
public final class PreferencesCodec {

    public static final String FILE_NAME = "njmule.properties";

    private static final Logger logger = LoggerFactory.getLogger(PreferencesCodec.class);

    private record Key(
            String name,
            Function<Preferences, String> getter,
            BiConsumer<Preferences.Builder, String> setter) {}

    private static final Map<String, Key> KEYS = new LinkedHashMap<>();

    static {
        key("user.nick", Preferences::nick, Preferences.Builder::nick);
        intKey("net.tcpPort", Preferences::tcpPort, Preferences.Builder::tcpPort);
        intKey("net.udpPort", Preferences::udpPort, Preferences.Builder::udpPort);
        boolKey("net.udpEnabled", Preferences::udpEnabled, Preferences.Builder::udpEnabled);
        boolKey("net.ed2k.enabled", Preferences::ed2kEnabled, Preferences.Builder::ed2kEnabled);
        boolKey("net.kad.enabled", Preferences::kadEnabled, Preferences.Builder::kadEnabled);
        boolKey("net.autoConnect", Preferences::autoConnect, Preferences.Builder::autoConnect);
        boolKey("net.reconnect", Preferences::reconnect, Preferences.Builder::reconnect);
        intKey(
                "net.maxConnections",
                Preferences::maxConnections,
                Preferences.Builder::maxConnections);
        intKey(
                "limits.uploadKiBps",
                Preferences::maxUploadKiBps,
                Preferences.Builder::maxUploadKiBps);
        intKey(
                "limits.downloadKiBps",
                Preferences::maxDownloadKiBps,
                Preferences.Builder::maxDownloadKiBps);
        intKey(
                "download.maxSourcesPerFile",
                Preferences::maxSourcesPerFile,
                Preferences.Builder::maxSourcesPerFile);
        key(
                "security.obfuscation",
                p -> p.obfuscation().name(),
                (b, v) ->
                        b.obfuscation(ObfuscationMode.valueOf(v.strip().toUpperCase(Locale.ROOT))));
        boolKey("security.secureIdent", Preferences::secureIdent, Preferences.Builder::secureIdent);
        boolKey(
                "upload.creditSystem",
                Preferences::creditSystem,
                Preferences.Builder::creditSystem);
        key(
                "dirs.incoming",
                p -> p.incomingDir().map(Path::toString).orElse(""),
                (b, v) -> b.incomingDir(optionalPath(v)));
        key(
                "dirs.temp",
                p -> p.tempDir().map(Path::toString).orElse(""),
                (b, v) -> b.tempDir(optionalPath(v)));
        key(
                "log.level",
                Preferences::logLevel,
                (b, v) -> b.logLevel(v.strip().toUpperCase(Locale.ROOT)));
        boolKey("net.upnp", Preferences::upnp, Preferences.Builder::upnp);
        key(
                "proxy.type",
                p -> p.proxy().type().name(),
                (b, v) ->
                        b.proxyType(
                                ProxySettings.Type.valueOf(v.strip().toUpperCase(Locale.ROOT))));
        key("proxy.host", p -> p.proxy().host(), Preferences.Builder::proxyHost);
        key(
                "proxy.port",
                p -> String.valueOf(p.proxy().port()),
                (b, v) -> b.proxyPort(Integer.parseInt(v.strip())));
        key("proxy.user", p -> p.proxy().user(), Preferences.Builder::proxyUser);
        key("proxy.password", p -> p.proxy().password(), Preferences.Builder::proxyPassword);
    }

    private PreferencesCodec() {}

    /** Loads the preferences, or returns the defaults if the file and its backup are missing. */
    public static Preferences load(Path file) throws IOException {
        try {
            return AtomicFiles.read(
                    file, in -> parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (NoSuchFileException e) {
            return Preferences.defaults();
        }
    }

    public static void save(Path file, Preferences preferences) throws IOException {
        AtomicFiles.write(
                file,
                out -> {
                    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                    format(preferences, w);
                    w.flush();
                });
    }

    static Preferences parse(Reader reader) throws IOException {
        Properties props = new Properties();
        props.load(reader);
        Preferences.Builder builder = Preferences.defaults().toBuilder();
        for (String name : props.stringPropertyNames()) {
            Key key = KEYS.get(name);
            if (key == null) {
                logger.debug("Ignoring unknown preference {}", name);
                continue;
            }
            Preferences.Builder candidate = builder.build().toBuilder();
            try {
                key.setter().accept(candidate, props.getProperty(name));
                builder = candidate.build().toBuilder();
            } catch (RuntimeException e) {
                logger.warn(
                        "Invalid value for preference {}, keeping the default: {}",
                        name,
                        e.getMessage());
            }
        }
        return builder.build();
    }

    static void format(Preferences preferences, Writer out) throws IOException {
        out.write(
                "# njMule preferences - edited by the application; invalid values revert to"
                        + " defaults.\n");
        for (Key key :
                KEYS.values().stream().sorted((a, b) -> a.name().compareTo(b.name())).toList()) {
            out.write(key.name());
            out.write('=');
            out.write(escape(key.getter().apply(preferences)));
            out.write('\n');
        }
    }

    // Escapes a value for java.util.Properties#load.
    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\f' -> sb.append("\\f");
                case ' ' -> sb.append(i == 0 ? "\\ " : " ");
                case '#', '!', '=', ':' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Optional<Path> optionalPath(String value) {
        String v = value.strip();
        return v.isEmpty() ? Optional.empty() : Optional.of(Path.of(v));
    }

    private static void key(
            String name,
            Function<Preferences, String> getter,
            BiConsumer<Preferences.Builder, String> setter) {
        KEYS.put(name, new Key(name, getter, setter));
    }

    private static void intKey(
            String name,
            Function<Preferences, Integer> getter,
            BiConsumer<Preferences.Builder, Integer> setter) {
        key(
                name,
                p -> Integer.toString(getter.apply(p)),
                (b, v) -> setter.accept(b, Integer.parseInt(v.strip())));
    }

    private static void boolKey(
            String name,
            Function<Preferences, Boolean> getter,
            BiConsumer<Preferences.Builder, Boolean> setter) {
        key(
                name,
                p -> Boolean.toString(getter.apply(p)),
                (b, v) -> setter.accept(b, parseBoolean(v)));
    }

    private static boolean parseBoolean(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("not a boolean: " + value);
        };
    }
}
