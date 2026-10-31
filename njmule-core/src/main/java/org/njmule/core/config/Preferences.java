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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.security.IpWhitelist;

/**
 * Immutable, validated engine preferences.
 *
 * @param maxUploadKiBps {@code 0} = unlimited
 * @param maxDownloadKiBps {@code 0} = unlimited
 * @param incomingDir empty = platform default ({@link AppDirectories#downloads()})
 * @param tempDir empty = platform default ({@link AppDirectories#temp()})
 * @param logLevel one of {@link #LOG_LEVELS}
 * @param upnp whether the router is asked to forward our ports
 * @param bindInterface the network interface (name, display name or IPv4 address) every connection goes through; blank
 *     = any (see {@link BindInterface})
 * @param downloadCapacityKiBps what the line can download, the top of the speed controls
 * @param uploadCapacityKiBps what the line can upload
 * @param minFreeSpaceMiB downloads wait while the temp disk has less free space; {@code 0} = never
 * @param ipFilterWhitelist addresses the IP filter never blocks, one per line (see {@link #invalidWhitelistEntry})
 * @param eventCommands the programs run when a download ends, a chat message arrives or the disk runs short
 * @param notificationSound whether the desktop app plays {@code notificationSoundFile} with its notifications
 * @param notificationSoundFile a WAV or MP3 file; empty = none
 * @param ipFilterLists addresses (http or https) of IP filter lists, one per line, joined into one filter (see
 *     {@link #invalidListEntry})
 * @param ipFilterUpdateDays the lists are fetched again once the filter is this many days old; {@code 0} = by hand only
 * @param updateMode whether njMule looks for new versions of itself on its own
 * @param updateChannel which new versions it tells of
 * @param updateInstallTime when a new version downloaded is installed, in the {@link UpdateMode#INSTALL} mode
 * @param nightHours the hours when njMule may restart to install it, with {@link UpdateInstallTime#EXIT_OR_NIGHT}
 * @param updateUrl where the releases are looked for instead of GitHub, as for a fork or a test; empty for GitHub
 * @param preventSleep keeps the computer from sleeping while files are transferred
 * @param autoDeleteDays a running download that receives nothing for this many days is deleted, its link kept; 0 for
 *     never
 * @param notifications the e-mails and web hook requests that tell of what happens
 * @param showCountries shows the country of other clients, from DB-IP's database fetched once a month
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
        boolean upnp,
        String bindInterface,
        int downloadCapacityKiBps,
        int uploadCapacityKiBps,
        int minFreeSpaceMiB,
        int deadServerRetries,
        boolean addServersFromServer,
        boolean addServersFromClients,
        boolean staticServersOnly,
        boolean manualServersHighPriority,
        boolean updateServersAtStart,
        int backupCopies,
        int ipFilterLevel,
        boolean rememberDownloadedFiles,
        boolean rememberCancelledFiles,
        boolean storeSearches,
        boolean addDownloadsPaused,
        NextDownload nextDownload,
        boolean filterLanAddresses,
        boolean messagesFromFriendsOnly,
        String messageFilter,
        boolean advancedSpamFilter,
        boolean searchSpamFilter,
        int newConnectionsPerFiveSeconds,
        int uploadQueueSize,
        SharedFilesVisibility sharedFilesVisibility,
        boolean previewFirst,
        String videoPlayer,
        String videoPlayerArgs,
        String commentFilter,
        String nameCleanups,
        boolean cleanUpNewNames,
        String ipFilterWhitelist,
        EventCommands eventCommands,
        boolean notificationSound,
        Optional<Path> notificationSoundFile,
        String ipFilterLists,
        int ipFilterUpdateDays,
        UpdateMode updateMode,
        UpdateChannel updateChannel,
        UpdateInstallTime updateInstallTime,
        NightHours nightHours,
        Optional<URI> updateUrl,
        boolean preventSleep,
        int autoDeleteDays,
        Notifications notifications,
        boolean showCountries) {

    public static final int MAX_NICK_LENGTH = 50;
    public static final int MAX_PORT = 65_535;
    public static final int MAX_RATE_KIBPS = 10_000_000;
    public static final int MAX_CONNECTIONS = 10_000;
    public static final int MAX_SOURCES_PER_FILE = 10_000;
    public static final Set<String> LOG_LEVELS = Set.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE");
    public static final int DEFAULT_TCP_PORT = 4662;
    public static final int DEFAULT_UDP_PORT = 4672;
    // A common home line: about 80 Mbit/s down and 8 up.
    public static final int DEFAULT_DOWNLOAD_CAPACITY = 10_240;
    public static final int DEFAULT_UPLOAD_CAPACITY = 1_024;
    public static final int DEFAULT_MIN_FREE_SPACE = 512;
    public static final int MAX_MIN_FREE_SPACE = 10_000_000;
    public static final int MAX_AUTO_DELETE_DAYS = 365;
    /** A server that failed this many times in a row is dropped from the list. */
    public static final int DEFAULT_DEAD_SERVER_RETRIES = 1;

    public static final int MAX_DEAD_SERVER_RETRIES = 10;
    /** Zip copies of the configuration kept, written at each exit and daily; 0 writes none. */
    public static final int DEFAULT_BACKUP_COPIES = 3;

    public static final int MAX_BACKUP_COPIES = 50;
    /** IP filter ranges below this level block. */
    public static final int DEFAULT_IP_FILTER_LEVEL = 127;

    public static final int MAX_IP_FILTER_LEVEL = 255;
    public static final int MAX_IP_FILTER_WHITELIST = 16_384;
    public static final int MAX_IP_FILTER_LISTS = 16;
    public static final int MAX_IP_FILTER_UPDATE_DAYS = 365;
    public static final int DEFAULT_IP_FILTER_UPDATE_DAYS = 7;
    private static final int MAX_LIST_ADDRESS = 2_048;
    /** Chat messages holding one of these words, apart by {@code |}, do not show. */
    public static final String DEFAULT_MESSAGE_FILTER = "fastest download speed|fastest eMule";

    public static final int MAX_MESSAGE_FILTER = 4096;

    /** Longest name of a network interface to bind to. */
    public static final int MAX_BIND_INTERFACE = 256;
    /** File comments holding one of these words, apart by {@code |}, are left out. */
    public static final String DEFAULT_COMMENT_FILTER = "http://|https://|ftp://|www.|ftp.";
    /** Words, apart by {@code |}, a clean-up takes out of file names. */
    public static final String DEFAULT_NAME_CLEANUPS =
            "http|www.|.com|.de|.org|.net|shared|powered|sponsored|sharelive|filedonkey";
    /** Incoming connections taken in five seconds at most. */
    public static final int DEFAULT_NEW_CONNECTIONS = 20;

    public static final int MAX_NEW_CONNECTIONS = 1_000;
    /** Clients waiting in the upload queue at most. */
    public static final int DEFAULT_UPLOAD_QUEUE = 5_000;

    public static final int MIN_UPLOAD_QUEUE = 100;
    public static final int MAX_UPLOAD_QUEUE = 100_000;

    public Preferences {
        Objects.requireNonNull(nick, "nick");
        nick = nick.strip();
        if (!isValidNick(nick)) {
            throw new IllegalArgumentException("nick must be 1-" + MAX_NICK_LENGTH + " printable characters");
        }
        requireRange("tcpPort", tcpPort, 1, MAX_PORT);
        requireRange("udpPort", udpPort, 1, MAX_PORT);
        requireRange("maxUploadKiBps", maxUploadKiBps, 0, MAX_RATE_KIBPS);
        requireRange("maxDownloadKiBps", maxDownloadKiBps, 0, MAX_RATE_KIBPS);
        requireRange("maxConnections", maxConnections, 1, MAX_CONNECTIONS);
        requireRange("maxSourcesPerFile", maxSourcesPerFile, 1, MAX_SOURCES_PER_FILE);
        Objects.requireNonNull(obfuscation, "obfuscation");
        Objects.requireNonNull(incomingDir, "incomingDir").ifPresent(p -> requireAbsolute("incomingDir", p));
        Objects.requireNonNull(tempDir, "tempDir").ifPresent(p -> requireAbsolute("tempDir", p));
        if (!LOG_LEVELS.contains(Objects.requireNonNull(logLevel, "logLevel"))) {
            throw new IllegalArgumentException("logLevel must be one of " + LOG_LEVELS);
        }
        Objects.requireNonNull(proxy, "proxy");
        requireRange("downloadCapacityKiBps", downloadCapacityKiBps, 1, MAX_RATE_KIBPS);
        requireRange("uploadCapacityKiBps", uploadCapacityKiBps, 1, MAX_RATE_KIBPS);
        requireRange("minFreeSpaceMiB", minFreeSpaceMiB, 0, MAX_MIN_FREE_SPACE);
        requireRange("autoDeleteDays", autoDeleteDays, 0, MAX_AUTO_DELETE_DAYS);
        Objects.requireNonNull(notifications, "notifications");
        requireRange("deadServerRetries", deadServerRetries, 1, MAX_DEAD_SERVER_RETRIES);
        requireRange("backupCopies", backupCopies, 0, MAX_BACKUP_COPIES);
        requireRange("ipFilterLevel", ipFilterLevel, 0, MAX_IP_FILTER_LEVEL);
        Objects.requireNonNull(nextDownload, "nextDownload");
        Objects.requireNonNull(messageFilter, "messageFilter");
        requireRange("newConnectionsPerFiveSeconds", newConnectionsPerFiveSeconds, 1, MAX_NEW_CONNECTIONS);
        requireRange("uploadQueueSize", uploadQueueSize, MIN_UPLOAD_QUEUE, MAX_UPLOAD_QUEUE);
        Objects.requireNonNull(sharedFilesVisibility, "sharedFilesVisibility");
        videoPlayer = Objects.requireNonNull(videoPlayer, "videoPlayer").strip();
        videoPlayerArgs =
                Objects.requireNonNull(videoPlayerArgs, "videoPlayerArgs").strip();
        bindInterface = Objects.requireNonNull(bindInterface, "bindInterface").strip();
        if (bindInterface.length() > MAX_BIND_INTERFACE || bindInterface.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("bindInterface must be a short name without control characters");
        }
        Objects.requireNonNull(commentFilter, "commentFilter");
        if (commentFilter.length() > MAX_MESSAGE_FILTER) {
            throw new IllegalArgumentException("commentFilter must be at most " + MAX_MESSAGE_FILTER + " characters");
        }
        Objects.requireNonNull(nameCleanups, "nameCleanups");
        if (nameCleanups.length() > MAX_MESSAGE_FILTER) {
            throw new IllegalArgumentException("nameCleanups must be at most " + MAX_MESSAGE_FILTER + " characters");
        }
        if (messageFilter.length() > MAX_MESSAGE_FILTER) {
            throw new IllegalArgumentException("messageFilter must be at most " + MAX_MESSAGE_FILTER + " characters");
        }
        ipFilterWhitelist =
                Objects.requireNonNull(ipFilterWhitelist, "ipFilterWhitelist").strip();
        if (ipFilterWhitelist.length() > MAX_IP_FILTER_WHITELIST) {
            throw new IllegalArgumentException(
                    "ipFilterWhitelist must be at most " + MAX_IP_FILTER_WHITELIST + " characters");
        }
        Optional<String> invalid = invalidWhitelistEntry(ipFilterWhitelist);
        if (invalid.isPresent()) {
            throw new IllegalArgumentException(
                    "ipFilterWhitelist: not an address, CIDR block or range: " + invalid.get());
        }
        Objects.requireNonNull(eventCommands, "eventCommands");
        Objects.requireNonNull(notificationSoundFile, "notificationSoundFile")
                .ifPresent(p -> requireAbsolute("notificationSoundFile", p));
        ipFilterLists = Objects.requireNonNull(ipFilterLists, "ipFilterLists").strip();
        Optional<String> invalidList = invalidListEntry(ipFilterLists);
        if (invalidList.isPresent()) {
            throw new IllegalArgumentException("ipFilterLists: not an http or https address: " + invalidList.get());
        }
        if (!fitsListCount(ipFilterLists)) {
            throw new IllegalArgumentException("ipFilterLists must hold at most " + MAX_IP_FILTER_LISTS + " addresses");
        }
        requireRange("ipFilterUpdateDays", ipFilterUpdateDays, 0, MAX_IP_FILTER_UPDATE_DAYS);
        Objects.requireNonNull(updateMode, "updateMode");
        Objects.requireNonNull(updateChannel, "updateChannel");
        Objects.requireNonNull(updateInstallTime, "updateInstallTime");
        Objects.requireNonNull(nightHours, "nightHours");
        Objects.requireNonNull(updateUrl, "updateUrl")
                .filter(u -> listAddress(u.toString()).isEmpty())
                .ifPresent(u -> {
                    throw new IllegalArgumentException("updateUrl: not an http or https address: " + u);
                });
    }

    /** The first line of a list of IP filter lists that is not an http or https address with a host, if any. */
    public static Optional<String> invalidListEntry(String text) {
        for (String line : listLines(text)) {
            if (listAddress(line).isEmpty()) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether firewalled clients are met through the routers, over uTP or QUIC: such connections are plain, so not
     * where obfuscation is required, and not behind a proxy, whose relay shows another address.
     */
    public boolean routerTraversal() {
        return this.obfuscation != ObfuscationMode.REQUIRED && !this.proxy.isEnabled();
    }

    /** The addresses of the IP filter lists, in their order. */
    public List<URI> ipFilterListAddresses() {
        return listAddresses(this.ipFilterLists);
    }

    /** The http and https addresses of {@code text}, one per line, in their order; the other lines left out. */
    public static List<URI> listAddresses(String text) {
        return listLines(text).stream()
                .map(Preferences::listAddress)
                .flatMap(Optional::stream)
                .toList();
    }

    /** Whether {@code text} holds no more lists than a filter takes. */
    public static boolean fitsListCount(String text) {
        return listLines(text).size() <= MAX_IP_FILTER_LISTS;
    }

    private static List<String> listLines(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static Optional<URI> listAddress(String line) {
        if (line.length() > MAX_LIST_ADDRESS) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(line);
            String scheme = uri.getScheme();
            return scheme != null
                            && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                            && uri.getHost() != null
                    ? Optional.of(uri)
                    : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * The first line of an IP filter whitelist that is not an address ({@code 1.2.3.4}), a CIDR block
     * ({@code 1.2.3.0/24}) or an ipfilter.dat range ({@code 1.2.3.0 - 1.2.3.255}), if any.
     */
    public static Optional<String> invalidWhitelistEntry(String text) {
        return IpWhitelist.firstInvalidEntry(text);
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
                true,
                "",
                DEFAULT_DOWNLOAD_CAPACITY,
                DEFAULT_UPLOAD_CAPACITY,
                DEFAULT_MIN_FREE_SPACE,
                DEFAULT_DEAD_SERVER_RETRIES,
                false,
                false,
                false,
                false,
                false,
                DEFAULT_BACKUP_COPIES,
                DEFAULT_IP_FILTER_LEVEL,
                true,
                true,
                true,
                false,
                NextDownload.NONE,
                true,
                false,
                DEFAULT_MESSAGE_FILTER,
                true,
                true,
                DEFAULT_NEW_CONNECTIONS,
                DEFAULT_UPLOAD_QUEUE,
                SharedFilesVisibility.NOBODY,
                false,
                "",
                "",
                DEFAULT_COMMENT_FILTER,
                DEFAULT_NAME_CLEANUPS,
                false,
                "",
                EventCommands.NONE,
                false,
                Optional.empty(),
                "",
                DEFAULT_IP_FILTER_UPDATE_DAYS,
                UpdateMode.NOTIFY,
                UpdateChannel.of(NjMuleInfo.version()),
                UpdateInstallTime.EXIT,
                NightHours.DEFAULT,
                Optional.empty(),
                false,
                0,
                Notifications.NONE,
                true);
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

    /** Whether {@code nick}, already stripped, can be ours: 1 to 50 characters, none of them a control. */
    public static boolean isValidNick(String nick) {
        return !nick.isEmpty()
                && nick.length() <= MAX_NICK_LENGTH
                && nick.chars().noneMatch(c -> Character.isISOControl(c) || isBidiControl(c));
    }

    // Embeddings, overrides and isolates would reverse the text shown after the nick in other users' lists.
    private static boolean isBidiControl(int c) {
        return (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
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
        private String bindInterface;
        private int downloadCapacityKiBps;
        private int uploadCapacityKiBps;
        private int minFreeSpaceMiB;
        private int deadServerRetries;
        private boolean addServersFromServer;
        private boolean addServersFromClients;
        private boolean staticServersOnly;
        private boolean manualServersHighPriority;
        private boolean updateServersAtStart;
        private int backupCopies;
        private int ipFilterLevel;
        private boolean rememberDownloadedFiles;
        private boolean rememberCancelledFiles;
        private boolean storeSearches;
        private boolean addDownloadsPaused;
        private NextDownload nextDownload;
        private boolean filterLanAddresses;
        private boolean messagesFromFriendsOnly;
        private String messageFilter;
        private boolean advancedSpamFilter;
        private boolean searchSpamFilter;
        private int newConnectionsPerFiveSeconds;
        private int uploadQueueSize;
        private SharedFilesVisibility sharedFilesVisibility;
        private boolean previewFirst;
        private String videoPlayer;
        private String videoPlayerArgs;
        private String commentFilter;
        private String nameCleanups;
        private boolean cleanUpNewNames;
        private String ipFilterWhitelist;
        private EventCommands eventCommands;
        private boolean notificationSound;
        private Optional<Path> notificationSoundFile;
        private String ipFilterLists;
        private int ipFilterUpdateDays;
        private UpdateMode updateMode;
        private UpdateChannel updateChannel;
        private UpdateInstallTime updateInstallTime;
        private NightHours nightHours;
        private Optional<URI> updateUrl;
        private boolean preventSleep;
        private int autoDeleteDays;
        private boolean notifyEmail;
        private String smtpHost;
        private int smtpPort;
        private Notifications.SmtpSecurity smtpSecurity;
        private String smtpUser;
        private String smtpPassword;
        private String mailFrom;
        private String mailTo;
        private boolean notifyWebhook;
        private String webhookUrl;
        private Notifications.WebhookFormat webhookBody;
        private Set<EventCommands.Event> notifyEvents;
        private boolean showCountries;

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
            proxy(p.proxy);
            this.upnp = p.upnp;
            this.bindInterface = p.bindInterface;
            this.downloadCapacityKiBps = p.downloadCapacityKiBps;
            this.uploadCapacityKiBps = p.uploadCapacityKiBps;
            this.minFreeSpaceMiB = p.minFreeSpaceMiB;
            this.deadServerRetries = p.deadServerRetries;
            this.addServersFromServer = p.addServersFromServer;
            this.addServersFromClients = p.addServersFromClients;
            this.staticServersOnly = p.staticServersOnly;
            this.manualServersHighPriority = p.manualServersHighPriority;
            this.updateServersAtStart = p.updateServersAtStart;
            this.backupCopies = p.backupCopies;
            this.ipFilterLevel = p.ipFilterLevel;
            this.rememberDownloadedFiles = p.rememberDownloadedFiles;
            this.rememberCancelledFiles = p.rememberCancelledFiles;
            this.storeSearches = p.storeSearches;
            this.addDownloadsPaused = p.addDownloadsPaused;
            this.nextDownload = p.nextDownload;
            this.filterLanAddresses = p.filterLanAddresses;
            this.messagesFromFriendsOnly = p.messagesFromFriendsOnly;
            this.messageFilter = p.messageFilter;
            this.advancedSpamFilter = p.advancedSpamFilter;
            this.searchSpamFilter = p.searchSpamFilter;
            this.newConnectionsPerFiveSeconds = p.newConnectionsPerFiveSeconds;
            this.uploadQueueSize = p.uploadQueueSize;
            this.sharedFilesVisibility = p.sharedFilesVisibility;
            this.previewFirst = p.previewFirst;
            this.videoPlayer = p.videoPlayer;
            this.videoPlayerArgs = p.videoPlayerArgs;
            this.commentFilter = p.commentFilter;
            this.nameCleanups = p.nameCleanups;
            this.cleanUpNewNames = p.cleanUpNewNames;
            this.ipFilterWhitelist = p.ipFilterWhitelist;
            this.eventCommands = p.eventCommands;
            this.notificationSound = p.notificationSound;
            this.notificationSoundFile = p.notificationSoundFile;
            this.ipFilterLists = p.ipFilterLists;
            this.ipFilterUpdateDays = p.ipFilterUpdateDays;
            this.updateMode = p.updateMode;
            this.updateChannel = p.updateChannel;
            this.updateInstallTime = p.updateInstallTime;
            this.nightHours = p.nightHours;
            this.updateUrl = p.updateUrl;
            this.preventSleep = p.preventSleep;
            this.autoDeleteDays = p.autoDeleteDays;
            notifications(p.notifications);
            this.showCountries = p.showCountries;
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

        public Builder bindInterface(String value) {
            this.bindInterface = value;
            return this;
        }

        public Builder downloadCapacityKiBps(int value) {
            this.downloadCapacityKiBps = value;
            return this;
        }

        public Builder minFreeSpaceMiB(int value) {
            this.minFreeSpaceMiB = value;
            return this;
        }

        public Builder uploadCapacityKiBps(int value) {
            this.uploadCapacityKiBps = value;
            return this;
        }

        public Builder deadServerRetries(int value) {
            this.deadServerRetries = value;
            return this;
        }

        public Builder addServersFromServer(boolean value) {
            this.addServersFromServer = value;
            return this;
        }

        public Builder addServersFromClients(boolean value) {
            this.addServersFromClients = value;
            return this;
        }

        public Builder staticServersOnly(boolean value) {
            this.staticServersOnly = value;
            return this;
        }

        public Builder manualServersHighPriority(boolean value) {
            this.manualServersHighPriority = value;
            return this;
        }

        public Builder updateServersAtStart(boolean value) {
            this.updateServersAtStart = value;
            return this;
        }

        public Builder backupCopies(int value) {
            this.backupCopies = value;
            return this;
        }

        public Builder ipFilterLevel(int value) {
            this.ipFilterLevel = value;
            return this;
        }

        public Builder rememberDownloadedFiles(boolean value) {
            this.rememberDownloadedFiles = value;
            return this;
        }

        public Builder rememberCancelledFiles(boolean value) {
            this.rememberCancelledFiles = value;
            return this;
        }

        public Builder storeSearches(boolean value) {
            this.storeSearches = value;
            return this;
        }

        public Builder addDownloadsPaused(boolean value) {
            this.addDownloadsPaused = value;
            return this;
        }

        public Builder nextDownload(NextDownload value) {
            this.nextDownload = value;
            return this;
        }

        public Builder filterLanAddresses(boolean value) {
            this.filterLanAddresses = value;
            return this;
        }

        public Builder messagesFromFriendsOnly(boolean value) {
            this.messagesFromFriendsOnly = value;
            return this;
        }

        public Builder messageFilter(String value) {
            this.messageFilter = value;
            return this;
        }

        public Builder advancedSpamFilter(boolean value) {
            this.advancedSpamFilter = value;
            return this;
        }

        public Builder searchSpamFilter(boolean value) {
            this.searchSpamFilter = value;
            return this;
        }

        public Builder newConnectionsPerFiveSeconds(int value) {
            this.newConnectionsPerFiveSeconds = value;
            return this;
        }

        public Builder uploadQueueSize(int value) {
            this.uploadQueueSize = value;
            return this;
        }

        public Builder sharedFilesVisibility(SharedFilesVisibility value) {
            this.sharedFilesVisibility = value;
            return this;
        }

        public Builder previewFirst(boolean value) {
            this.previewFirst = value;
            return this;
        }

        public Builder videoPlayer(String value) {
            this.videoPlayer = value;
            return this;
        }

        public Builder videoPlayerArgs(String value) {
            this.videoPlayerArgs = value;
            return this;
        }

        public Builder commentFilter(String value) {
            this.commentFilter = value;
            return this;
        }

        public Builder nameCleanups(String value) {
            this.nameCleanups = value;
            return this;
        }

        public Builder cleanUpNewNames(boolean value) {
            this.cleanUpNewNames = value;
            return this;
        }

        public Builder ipFilterWhitelist(String value) {
            this.ipFilterWhitelist = value;
            return this;
        }

        public Builder eventCommands(EventCommands value) {
            this.eventCommands = value;
            return this;
        }

        /** Turns the command of {@code event} on or off, keeping its command line. */
        public Builder eventEnabled(EventCommands.Event event, boolean value) {
            EventCommands.Command command = this.eventCommands.get(event);
            this.eventCommands =
                    this.eventCommands.with(event, new EventCommands.Command(value, command.commandLine()));
            return this;
        }

        /** Sets the command line of {@code event}, keeping whether it runs. */
        public Builder eventCommandLine(EventCommands.Event event, String value) {
            EventCommands.Command command = this.eventCommands.get(event);
            this.eventCommands = this.eventCommands.with(event, new EventCommands.Command(command.enabled(), value));
            return this;
        }

        public Builder notificationSound(boolean value) {
            this.notificationSound = value;
            return this;
        }

        public Builder notificationSoundFile(Optional<Path> value) {
            this.notificationSoundFile = value;
            return this;
        }

        public Builder ipFilterLists(String value) {
            this.ipFilterLists = value;
            return this;
        }

        public Builder ipFilterUpdateDays(int value) {
            this.ipFilterUpdateDays = value;
            return this;
        }

        public Builder updateMode(UpdateMode value) {
            this.updateMode = value;
            return this;
        }

        public Builder updateChannel(UpdateChannel value) {
            this.updateChannel = value;
            return this;
        }

        public Builder updateInstallTime(UpdateInstallTime value) {
            this.updateInstallTime = value;
            return this;
        }

        public Builder nightHours(NightHours value) {
            this.nightHours = value;
            return this;
        }

        public Builder preventSleep(boolean value) {
            this.preventSleep = value;
            return this;
        }

        public Builder autoDeleteDays(int value) {
            this.autoDeleteDays = value;
            return this;
        }

        public Builder notifications(Notifications value) {
            this.notifyEmail = value.email();
            this.smtpHost = value.smtpHost();
            this.smtpPort = value.smtpPort();
            this.smtpSecurity = value.security();
            this.smtpUser = value.smtpUser();
            this.smtpPassword = value.smtpPassword();
            this.mailFrom = value.mailFrom();
            this.mailTo = value.mailTo();
            this.notifyWebhook = value.webhook();
            this.webhookUrl = value.webhookUrl();
            this.webhookBody = value.webhookBody();
            this.notifyEvents = value.events();
            return this;
        }

        public Builder showCountries(boolean value) {
            this.showCountries = value;
            return this;
        }

        public Builder notifyEmail(boolean value) {
            this.notifyEmail = value;
            return this;
        }

        public Builder smtpHost(String value) {
            this.smtpHost = value;
            return this;
        }

        public Builder smtpPort(int value) {
            this.smtpPort = value;
            return this;
        }

        public Builder smtpSecurity(Notifications.SmtpSecurity value) {
            this.smtpSecurity = value;
            return this;
        }

        public Builder smtpUser(String value) {
            this.smtpUser = value;
            return this;
        }

        public Builder smtpPassword(String value) {
            this.smtpPassword = value;
            return this;
        }

        public Builder mailFrom(String value) {
            this.mailFrom = value;
            return this;
        }

        public Builder mailTo(String value) {
            this.mailTo = value;
            return this;
        }

        public Builder notifyWebhook(boolean value) {
            this.notifyWebhook = value;
            return this;
        }

        public Builder webhookUrl(String value) {
            this.webhookUrl = value;
            return this;
        }

        public Builder webhookBody(Notifications.WebhookFormat value) {
            this.webhookBody = value;
            return this;
        }

        public Builder notifyEvents(Set<EventCommands.Event> value) {
            this.notifyEvents = Set.copyOf(value);
            return this;
        }

        public Builder updateUrl(Optional<URI> value) {
            this.updateUrl = value;
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
                    this.upnp,
                    this.bindInterface,
                    this.downloadCapacityKiBps,
                    this.uploadCapacityKiBps,
                    this.minFreeSpaceMiB,
                    this.deadServerRetries,
                    this.addServersFromServer,
                    this.addServersFromClients,
                    this.staticServersOnly,
                    this.manualServersHighPriority,
                    this.updateServersAtStart,
                    this.backupCopies,
                    this.ipFilterLevel,
                    this.rememberDownloadedFiles,
                    this.rememberCancelledFiles,
                    this.storeSearches,
                    this.addDownloadsPaused,
                    this.nextDownload,
                    this.filterLanAddresses,
                    this.messagesFromFriendsOnly,
                    this.messageFilter,
                    this.advancedSpamFilter,
                    this.searchSpamFilter,
                    this.newConnectionsPerFiveSeconds,
                    this.uploadQueueSize,
                    this.sharedFilesVisibility,
                    this.previewFirst,
                    this.videoPlayer,
                    this.videoPlayerArgs,
                    this.commentFilter,
                    this.nameCleanups,
                    this.cleanUpNewNames,
                    this.ipFilterWhitelist,
                    this.eventCommands,
                    this.notificationSound,
                    this.notificationSoundFile,
                    this.ipFilterLists,
                    this.ipFilterUpdateDays,
                    this.updateMode,
                    this.updateChannel,
                    this.updateInstallTime,
                    this.nightHours,
                    this.updateUrl,
                    this.preventSleep,
                    this.autoDeleteDays,
                    new Notifications(
                            this.notifyEmail,
                            this.smtpHost,
                            this.smtpPort,
                            this.smtpSecurity,
                            this.smtpUser,
                            this.smtpPassword,
                            this.mailFrom,
                            this.mailTo,
                            this.notifyWebhook,
                            this.webhookUrl,
                            this.webhookBody,
                            this.notifyEvents),
                    this.showCountries);
        }
    }
}
