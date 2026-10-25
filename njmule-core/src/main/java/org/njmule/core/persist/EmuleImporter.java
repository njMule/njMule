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

import com.google.common.base.CharMatcher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.config.BindInterface;
import org.njmule.core.config.NextDownload;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.config.SharedFilesVisibility;
import org.njmule.core.crypto.RsaKeys;
import org.njmule.core.persist.format.CancelledMet;
import org.njmule.core.persist.format.ClientsMet;
import org.njmule.core.persist.format.FriendsMet;
import org.njmule.core.persist.format.IniFile;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.persist.format.PartMet;
import org.njmule.core.persist.format.PreferencesDat;
import org.njmule.core.persist.format.PreferencesKadDat;
import org.njmule.core.persist.format.SearchFiles;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.protocol.io.PacketWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes what njMule can use from an eMule profile. The eMule directories are only read: every file is checked before
 * anything is taken from it, and the report says what happened to each one.
 */
public final class EmuleImporter {

    /** Where the import goes; implemented by the engine, which applies changes on its thread. */
    public interface Target {
        Path configDir();

        void updatePreferences(UnaryOperator<Preferences> change) throws IOException;

        /** Adds the servers of a {@code server.met}; returns how many were new. */
        int addServers(Path serverMet) throws IOException;

        /** Adds Kad contacts; returns how many were new. */
        int addKadContacts(NodesDat.Contents contacts) throws IOException;

        /** Remembers hashes of files, so shared files need not be hashed again. */
        int addKnownFiles(List<KnownMet.KnownFile> files, List<Known2Met.Entry> trees);

        void shareDirectory(Path directory) throws IOException;

        /** Takes these categories when we have none of our own; returns how many were taken. */
        int addCategories(List<Category> categories) throws IOException;

        /**
         * Downloads on from a copy of another client's part file, in {@code category} ({@link Category#NONE} for none);
         * returns the file name.
         */
        String adoptDownload(Path met, Path data, int category) throws IOException;
    }

    /** Under the config directory: the identity files waiting for the next start. */
    public static final String PENDING_IDENTITY = "import-identity";

    /** Under the config directory: imported files that the running engine would overwrite, waiting for its start. */
    public static final String PENDING_FILES = "import-files";

    /**
     * Under the config directory: a manifest for each import, naming every file read from eMule with its size and
     * SHA-256 and what became of it.
     */
    public static final String MANIFESTS = "import";

    private static final Logger logger = LoggerFactory.getLogger(EmuleImporter.class);
    private static final DateTimeFormatter MANIFEST_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String SECTION = "eMule";
    private static final String PROXY = "Proxy";
    // Limits at or above this mean "no limit".
    private static final long UNLIMITED = 65_535;
    // Far beyond the files of real profiles, and still well within the memory of the engine.
    private static final long MAX_FILE_SIZE = 256L * 1024 * 1024;
    private static final String KAD_KEY_FILE = "kadudpkey.dat";
    private static final String KEY_FILE = "cryptkey.dat";
    private static final List<String> IDENTITY =
            List.of(UserHashStore.FILE_NAME, KEY_FILE, "clients.met", "preferencesKad.dat", KAD_KEY_FILE);
    // Kept up to date by the engine while it runs, which would save its own over them.
    private static final Set<String> LIVE = Set.of(
            "cancelled.met",
            "emfriends.met",
            "StoredSearches.met",
            "SearchSpam.met",
            "fileinfo.ini",
            "staticservers.dat");

    private final Path emule;
    private final ImportOptions options;
    private final Target target;
    private final BooleanSupplier emuleRunning;
    private final List<ImportReport.Item> items = new ArrayList<>();
    // The size and SHA-256 of each file read, by name, for the manifest.
    private final Map<String, String> fingerprints = new LinkedHashMap<>();
    // The ids of eMule's categories taken over: only downloads in these keep theirs.
    private final Set<Integer> categoriesTaken = new HashSet<>();

    /**
     * @param emule eMule's configuration directory
     * @param emuleRunning whether an eMule runs now
     */
    public EmuleImporter(Path emule, ImportOptions options, Target target, BooleanSupplier emuleRunning) {
        this.emule = Objects.requireNonNull(emule, "emule");
        this.options = Objects.requireNonNull(options, "options");
        this.target = Objects.requireNonNull(target, "target");
        this.emuleRunning = Objects.requireNonNull(emuleRunning, "emuleRunning");
    }

    /** Whether an eMule runs on this computer. */
    public static boolean isEmuleRunning() {
        return ProcessHandle.allProcesses()
                .anyMatch(p -> p.info()
                        .command()
                        .map(c -> c.toLowerCase(Locale.ROOT).endsWith("emule.exe"))
                        .orElse(false));
    }

