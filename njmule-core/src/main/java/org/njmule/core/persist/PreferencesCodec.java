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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.njmule.core.config.EventCommands;
import org.njmule.core.config.NextDownload;
import org.njmule.core.config.NightHours;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.config.SharedFilesVisibility;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.config.UpdateInstallTime;
import org.njmule.core.config.UpdateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes {@code njmule.properties} (UTF-8, sorted keys, no timestamp so that the file diffs cleanly). Loading
 * is lenient: an invalid value keeps its default and logs a warning, so a hand-edited typo never prevents start-up.
 */
public final class PreferencesCodec {

    public static final String FILE_NAME = "njmule.properties";

    private static final Logger logger = LoggerFactory.getLogger(PreferencesCodec.class);

    private record Key(
            String name, Function<Preferences, String> getter, BiConsumer<Preferences.Builder, String> setter) {}

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
        intKey("net.maxConnections", Preferences::maxConnections, Preferences.Builder::maxConnections);
        intKey("limits.uploadKiBps", Preferences::maxUploadKiBps, Preferences.Builder::maxUploadKiBps);
        intKey("limits.downloadKiBps", Preferences::maxDownloadKiBps, Preferences.Builder::maxDownloadKiBps);
        intKey(
                "limits.downloadCapacityKiBps",
                Preferences::downloadCapacityKiBps,
                Preferences.Builder::downloadCapacityKiBps);
        intKey(
                "limits.uploadCapacityKiBps",
                Preferences::uploadCapacityKiBps,
                Preferences.Builder::uploadCapacityKiBps);
        intKey("download.maxSourcesPerFile", Preferences::maxSourcesPerFile, Preferences.Builder::maxSourcesPerFile);
        intKey("download.minFreeSpaceMiB", Preferences::minFreeSpaceMiB, Preferences.Builder::minFreeSpaceMiB);
        key(
                "security.obfuscation",
                p -> p.obfuscation().name(),
                (b, v) -> b.obfuscation(ObfuscationMode.valueOf(v.strip().toUpperCase(Locale.ROOT))));
        boolKey("security.secureIdent", Preferences::secureIdent, Preferences.Builder::secureIdent);
        boolKey("upload.creditSystem", Preferences::creditSystem, Preferences.Builder::creditSystem);
        key(
                "dirs.incoming",
                p -> p.incomingDir().map(Path::toString).orElse(""),
                (b, v) -> b.incomingDir(optionalPath(v)));
        key("dirs.temp", p -> p.tempDir().map(Path::toString).orElse(""), (b, v) -> b.tempDir(optionalPath(v)));
        key("log.level", Preferences::logLevel, (b, v) -> b.logLevel(v.strip().toUpperCase(Locale.ROOT)));
        boolKey("net.upnp", Preferences::upnp, Preferences.Builder::upnp);
        key("net.bindInterface", Preferences::bindInterface, Preferences.Builder::bindInterface);
        intKey("backup.copies", Preferences::backupCopies, Preferences.Builder::backupCopies);
        intKey("security.ipFilterLevel", Preferences::ipFilterLevel, Preferences.Builder::ipFilterLevel);
        boolKey("security.filterLan", Preferences::filterLanAddresses, Preferences.Builder::filterLanAddresses);
        key("security.ipFilterWhitelist", Preferences::ipFilterWhitelist, Preferences.Builder::ipFilterWhitelist);
        key("security.ipFilterLists", Preferences::ipFilterLists, Preferences.Builder::ipFilterLists);
        intKey("security.ipFilterUpdateDays", Preferences::ipFilterUpdateDays, Preferences.Builder::ipFilterUpdateDays);
        key(
                "update.mode",
                p -> p.updateMode().name(),
                (b, v) -> b.updateMode(UpdateMode.valueOf(v.strip().toUpperCase(Locale.ROOT))));
        key(
                "update.channel",
                p -> p.updateChannel().name(),
                (b, v) -> b.updateChannel(UpdateChannel.valueOf(v.strip().toUpperCase(Locale.ROOT))));
        key(
                "update.installAt",
                p -> p.updateInstallTime().name(),
                (b, v) -> b.updateInstallTime(
                        UpdateInstallTime.valueOf(v.strip().replace('-', '_').toUpperCase(Locale.ROOT))));
        key("update.nightHours", p -> p.nightHours().toString(), (b, v) -> b.nightHours(NightHours.parse(v)));
        key(
                "update.url",
                p -> p.updateUrl().map(URI::toString).orElse(""),
                (b, v) -> b.updateUrl(v.isBlank() ? Optional.empty() : Optional.of(URI.create(v.strip()))));
        boolKey("chat.friendsOnly", Preferences::messagesFromFriendsOnly, Preferences.Builder::messagesFromFriendsOnly);
        boolKey("search.spamFilter", Preferences::searchSpamFilter, Preferences.Builder::searchSpamFilter);
        key("chat.filter", Preferences::messageFilter, Preferences.Builder::messageFilter);
        boolKey("chat.spamFilter", Preferences::advancedSpamFilter, Preferences.Builder::advancedSpamFilter);
        intKey(
                "net.newConnectionsPer5s",
                Preferences::newConnectionsPerFiveSeconds,
                Preferences.Builder::newConnectionsPerFiveSeconds);
        intKey("upload.queueSize", Preferences::uploadQueueSize, Preferences.Builder::uploadQueueSize);
        boolKey("download.previewFirst", Preferences::previewFirst, Preferences.Builder::previewFirst);
        key("preview.player", Preferences::videoPlayer, Preferences.Builder::videoPlayer);
        key("preview.args", Preferences::videoPlayerArgs, Preferences.Builder::videoPlayerArgs);
        key("comments.filter", Preferences::commentFilter, Preferences.Builder::commentFilter);
        key("files.nameCleanups", Preferences::nameCleanups, Preferences.Builder::nameCleanups);
        boolKey("files.cleanUpNewNames", Preferences::cleanUpNewNames, Preferences.Builder::cleanUpNewNames);
        boolKey("notify.sound", Preferences::notificationSound, Preferences.Builder::notificationSound);
        boolKey("system.preventSleep", Preferences::preventSleep, Preferences.Builder::preventSleep);
        key(
                "notify.soundFile",
                p -> p.notificationSoundFile().map(Path::toString).orElse(""),
                (b, v) -> b.notificationSoundFile(optionalPath(v)));
        key(
                "share.visibleTo",
                p -> p.sharedFilesVisibility().name(),
                (b, v) -> b.sharedFilesVisibility(
                        SharedFilesVisibility.valueOf(v.strip().toUpperCase(Locale.ROOT))));
        boolKey(
                "files.rememberDownloaded",
                Preferences::rememberDownloadedFiles,
                Preferences.Builder::rememberDownloadedFiles);
        boolKey(
                "files.rememberCancelled",
                Preferences::rememberCancelledFiles,
                Preferences.Builder::rememberCancelledFiles);
        boolKey("search.store", Preferences::storeSearches, Preferences.Builder::storeSearches);
        boolKey("download.addPaused", Preferences::addDownloadsPaused, Preferences.Builder::addDownloadsPaused);
        key(
                "download.startNext",
                p -> p.nextDownload().name(),
                (b, v) -> b.nextDownload(NextDownload.valueOf(v.strip().toUpperCase(Locale.ROOT))));
        intKey("server.deadRetries", Preferences::deadServerRetries, Preferences.Builder::deadServerRetries);
        boolKey("server.addFromServer", Preferences::addServersFromServer, Preferences.Builder::addServersFromServer);
        boolKey(
                "server.addFromClients",
                Preferences::addServersFromClients,
                Preferences.Builder::addServersFromClients);
        boolKey("server.staticOnly", Preferences::staticServersOnly, Preferences.Builder::staticServersOnly);
        boolKey("server.updateAtStart", Preferences::updateServersAtStart, Preferences.Builder::updateServersAtStart);
        boolKey(
                "server.manualHighPriority",
                Preferences::manualServersHighPriority,
                Preferences.Builder::manualServersHighPriority);
        for (EventCommands.Event event : EventCommands.Event.values()) {
            String name = "events." + event.id();
            boolKey(name + ".enabled", p -> p.eventCommands().get(event).enabled(), (b, v) -> b.eventEnabled(event, v));
            key(
                    name + ".command",
                    p -> p.eventCommands().get(event).commandLine(),
                    (b, v) -> b.eventCommandLine(event, v));
        }
        key("proxy.host", p -> p.proxy().host(), Preferences.Builder::proxyHost);
        key("proxy.port", p -> String.valueOf(p.proxy().port()), (b, v) -> b.proxyPort(Integer.parseInt(v.strip())));
        key("proxy.user", p -> p.proxy().user(), Preferences.Builder::proxyUser);
        key("proxy.password", p -> p.proxy().password(), Preferences.Builder::proxyPassword);
        // A proxy type needs its host: read after it.
        key(
                "proxy.type",
                p -> p.proxy().type().name(),
                (b, v) -> b.proxyType(ProxySettings.Type.valueOf(v.strip().toUpperCase(Locale.ROOT))));
    }

    private PreferencesCodec() {}

    /** Every preference as the file writes it, by key. */
    public static SortedMap<String, String> values(Preferences preferences) {
        SortedMap<String, String> all = new TreeMap<>();
        KEYS.values().forEach(k -> all.put(k.name(), k.getter().apply(preferences)));
        return all;
    }

    /**
     * {@code preferences} with {@code changes} applied as the file's values are read, in the order the keys are
     * declared; an unknown key or a wrong value is refused, naming its key.
     */
    public static Preferences apply(Preferences preferences, Map<String, String> changes) {
        for (String name : changes.keySet()) {
            if (!KEYS.containsKey(name)) {
                throw new IllegalArgumentException("Unknown preference: " + name);
            }
        }
        Preferences.Builder builder = preferences.toBuilder();
        for (Key key : KEYS.values()) {
            String value = changes.get(key.name());
            if (value == null) {
                continue;
            }
            Preferences.Builder candidate = builder.build().toBuilder();
            try {
                key.setter().accept(candidate, value);
                builder = candidate.build().toBuilder();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(key.name() + ": " + e.getMessage(), e);
            }
        }
        return builder.build();
    }

    /** Loads the preferences, or returns the defaults if the file and its backup are missing. */
    public static Preferences load(Path file) throws IOException {
        try {
            return AtomicFiles.read(file, in -> parse(new InputStreamReader(in, StandardCharsets.UTF_8)), false);
        } catch (NoSuchFileException e) {
            return Preferences.defaults();
        }
    }

    /**
     * Writes the preferences; keys of the file this version does not know stay, so that an older version loses none.
     */
    public static void save(Path file, Preferences preferences) throws IOException {
        Properties unknown = unknownKeys(file);
        AtomicFiles.write(file, out -> {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            format(preferences, w);
            if (!unknown.isEmpty()) {
                StringBuilder kept = new StringBuilder("# Not used by this version of njMule, kept as they were.\n");
                for (String name : new TreeSet<>(unknown.stringPropertyNames())) {
                    PropertyLines.append(kept, name, unknown.getProperty(name));
                }
                w.write(kept.toString());
            }
            w.flush();
        });
    }

    private static Properties unknownKeys(Path file) {
        Properties props;
        try {
            props = AtomicFiles.read(
                    file, in -> PropertyLines.parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return new Properties();
        }
        props.keySet().removeIf(KEYS::containsKey);
        return props;
    }

    static Preferences parse(Reader reader) throws IOException {
        Properties props = PropertyLines.parse(reader);
        for (String name : props.stringPropertyNames()) {
            if (!KEYS.containsKey(name)) {
                logger.debug("Keeping the unknown preference {} as it is", name);
            }
        }
        // In the order the keys are declared: a value checked against others comes after them.
        Preferences.Builder builder = Preferences.defaults().toBuilder();
        for (Key key : KEYS.values()) {
            String name = key.name();
            if (props.getProperty(name) == null) {
                continue;
            }
            Preferences.Builder candidate = builder.build().toBuilder();
            try {
                key.setter().accept(candidate, props.getProperty(name));
                builder = candidate.build().toBuilder();
            } catch (RuntimeException e) {
                logger.warn("Invalid value for preference {}, keeping the default: {}", name, e.getMessage());
            }
        }
        return builder.build();
    }

    static void format(Preferences preferences, Writer out) throws IOException {
        StringBuilder text = new StringBuilder(
                "# njMule preferences - edited by the application; invalid values revert to defaults.\n");
        for (Key key : KEYS.values().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList()) {
            PropertyLines.append(text, key.name(), key.getter().apply(preferences));
        }
        out.write(text.toString());
    }

    private static Optional<Path> optionalPath(String value) {
        String v = value.strip();
        return v.isEmpty() ? Optional.empty() : Optional.of(Path.of(v));
    }

    private static void key(
            String name, Function<Preferences, String> getter, BiConsumer<Preferences.Builder, String> setter) {
        KEYS.put(name, new Key(name, getter, setter));
    }

    private static void intKey(
            String name, Function<Preferences, Integer> getter, BiConsumer<Preferences.Builder, Integer> setter) {
        key(name, p -> Integer.toString(getter.apply(p)), (b, v) -> setter.accept(b, Integer.parseInt(v.strip())));
    }

    private static void boolKey(
            String name, Function<Preferences, Boolean> getter, BiConsumer<Preferences.Builder, Boolean> setter) {
        key(name, p -> Boolean.toString(getter.apply(p)), (b, v) -> setter.accept(b, parseBoolean(v)));
    }

    private static boolean parseBoolean(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("not a boolean: " + value);
        };
    }
}