    /**
     * Before the engine reads its identity: moves an imported identity into {@code configDir}, unless an eMule runs,
     * since two clients with one identity spoil each other's credits.
     *
     * @return whether an identity was taken over
     */
    public static boolean applyPendingIdentity(Path configDir, BooleanSupplier emuleRunning) throws IOException {
        Path pending = configDir.resolve(PENDING_IDENTITY);
        if (!Files.isDirectory(pending)) {
            return false;
        }
        if (emuleRunning.getAsBoolean()) {
            logger.warn("eMule runs: its identity is taken over once it is closed");
            return false;
        }
        // Our key with another user hash would fail every identification, and the reverse.
        boolean complete = Files.isRegularFile(pending.resolve(UserHashStore.FILE_NAME))
                && Files.isRegularFile(pending.resolve(KEY_FILE));
        if (complete) {
            // Every file is in place before the folder goes: a start that fails half way takes all of them again.
            for (String name : IDENTITY) {
                Path file = pending.resolve(name);
                if (Files.isRegularFile(file)) {
                    AtomicFiles.writeSecret(configDir.resolve(name), Files.readAllBytes(file));
                }
            }
            logger.info("Using the identity imported from eMule");
        } else {
            logger.warn("Leaving out the identity imported from eMule: its user hash or key is missing");
        }
        removeFolder(pending);
        return complete;
    }

    /**
     * Before the engine reads its files: puts the imported ones it keeps up to date in place, over what it saved since
     * the import.
     *
     * @return how many were taken
     */
    public static int applyPendingFiles(Path configDir) {
        Path pending = configDir.resolve(PENDING_FILES);
        if (!Files.isDirectory(pending)) {
            return 0;
        }
        int taken = 0;
        try {
            for (String name : LIVE) {
                Path file = pending.resolve(name);
                if (Files.isRegularFile(file)) {
                    AtomicFiles.writeBytes(configDir.resolve(name), Files.readAllBytes(file));
                    taken++;
                }
            }
        } catch (IOException e) {
            logger.warn("Cannot take the files imported from eMule, trying again at the next start", e);
            return taken;
        }
        logger.info("Using {} files imported from eMule", taken);
        removeFolder(pending);
        return taken;
    }

    // With what an interrupted or repeated import left in it, folders too, children before their parent; a folder that
    // stays is taken again at the next start.
    private static void removeFolder(Path folder) {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(folder)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            logger.warn("Cannot remove {}", folder, e);
        }
    }

    public ImportReport run() {
        // njMule writes into its configuration directory, which must never be eMule's.
        if (canonical(this.target.configDir()).startsWith(canonical(this.emule))) {
            report(this.emule, ImportReport.Outcome.FAILED, phrase("insideEmule"));
            return new ImportReport(this.options.dryRun(), this.items);
        }
        Optional<IniFile> ini = preferences();
        with(this.emule.resolve("server.met"), this::servers);
        with(this.emule.resolve("nodes.dat"), this::nodes);
        with(this.emule.resolve("known.met"), this::known);
        sharedDirectories(ini);
        with(this.emule.resolve("sharedfiles.dat"), EmuleImporter::sharedFiles);
        with(this.emule.resolve("ipfilter.dat"), this::ipFilter);
        kept(
                "cancelled.met",
                data -> phrase("files", CancelledMet.read(data).keys().size()));
        kept("emfriends.met", data -> phrase("friends", FriendsMet.read(data).size()));
        kept(
                "StoredSearches.met",
                data -> phrase("searches", SearchFiles.readStored(data).size()));
        kept(
                "SearchSpam.met",
                data -> phrase("spamMarkers", SearchFiles.readSpam(data).size()));
        kept(
                "fileinfo.ini",
                data -> phrase("comments", IniFile.parse(data).sections().size()));
        kept(
                "staticservers.dat",
                data -> phrase("servers", TextFiles.staticServers(data).size()));
        kept("addresses.dat", data -> phrase("addresses", TextFiles.lines(data).size()));
        // The addresses typed in eMule's boxes, which the interface offers again.
        for (String history : List.of(
                "AC_ServerMetURLs.dat", "AC_IPFilterUpdateURLs.dat", "AC_BootstrapIPs.dat", "AC_SearchStrings.dat")) {
            kept(history, data -> phrase("entries", TextFiles.entries(data).size()));
        }
        categories(ini);
        kept(
                "statistics.ini",
                data -> phrase("sections", IniFile.parse(data).sections().size()));
        for (String name : List.of("key_index.dat", "src_index.dat", "load_index.dat")) {
            Path file = this.emule.resolve(name);
            if (Files.exists(file)) {
                report(file, ImportReport.Outcome.SKIPPED, phrase("shortLived"));
            }
        }
        identity(ini);
        downloads(ini);
        // Nothing to list when no file of the profile was there to read.
        if (!this.options.dryRun() && !this.fingerprints.isEmpty()) {
            writeManifest();
        }
        return new ImportReport(
                this.options.dryRun(),
                this.items,
                ini.map(EmuleImporter::startsAtLogin).orElse(false));
    }

    // Starting with the system is not a preference of ours but a setting of the system, which the desktop app makes.
    private static boolean startsAtLogin(IniFile ini) {
        return ini.getBoolean(SECTION, "AutoStart").orElse(false);
    }

    // Nick, limits, networks, obfuscation and credits; ports and directories stay njMule's own.
    private Optional<IniFile> preferences() {
        Path file = this.emule.resolve("preferences.ini");
        if (!Files.exists(file)) {
            report(file, ImportReport.Outcome.MISSING, "");
            return Optional.empty();
        }
        IniFile ini;
        try {
            ini = IniFile.parse(read(file));
        } catch (IOException e) {
            report(file, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
            return Optional.empty();
        }
        if (!ini.hasSection(SECTION)) {
            report(file, ImportReport.Outcome.FAILED, phrase("noSection", SECTION));
            return Optional.empty();
        }
        List<ImportReport.Phrase> detail = startsAtLogin(ini)
                ? List.of(phrase("preferences"), phrase("startAtLogin"), phrase("portsOurs"))
                : List.of(phrase("preferences"), phrase("portsOurs"));
        if (this.options.dryRun()) {
            this.items.add(new ImportReport.Item(name(file), ImportReport.Outcome.WOULD_IMPORT, detail));
            return Optional.of(ini);
        }
        try {
            this.target.updatePreferences(p -> mapPreferences(ini, p));
            this.items.add(new ImportReport.Item(name(file), ImportReport.Outcome.IMPORTED, detail));
        } catch (IOException | RuntimeException e) {
            report(file, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
        }
        return Optional.of(ini);
    }

    /** Our preferences with eMule's values where it has usable ones. */
    static Preferences mapPreferences(IniFile ini, Preferences current) {
        Preferences.Builder b = current.toBuilder();
        ini.get(SECTION, "Nick")
                .map(String::strip)
                .filter(Preferences::isValidNick)
                .ifPresent(b::nick);
        limit(ini, "MaxUpload").ifPresent(v -> b.maxUploadKiBps((int) v));
        limit(ini, "MaxDownload").ifPresent(v -> b.maxDownloadKiBps((int) v));
        // An unknown capacity is stored as "unlimited", which says nothing about the line.
        capacity(ini, "DownloadCapacity").ifPresent(b::downloadCapacityKiBps);
        capacity(ini, "UploadCapacityNew").ifPresent(b::uploadCapacityKiBps);
        // eMule keeps a minimum in bytes, used only while its check is on; ours is always on. The unsigned 32-bit
        // count is written signed, so 2 GiB and more come out negative.
        if (ini.getBoolean(SECTION, "CheckDiskspace").orElse(false)) {
            ini.getLong(SECTION, "MinFreeDiskSpace")
                    .ifPresent(v -> b.minFreeSpaceMiB(Math.clamp(
                            (v < 0 ? v & 0xFFFF_FFFFL : v) / (1024 * 1024), 0, Preferences.MAX_MIN_FREE_SPACE)));
        }
        ini.getLong(SECTION, "MaxConnections").ifPresent(v -> b.maxConnections(Math.clamp(v, 1, 10_000)));
        ini.getLong(SECTION, "MaxSourcesPerFile").ifPresent(v -> b.maxSourcesPerFile(Math.clamp(v, 1, 10_000)));
        // The address eMule's sockets are bound to; 0.0.0.0 is eMule's own "any".
        ini.get(SECTION, "BindAddr")
                .map(String::strip)
                .filter(BindInterface::isAddress)
                .filter(a -> !a.equals("0.0.0.0"))
                .ifPresent(b::bindInterface);
        ini.getBoolean(SECTION, "Reconnect").ifPresent(b::reconnect);
        ini.getBoolean(SECTION, "Autoconnect").ifPresent(b::autoConnect);
        ini.getLong(SECTION, "DeadServerRetry")
                .ifPresent(v -> b.deadServerRetries(Math.clamp(v, 1, Preferences.MAX_DEAD_SERVER_RETRIES)));
        ini.getBoolean(SECTION, "AddServersFromServer").ifPresent(b::addServersFromServer);
        ini.getBoolean(SECTION, "AddServersFromClient").ifPresent(b::addServersFromClients);
        ini.getBoolean(SECTION, "AutoConnectStaticOnly").ifPresent(b::staticServersOnly);
        ini.getBoolean(SECTION, "ManualHighPrio").ifPresent(b::manualServersHighPriority);
        ini.getBoolean(SECTION, "Serverlist").ifPresent(b::updateServersAtStart);
        ini.getBoolean(SECTION, "RememberDownloadedFiles").ifPresent(b::rememberDownloadedFiles);
        ini.getBoolean(SECTION, "RememberCancelledFiles").ifPresent(b::rememberCancelledFiles);
        ini.getBoolean(SECTION, "StoreSearches").ifPresent(b::storeSearches);
        ini.getBoolean(SECTION, "AddNewFilesPaused").ifPresent(b::addDownloadsPaused);
        ini.getLong(SECTION, "StartNextFile").ifPresent(v -> b.nextDownload(nextDownload(v)));
        ini.getBoolean(SECTION, "FilterBadIPs").ifPresent(b::filterLanAddresses);
        ini.getBoolean(SECTION, "MessagesFromFriendsOnly").ifPresent(b::messagesFromFriendsOnly);
        ini.getBoolean(SECTION, "EnableSearchResultSpamFilter").ifPresent(b::searchSpamFilter);
        ini.get(SECTION, "MessageFilter")
                .map(v -> v.length() > Preferences.MAX_MESSAGE_FILTER
                        ? v.substring(0, Preferences.MAX_MESSAGE_FILTER)
                        : v)
                .ifPresent(b::messageFilter);
        ini.getBoolean(SECTION, "AdvancedSpamFilter").ifPresent(b::advancedSpamFilter);
        ini.get(SECTION, "CommentFilter")
                .filter(v -> v.length() <= Preferences.MAX_MESSAGE_FILTER)
                .ifPresent(b::commentFilter);
        ini.get(SECTION, "FilenameCleanups")
                .filter(v -> v.length() <= Preferences.MAX_MESSAGE_FILTER)
                .ifPresent(b::nameCleanups);
        ini.getBoolean(SECTION, "AutoFilenameCleanup").ifPresent(b::cleanUpNewNames);
        ini.getLong(SECTION, "MaxConnectionsPerFiveSeconds")
                .ifPresent(v -> b.newConnectionsPerFiveSeconds(Math.clamp(v, 1, Preferences.MAX_NEW_CONNECTIONS)));
        ini.getBoolean(SECTION, "PreviewPrio").ifPresent(b::previewFirst);
        ini.get(SECTION, "VideoPlayer").ifPresent(b::videoPlayer);
        ini.get(SECTION, "VideoPlayerArgs").ifPresent(b::videoPlayerArgs);
        // NotifierUseSound: 0 none, 1 the sound file, 2 speech, which njMule has not.
        ini.getLong(SECTION, "NotifierUseSound").ifPresent(v -> b.notificationSound(v == 1));
        ini.get(SECTION, "NotifierSoundPath")
                .flatMap(EmuleImporter::path)
                .ifPresent(p -> b.notificationSoundFile(Optional.of(p)));
        // SeeShare: 0 everybody, 1 friends, 2 nobody.
        ini.getLong(SECTION, "SeeShare").ifPresent(v -> b.sharedFilesVisibility(visibility(v)));
        ini.getLong(SECTION, "QueueSize")
                .ifPresent(v ->
                        b.uploadQueueSize(Math.clamp(v, Preferences.MIN_UPLOAD_QUEUE, Preferences.MAX_UPLOAD_QUEUE)));
        ini.getLong(SECTION, "FilterLevel")
                .ifPresent(v -> b.ipFilterLevel(Math.clamp(v, 0, Preferences.MAX_IP_FILTER_LEVEL)));
        ini.getBoolean(SECTION, "NetworkED2K").ifPresent(b::ed2kEnabled);
        ini.getBoolean(SECTION, "NetworkKademlia").ifPresent(b::kadEnabled);
        ini.getBoolean(SECTION, "SecureIdent").ifPresent(b::secureIdent);
        ini.getBoolean(SECTION, "UseCreditSystem").ifPresent(b::creditSystem);
        b.obfuscation(obfuscation(ini, current.obfuscation()));
        proxy(ini).ifPresent(b::proxy);
        return b.build();
    }

    // eMule's proxy types: 1-3 SOCKS 4, 4a and 5, 4-5 HTTP 1.0 and 1.1.
    private static SharedFilesVisibility visibility(long value) {
        return value == 0
                ? SharedFilesVisibility.EVERYBODY
                : value == 1 ? SharedFilesVisibility.FRIENDS : SharedFilesVisibility.NOBODY;
    }

    // StartNextFile: 0 none, 3 the same category only; any other value starts one, 2 and up of the same category first.
    private static NextDownload nextDownload(long value) {
        if (value == 0) {
            return NextDownload.NONE;
        }
        if (value == 3) {
            return NextDownload.SAME_CATEGORY;
        }
        return value >= 2 ? NextDownload.SAME_CATEGORY_FIRST : NextDownload.ANY;
    }

    private static Optional<ProxySettings> proxy(IniFile ini) {
        if (!ini.getBoolean(PROXY, "ProxyEnableProxy").orElse(false)) {
            return Optional.empty();
        }
        long type = ini.getLong(PROXY, "ProxyType").orElse(0);
        if (type < 1 || type > 5) {
            return Optional.empty();
        }
        boolean password = ini.getBoolean(PROXY, "ProxyEnablePassword").orElse(false);
        try {
            return Optional.of(new ProxySettings(
                    type <= 3 ? ProxySettings.Type.SOCKS : ProxySettings.Type.HTTP,
                    ini.get(PROXY, "ProxyName").orElse(""),
                    (int) ini.getLong(PROXY, "ProxyPort").orElse(1080),
                    password ? ini.get(PROXY, "ProxyUser").orElse("") : "",
                    password ? ini.get(PROXY, "ProxyPassword").orElse("") : ""));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static OptionalInt capacity(IniFile ini, String key) {
        OptionalLong value = limit(ini, key);
        return value.isPresent() && value.getAsLong() > 0
                ? OptionalInt.of((int) Math.min(value.getAsLong(), 10_000_000))
                : OptionalInt.empty();
    }

    private static OptionalLong limit(IniFile ini, String key) {
        OptionalLong value = ini.getLong(SECTION, key);
        if (value.isEmpty()) {
            return value;
        }
        long v = value.getAsLong();
        return OptionalLong.of(v <= 0 || v >= UNLIMITED ? 0 : v);
    }

    private static ObfuscationMode obfuscation(IniFile ini, ObfuscationMode current) {
        Optional<Boolean> supported = ini.getBoolean(SECTION, "CryptLayerSupported");
        Optional<Boolean> requested = ini.getBoolean(SECTION, "CryptLayerRequested");
        Optional<Boolean> required = ini.getBoolean(SECTION, "CryptLayerRequired");
        if (supported.isEmpty() && requested.isEmpty() && required.isEmpty()) {
            return current;
        }
        return ObfuscationMode.fromFlags(supported.orElse(true), requested.orElse(false), required.orElse(false));
    }

    private ImportReport.Item servers(Path file, byte[] data) throws IOException {
        int count = ServerMet.read(data).size();
        if (this.options.dryRun()) {
            return item(file, ImportReport.Outcome.WOULD_IMPORT, phrase("servers", count));
        }
        int added = this.target.addServers(file);
        return item(file, ImportReport.Outcome.IMPORTED, phrase("servers", count), phrase("added", added));
    }

    private ImportReport.Item nodes(Path file, byte[] data) throws IOException {
        NodesDat.Contents contents = NodesDat.read(data);
        int count = contents.contacts().size();
        ImportReport.Phrase what = phrase(contents.bootstrap() ? "bootstrapNodes" : "kadContacts", count);
        if (this.options.dryRun()) {
            return item(file, ImportReport.Outcome.WOULD_IMPORT, what);
        }
        int added = this.target.addKadContacts(contents);
        return item(file, ImportReport.Outcome.IMPORTED, what, phrase("added", added));
    }

    private ImportReport.Item known(Path file, byte[] data) throws IOException {
        List<KnownMet.KnownFile> files = KnownMet.read(data);
        List<Known2Met.Entry> trees = List.of();
        List<ImportReport.Phrase> what = new ArrayList<>();
        Path aich = this.emule.resolve("known2_64.met");
        if (Files.exists(aich)) {
            try {
                trees = Known2Met.read(read(aich));
            } catch (IOException | RuntimeException e) {
                // The trees only spare hashing the shared files again.
                what.add(phrase("leftOut", name(aich), String.valueOf(e.getMessage())));
            }
        }
        what.addAll(0, List.of(phrase("knownFiles", files.size()), phrase("aichTrees", trees.size())));
        if (this.options.dryRun()) {
            return new ImportReport.Item(name(file), ImportReport.Outcome.WOULD_IMPORT, what);
        }
        what.add(phrase("added", this.target.addKnownFiles(files, trees)));
        return new ImportReport.Item(name(file), ImportReport.Outcome.IMPORTED, what);
    }

    // eMule shares its incoming directory and those of shareddir.dat, each without subdirectories.
    private void sharedDirectories(Optional<IniFile> ini) {
        Path file = this.emule.resolve("shareddir.dat");
        Set<Path> dirs = new LinkedHashSet<>();
        ini.flatMap(i -> i.get(SECTION, "IncomingDir"))
                .flatMap(EmuleImporter::path)
                .filter(Files::isDirectory)
                .ifPresent(dirs::add);
        if (Files.exists(file)) {
            try {
                for (String entry : TextFiles.entries(read(file))) {
                    path(entry).filter(Files::isDirectory).ifPresent(dirs::add);
                }
            } catch (IOException | RuntimeException e) {
                report(file, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
                return;
            }
        }
        if (dirs.isEmpty()) {
            report(file, ImportReport.Outcome.MISSING, phrase("noSharedFolder"));
            return;
        }
        if (this.options.dryRun()) {
            report(file, ImportReport.Outcome.WOULD_IMPORT, phrase("folders", dirs.size()));
            return;
        }
        // A directory refused (a private one) or failing does not stop the others.
        int shared = 0;
        String failure = "";
        for (Path dir : dirs) {
            try {
                this.target.shareDirectory(dir);
                shared++;
            } catch (IOException | RuntimeException e) {
                failure = String.valueOf(e.getMessage());
            }
        }
        if (shared == 0) {
            report(file, ImportReport.Outcome.FAILED, failure);
        } else {
            int skipped = dirs.size() - shared;
            if (skipped == 0) {
                report(file, ImportReport.Outcome.IMPORTED, phrase("folders", shared));
            } else {
                report(
                        file,
                        ImportReport.Outcome.IMPORTED,
                        phrase("folders", shared),
                        phrase("notShared", skipped, failure));
            }
        }
    }

    private static ImportReport.Item sharedFiles(Path file, byte[] data) {
        long single =
                TextFiles.sharedFiles(data).stream().filter(s -> !s.excluded()).count();
        return item(file, ImportReport.Outcome.SKIPPED, phrase("singleFiles", single));
    }

    private ImportReport.Item ipFilter(Path file, byte[] data) throws IOException {
        int ranges = IpFilterDat.read(data, String.valueOf(file.getFileName())).size();
        Path ours = this.target.configDir().resolve("ipfilter.dat");
        if (Files.exists(ours)) {
            return item(file, ImportReport.Outcome.SKIPPED, phrase("ranges", ranges), phrase("ourOwn"));
        }
        if (this.options.dryRun()) {
            return item(file, ImportReport.Outcome.WOULD_IMPORT, phrase("ranges", ranges));
        }
        AtomicFiles.writeBytes(ours, data);
        return item(file, ImportReport.Outcome.IMPORTED, phrase("ranges", ranges), phrase("nextStart"));
    }

    // eMule's categories keep their numbers, so downloads taken from its temp folder stay in theirs.
    private void categories(Optional<IniFile> preferences) {
        // eMule's own incoming folder is never ours: those categories finish into njMule's.
        Optional<Path> emuleIncoming =
                preferences.flatMap(p -> p.get(SECTION, "IncomingDir")).flatMap(EmuleImporter::path);
        with(this.emule.resolve("Category.ini"), (file, data) -> {
            IniFile ini = IniFile.parse(data);
            List<Category> found = new ArrayList<>();
            List<ImportReport.Phrase> notes = new ArrayList<>();
            for (String section : ini.sections()) {
                OptionalInt id = categoryId(section);
                // Cat#0 is the list of all downloads, not a category.
                if (id.isEmpty() || id.getAsInt() < 1) {
                    continue;
                }
                // eMule allows longer texts than ours: they are cut rather than the category lost.
                String title = shorten(ini.get(section, "Title").orElse(""), Category.MAX_NAME);
                Optional<Path> incoming = ini.get(section, "Incoming")
                        .flatMap(EmuleImporter::path)
                        .filter(dir -> !emuleIncoming.equals(Optional.of(dir)));
                String comment = shorten(ini.get(section, "Comment").orElse(""), Category.MAX_COMMENT);
                int color = color(ini.getLong(section, "Color"));
                String patterns = shorten(ini.get(section, "AutoCat").orElse(""), Category.MAX_PATTERNS);
                boolean regex =
                        ini.getBoolean(section, "AutoCatAsRegularExpression").orElse(false);
                try {
                    found.add(new Category(
                            id.getAsInt(), title, incoming, comment, color, DownloadPriority.AUTO, patterns, regex));
                } catch (IllegalArgumentException e) {
                    try {
                        // Patterns we cannot compile: the category comes without them.
                        found.add(new Category(
                                id.getAsInt(), title, incoming, comment, color, DownloadPriority.AUTO, "", false));
                        notes.add(phrase("withoutPatterns", title));
                    } catch (IllegalArgumentException again) {
                        logger.info("Leaving out the category {} of {}: {}", section, file, e.getMessage());
                    }
                }
            }
            if (found.isEmpty()) {
                return item(file, ImportReport.Outcome.SKIPPED, phrase("noCategory"));
            }
            if (this.options.dryRun()) {
                return withNotes(file, ImportReport.Outcome.WOULD_IMPORT, phrase("categories", found.size()), notes);
            }
            int added = this.target.addCategories(found);
            if (added == found.size()) {
                found.forEach(c -> this.categoriesTaken.add(c.id()));
            }
            return added == 0
                    ? item(file, ImportReport.Outcome.SKIPPED, phrase("categories", found.size()), phrase("ourOwn"))
                    : withNotes(file, ImportReport.Outcome.IMPORTED, phrase("categories", added), notes);
        });
    }

    // Stripped first, as categories keep their texts, and never between the two halves of a surrogate pair.
    private static String shorten(String text, int max) {
        String s = text.strip();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max);
    }

    // Only the name eMule writes: Cat#01 or Cat# 1 next to Cat#1 would give two categories one id.
    private static OptionalInt categoryId(String section) {
        if (!section.startsWith("Cat#")) {
            return OptionalInt.empty();
        }
        String number = section.substring(4);
        try {
            int id = Integer.parseInt(number);
            return Integer.toString(id).equals(number) ? OptionalInt.of(id) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    // eMule keeps any colour (0x00BBGGRR); ours are a few tints of the theme: the one of the nearest hue, none for
    // greys.
    static int color(OptionalLong value) {
        if (value.isEmpty() || value.getAsLong() <= 0 || value.getAsLong() > 0xFFFFFF) {
            return 0;
        }
        long c = value.getAsLong();
        int r = (int) (c & 0xFF);
        int g = (int) (c >> 8 & 0xFF);
        int b = (int) (c >> 16 & 0xFF);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max - min < 24) {
            return 0;
        }
        double delta = max - min;
        double hue;
        if (max == r) {
            hue = 60 * (((g - b) / delta) % 6);
        } else if (max == g) {
            hue = 60 * ((b - r) / delta + 2);
        } else {
            hue = 60 * ((r - g) / delta + 4);
        }
        hue = (hue + 360) % 360;
        // Red, orange, yellow, green, teal, blue, purple, pink: 45 degrees each, red around 0.
        return 1 + (int) (((hue + 22.5) % 360) / 45);
    }

    /** Files checked, then kept unless njMule has its own; those the engine keeps up to date wait for its start. */
    private void kept(String name, Reader reader) {
        with(this.emule.resolve(name), (file, data) -> {
            ImportReport.Phrase what = reader.read(data);
            Path ours = this.target.configDir().resolve(name);
            Path pending = this.target.configDir().resolve(PENDING_FILES);
            Path staged = pending.resolve(name);
            boolean live = LIVE.contains(name);
            // With a copy waiting from an earlier import, ours is only what the engine saved since.
            if (Files.exists(ours) && !(live && Files.exists(staged))) {
                return item(file, ImportReport.Outcome.SKIPPED, what, phrase("ourOwn"));
            }
            if (this.options.dryRun()) {
                return item(file, ImportReport.Outcome.WOULD_IMPORT, what);
            }
            if (live) {
                Files.createDirectories(pending);
                AtomicFiles.write(staged, out -> out.write(data), false);
                return item(file, ImportReport.Outcome.IMPORTED, what, phrase("nextStart"));
            }
            AtomicFiles.writeBytes(ours, data);
            return item(file, ImportReport.Outcome.IMPORTED, what);
        });
    }

    // The user hash, key, credits and Kad identity belong together; they wait for the next start.
    private void identity(Optional<IniFile> ini) {
        ImportReport.Phrase refusal = null;
        if (!this.options.identity()) {
            refusal = phrase("ownIdentity");
        } else if (this.emuleRunning.getAsBoolean()) {
            refusal = phrase("closeEmule");
        }
        if (refusal != null) {
            for (String name : IDENTITY.subList(0, IDENTITY.size() - 1)) {
                Path file = this.emule.resolve(name);
                if (Files.exists(file)) {
                    report(file, ImportReport.Outcome.SKIPPED, refusal);
                }
            }
            return;
        }
        if (!this.options.dryRun()) {
            // An identity waiting from an earlier import goes as a whole: its files must not mix with these.
            dropPendingIdentity();
        }
        boolean hash = pending(UserHashStore.FILE_NAME, data -> {
            if (!PreferencesDat.isMarkedUserHash(PreferencesDat.read(data).userHash())) {
                throw new IOException("not a usable user hash");
            }
            return phrase("userHash");
        });
        boolean key = pending(KEY_FILE, data -> {
            String text = new String(data, StandardCharsets.US_ASCII);
            RsaKeys.decodePrivate(Base64.getDecoder().decode(text.replaceAll("\\s+", "")));
            return phrase("identityKey");
        });
        if (!hash || !key) {
            // Our key with eMule's user hash would fail every identification, and the reverse.
            if (!this.options.dryRun()) {
                dropPendingIdentity();
            }
            report(this.emule, ImportReport.Outcome.SKIPPED, phrase("together"));
            return;
        }
        pending("clients.met", data -> phrase("credits", ClientsMet.read(data).size()));
        pending("preferencesKad.dat", data -> {
            PreferencesKadDat.read(data);
            return phrase("kadId");
        });
        long udpKey = ini.map(i -> i.getLong(SECTION, "KadUDPKey"))
                        .orElse(OptionalLong.empty())
                        .orElse(0)
                & 0xFFFF_FFFFL;
        if (!this.options.dryRun() && udpKey != 0) {
            Path pending = this.target.configDir().resolve(PENDING_IDENTITY);
            try {
                Files.createDirectories(pending);
                writePending(
                        pending.resolve(KAD_KEY_FILE),
                        new PacketWriter().u32(udpKey).toByteArray());
            } catch (IOException e) {
                report(pending, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
            }
        }
    }

    private void dropPendingIdentity() {
        removeFolder(this.target.configDir().resolve(PENDING_IDENTITY));
    }

    // Without a backup: the folder holds only what the next start takes; for its owner only, as an identity.
    private static void writePending(Path file, byte[] data) throws IOException {
        AtomicFiles.write(file, out -> out.write(data), false, true);
    }

    /** Checks an identity file and puts it aside for the next start; returns whether it could. */
    private boolean pending(String name, Reader check) {
        ImportReport.Outcome outcome = with(this.emule.resolve(name), (file, data) -> {
            ImportReport.Phrase what = check.read(data);
            if (this.options.dryRun()) {
                return item(file, ImportReport.Outcome.WOULD_IMPORT, what);
            }
            Path pending = this.target.configDir().resolve(PENDING_IDENTITY);
            Files.createDirectories(pending);
            writePending(pending.resolve(name), data);
            return item(file, ImportReport.Outcome.IMPORTED, what, phrase("nextStart"));
        });
        return outcome == ImportReport.Outcome.IMPORTED || outcome == ImportReport.Outcome.WOULD_IMPORT;
    }

    // Downloads are copied, never moved, and only while eMule does not write them.
    private void downloads(Optional<IniFile> ini) {
        List<Path> temps = tempDirectories(ini);
        Path where = temps.isEmpty() ? this.emule.resolve("Temp") : temps.getFirst();
        if (temps.isEmpty()) {
            report(where, ImportReport.Outcome.MISSING, "");
            return;
        }
        if (!this.options.downloads()) {
            report(where, ImportReport.Outcome.SKIPPED, phrase("notAsked"));
            return;
        }
        boolean running = this.emuleRunning.getAsBoolean();
        int found = 0;
        for (Path temp : temps) {
            try (DirectoryStream<Path> mets = Files.newDirectoryStream(temp, "*.part.met")) {
                for (Path met : mets) {
                    found++;
                    with(met, (file, data) -> download(file, data, running));
                }
            } catch (IOException e) {
                report(temp, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
            }
        }
        if (found == 0) {
            report(where, ImportReport.Outcome.MISSING, phrase("noDownloads"));
        }
    }

    // TempDir, then the others of TempDirs separated by '|'; next to the config by default.
    private List<Path> tempDirectories(Optional<IniFile> ini) {
        Set<Path> dirs = new LinkedHashSet<>();
        String listed = ini.flatMap(i -> i.get(SECTION, "TempDir")).orElse("")
                + "|"
                + ini.flatMap(i -> i.get(SECTION, "TempDirs")).orElse("");
        for (String entry : listed.split("\\|", -1)) {
            path(entry).filter(Files::isDirectory).ifPresent(dirs::add);
        }
        Path parent = this.emule.getParent();
        if (dirs.isEmpty() && parent != null && Files.isDirectory(parent.resolve("Temp"))) {
            dirs.add(parent.resolve("Temp"));
        }
        return List.copyOf(dirs);
    }

    private ImportReport.Item download(Path met, byte[] data, boolean running) throws IOException {
        PartMet.Contents contents = PartMet.read(data);
        String metName = String.valueOf(met.getFileName());
        String name = contents.name().orElse(metName);
        Path part = met.resolveSibling(metName.substring(0, metName.length() - ".met".length()));
        if (contents.size().isEmpty() || !Files.isRegularFile(part)) {
            return item(met, ImportReport.Outcome.FAILED, phrase("noDataFile", name));
        }
        if (running && !contents.paused()) {
            return item(met, ImportReport.Outcome.SKIPPED, phrase("pauseFirst", name));
        }
        long mib = Files.size(part) / (1024 * 1024);
        if (this.options.dryRun()) {
            return item(met, ImportReport.Outcome.WOULD_IMPORT, phrase("download", name, mib));
        }
        // eMule's number names one of ours only when that category came with this import.
        long category = contents.category().orElse(Category.NONE);
        String adopted = this.target.adoptDownload(
                met,
                part,
                category > 0 && category <= Integer.MAX_VALUE && this.categoriesTaken.contains((int) category)
                        ? (int) category
                        : Category.NONE);
        return item(met, ImportReport.Outcome.IMPORTED, phrase("copied", adopted, mib));
    }

    // Absolute only, as a relative one would name a folder below njMule's working directory; normalized, so that one
    // folder written two ways is taken once.
    private static Optional<Path> path(String text) {
        try {
            return text.isBlank()
                    ? Optional.empty()
                    : Optional.of(Path.of(text.strip()))
                            .filter(Path::isAbsolute)
                            .map(Path::normalize);
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    // A file far larger than any of a real profile is refused rather than read into memory.
    private byte[] read(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_SIZE) {
            throw new IOException("too large to be eMule's: " + size + " bytes");
        }
        byte[] data = Files.readAllBytes(file);
        this.fingerprints.put(String.valueOf(file.getFileName()), data.length + "\t" + sha256(data));
        return data;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform has SHA-256", e);
        }
    }

    // What was read and what became of it, for whoever wants to check an import later.
    private void writeManifest() {
        StringBuilder text = new StringBuilder()
                .append("# njMule import from ")
                .append(this.emule.toAbsolutePath())
                .append(" at ")
                .append(Instant.now())
                .append("\n# outcome\tbytes\tSHA-256\tfile\tdetail\n");
        for (ImportReport.Item item : this.items) {
            text.append(item.outcome())
                    .append('\t')
                    .append(this.fingerprints.getOrDefault(item.file(), "\t"))
                    .append('\t')
                    .append(item.file())
                    .append('\t')
                    .append(CharMatcher.javaIsoControl().replaceFrom(item.detail(), ' '))
                    .append('\n');
        }
        Path folder = this.target.configDir().resolve(MANIFESTS);
        try {
            Files.createDirectories(folder);
            AtomicFiles.writeBytes(
                    folder.resolve(MANIFEST_NAME.format(LocalDateTime.now(ZoneId.systemDefault())) + ".txt"),
                    text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("Cannot write the import manifest in {}: {}", folder, e.getMessage());
        }
    }

    // The nearest existing folder resolved, so that links and other spellings of one folder compare equal.
    private static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute));
        } catch (IOException e) {
            return absolute;
        }
    }

    /** Reads a file and says what is in it. */
    @FunctionalInterface
    private interface Reader {
        ImportReport.Phrase read(byte[] data) throws IOException;
    }

    @FunctionalInterface
    private interface Step {
        ImportReport.Item run(Path file, byte[] data) throws IOException;
    }

    private ImportReport.Outcome with(Path file, Step step) {
        ImportReport.Item item;
        if (!Files.exists(file)) {
            item = item(file, ImportReport.Outcome.MISSING, "");
        } else {
            try {
                item = step.run(file, read(file));
            } catch (IOException | RuntimeException e) {
                item = item(file, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
            }
        }
        this.items.add(item);
        return item.outcome();
    }

    private void report(Path file, ImportReport.Outcome outcome, String detail) {
        this.items.add(item(file, outcome, detail));
    }

    private void report(Path file, ImportReport.Outcome outcome, ImportReport.Phrase... phrases) {
        this.items.add(item(file, outcome, phrases));
    }

    private static ImportReport.Item item(Path file, ImportReport.Outcome outcome, String detail) {
        return new ImportReport.Item(name(file), outcome, detail);
    }

    private static ImportReport.Item item(Path file, ImportReport.Outcome outcome, ImportReport.Phrase... phrases) {
        return new ImportReport.Item(name(file), outcome, List.of(phrases));
    }

    private static ImportReport.Item withNotes(
            Path file, ImportReport.Outcome outcome, ImportReport.Phrase what, List<ImportReport.Phrase> notes) {
        List<ImportReport.Phrase> phrases = new ArrayList<>();
        phrases.add(what);
        phrases.addAll(notes);
        return new ImportReport.Item(name(file), outcome, phrases);
    }

    private static String name(Path file) {
        return String.valueOf(file.getFileName());
    }

    // The phrases of the report, which the interfaces translate.
    private static ImportReport.Phrase phrase(String name, Object... args) {
        return ImportReport.Phrase.of("nj.import.d." + name, args);
    }
}
