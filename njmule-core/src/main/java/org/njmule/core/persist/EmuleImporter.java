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
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
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

        /** Downloads on from a copy of another client's part file; returns the file name. */
        String adoptDownload(Path met, Path data) throws IOException;
    }

    /** Under the config directory: the identity files waiting for the next start. */
    public static final String PENDING_IDENTITY = "import-identity";

    private static final Logger logger = LoggerFactory.getLogger(EmuleImporter.class);
    private static final String SECTION = "eMule";
    private static final String PROXY = "Proxy";
    // Limits at or above this mean "no limit".
    private static final long UNLIMITED = 65_535;
    private static final String KAD_KEY_FILE = "kadudpkey.dat";
    private static final List<String> IDENTITY =
            List.of(UserHashStore.FILE_NAME, "cryptkey.dat", "clients.met", "preferencesKad.dat", KAD_KEY_FILE);

    private final Path emule;
    private final ImportOptions options;
    private final Target target;
    private final BooleanSupplier emuleRunning;
    private final List<ImportReport.Item> items = new ArrayList<>();

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
        for (String name : IDENTITY) {
            Path file = pending.resolve(name);
            if (Files.exists(file)) {
                AtomicFiles.writeBytes(configDir.resolve(name), Files.readAllBytes(file));
                Files.delete(file);
            }
        }
        Files.delete(pending);
        logger.info("Using the identity imported from eMule");
        return true;
    }

    public ImportReport run() {
        Optional<IniFile> ini = this.preferences();
        this.with(this.emule.resolve("server.met"), this::servers);
        this.with(this.emule.resolve("nodes.dat"), this::nodes);
        this.with(this.emule.resolve("known.met"), this::known);
        this.sharedDirectories(ini);
        this.with(this.emule.resolve("sharedfiles.dat"), EmuleImporter::sharedFiles);
        this.with(this.emule.resolve("ipfilter.dat"), this::ipFilter);
        this.kept("cancelled.met", data -> CancelledMet.read(data).keys().size() + " files");
        this.kept("emfriends.met", data -> FriendsMet.read(data).size() + " friends");
        this.kept("StoredSearches.met", data -> SearchFiles.readStored(data).size() + " searches");
        this.kept("SearchSpam.met", data -> SearchFiles.readSpam(data).size() + " markers");
        this.kept("staticservers.dat", data -> TextFiles.staticServers(data).size() + " servers");
        this.kept("addresses.dat", data -> TextFiles.lines(data).size() + " addresses");
        this.kept("Category.ini", data -> IniFile.parse(data).sections().size() + " sections");
        this.kept("statistics.ini", data -> IniFile.parse(data).sections().size() + " sections");
        for (String name : List.of("key_index.dat", "src_index.dat", "load_index.dat")) {
            Path file = this.emule.resolve(name);
            if (Files.exists(file)) {
                this.report(file, ImportReport.Outcome.SKIPPED, "other nodes' entries, short-lived");
            }
        }
        this.identity(ini);
        this.downloads(ini);
        return new ImportReport(this.options.dryRun(), this.items);
    }

    // Nick, limits, networks, obfuscation and credits; ports and directories stay njMule's own.
    private Optional<IniFile> preferences() {
        Path file = this.emule.resolve("preferences.ini");
        if (!Files.exists(file)) {
            this.report(file, ImportReport.Outcome.MISSING, "");
            return Optional.empty();
        }
        IniFile ini;
        try {
            ini = IniFile.parse(Files.readAllBytes(file));
        } catch (IOException e) {
            this.report(file, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
            return Optional.empty();
        }
        if (!ini.hasSection(SECTION)) {
            this.report(file, ImportReport.Outcome.FAILED, "no [" + SECTION + "] section");
            return Optional.empty();
        }
        String detail = "nick, limits, networks, obfuscation; ports and directories stay ours";
        if (this.options.dryRun()) {
            this.report(file, ImportReport.Outcome.WOULD_IMPORT, detail);
            return Optional.of(ini);
        }
        try {
            this.target.updatePreferences(p -> mapPreferences(ini, p));
            this.report(file, ImportReport.Outcome.IMPORTED, detail);
        } catch (IOException | RuntimeException e) {
            this.report(file, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
        }
        return Optional.of(ini);
    }

    /** Our preferences with eMule's values where it has usable ones. */
    static Preferences mapPreferences(IniFile ini, Preferences current) {
        Preferences.Builder b = current.toBuilder();
        ini.get(SECTION, "Nick")
                .map(String::strip)
                .filter(n -> !n.isEmpty() && n.length() <= Preferences.MAX_NICK_LENGTH)
                .filter(n -> n.chars().noneMatch(Character::isISOControl))
                .ifPresent(b::nick);
        limit(ini, "MaxUpload").ifPresent(v -> b.maxUploadKiBps((int) v));
        limit(ini, "MaxDownload").ifPresent(v -> b.maxDownloadKiBps((int) v));
        // An unknown capacity is stored as "unlimited", which says nothing about the line.
        capacity(ini, "DownloadCapacity").ifPresent(b::downloadCapacityKiBps);
        capacity(ini, "UploadCapacityNew").ifPresent(b::uploadCapacityKiBps);
        // eMule keeps a minimum in bytes, used only while its check is on; ours is always on.
        if (ini.getBoolean(SECTION, "CheckDiskspace").orElse(false)) {
            ini.getLong(SECTION, "MinFreeDiskSpace")
                    .ifPresent(
                            v -> b.minFreeSpaceMiB(Math.clamp(v / (1024 * 1024), 0, Preferences.MAX_MIN_FREE_SPACE)));
        }
        ini.getLong(SECTION, "MaxConnections").ifPresent(v -> b.maxConnections(Math.clamp(v, 1, 10_000)));
        ini.getLong(SECTION, "MaxSourcesPerFile").ifPresent(v -> b.maxSourcesPerFile(Math.clamp(v, 1, 10_000)));
        ini.getBoolean(SECTION, "Reconnect").ifPresent(b::reconnect);
        ini.getBoolean(SECTION, "Autoconnect").ifPresent(b::autoConnect);
        ini.getBoolean(SECTION, "NetworkED2K").ifPresent(b::ed2kEnabled);
        ini.getBoolean(SECTION, "NetworkKademlia").ifPresent(b::kadEnabled);
        ini.getBoolean(SECTION, "SecureIdent").ifPresent(b::secureIdent);
        ini.getBoolean(SECTION, "UseCreditSystem").ifPresent(b::creditSystem);
        b.obfuscation(obfuscation(ini, current.obfuscation()));
        proxy(ini).ifPresent(b::proxy);
        return b.build();
    }

    // eMule's proxy types: 1-3 SOCKS 4, 4a and 5, 4-5 HTTP 1.0 and 1.1.
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
            return item(file, ImportReport.Outcome.WOULD_IMPORT, count + " servers");
        }
        int added = this.target.addServers(file);
        return item(file, ImportReport.Outcome.IMPORTED, count + " servers, " + added + " new");
    }

    private ImportReport.Item nodes(Path file, byte[] data) throws IOException {
        NodesDat.Contents contents = NodesDat.read(data);
        int count = contents.contacts().size();
        String what = count + (contents.bootstrap() ? " bootstrap nodes" : " Kad contacts");
        if (this.options.dryRun()) {
            return item(file, ImportReport.Outcome.WOULD_IMPORT, what);
        }
        int added = this.target.addKadContacts(contents);
        return item(file, ImportReport.Outcome.IMPORTED, what + ", " + added + " new");
    }

    private ImportReport.Item known(Path file, byte[] data) throws IOException {
        List<KnownMet.KnownFile> files = KnownMet.read(data);
        List<Known2Met.Entry> trees = List.of();
        Path aich = this.emule.resolve("known2_64.met");
        if (Files.exists(aich)) {
            trees = Known2Met.read(Files.readAllBytes(aich));
        }
        String what = files.size() + " known files, " + trees.size() + " AICH trees";
        if (this.options.dryRun()) {
            return item(file, ImportReport.Outcome.WOULD_IMPORT, what);
        }
        int added = this.target.addKnownFiles(files, trees);
        return item(file, ImportReport.Outcome.IMPORTED, what + ", " + added + " new");
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
                for (String entry : TextFiles.entries(Files.readAllBytes(file))) {
                    path(entry).filter(Files::isDirectory).ifPresent(dirs::add);
                }
            } catch (IOException | RuntimeException e) {
                this.report(file, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
                return;
            }
        }
        if (dirs.isEmpty()) {
            this.report(file, ImportReport.Outcome.MISSING, "no shared directory");
            return;
        }
        if (this.options.dryRun()) {
            this.report(file, ImportReport.Outcome.WOULD_IMPORT, dirs.size() + " directories");
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
            this.report(file, ImportReport.Outcome.FAILED, failure);
        } else {
            int skipped = dirs.size() - shared;
            this.report(
                    file,
                    ImportReport.Outcome.IMPORTED,
                    shared + " directories" + (skipped == 0 ? "" : ", " + skipped + " not shared: " + failure));
        }
    }

    private static ImportReport.Item sharedFiles(Path file, byte[] data) {
        long single =
                TextFiles.sharedFiles(data).stream().filter(s -> !s.excluded()).count();
        return item(file, ImportReport.Outcome.SKIPPED, single + " single files: not shared yet");
    }

    private ImportReport.Item ipFilter(Path file, byte[] data) throws IOException {
        int ranges = IpFilterDat.read(data, String.valueOf(file.getFileName())).size();
        Path ours = this.target.configDir().resolve("ipfilter.dat");
        if (Files.exists(ours)) {
            return item(file, ImportReport.Outcome.SKIPPED, ranges + " ranges; we have our own");
        }
        if (this.options.dryRun()) {
            return item(file, ImportReport.Outcome.WOULD_IMPORT, ranges + " ranges");
        }
        AtomicFiles.writeBytes(ours, data);
        return item(file, ImportReport.Outcome.IMPORTED, ranges + " ranges, from the next start");
    }

    /** Files for features to come: checked, then kept unless njMule has its own. */
    private void kept(String name, Reader reader) {
        this.with(this.emule.resolve(name), (file, data) -> {
            String what = reader.read(data);
            Path ours = this.target.configDir().resolve(name);
            if (Files.exists(ours)) {
                return item(file, ImportReport.Outcome.SKIPPED, what + "; we have our own");
            }
            if (this.options.dryRun()) {
                return item(file, ImportReport.Outcome.WOULD_IMPORT, what);
            }
            AtomicFiles.writeBytes(ours, data);
            return item(file, ImportReport.Outcome.IMPORTED, what);
        });
    }

    // The user hash, key, credits and Kad identity belong together; they wait for the next start.
    private void identity(Optional<IniFile> ini) {
        String refusal = null;
        if (!this.options.identity()) {
            refusal = "njMule keeps its own identity unless asked to adopt eMule's";
        } else if (this.emuleRunning.getAsBoolean()) {
            refusal = "close eMule first: two clients with one identity spoil their credits";
        }
        if (refusal != null) {
            for (String name : IDENTITY.subList(0, IDENTITY.size() - 1)) {
                Path file = this.emule.resolve(name);
                if (Files.exists(file)) {
                    this.report(file, ImportReport.Outcome.SKIPPED, refusal);
                }
            }
            return;
        }
        boolean hash = this.pending(UserHashStore.FILE_NAME, data -> {
            if (!PreferencesDat.isMarkedUserHash(PreferencesDat.read(data).userHash())) {
                throw new IOException("not a usable user hash");
            }
            return "user hash";
        });
        boolean key = this.pending("cryptkey.dat", data -> {
            String text = new String(data, StandardCharsets.US_ASCII);
            RsaKeys.decodePrivate(Base64.getDecoder().decode(text.replaceAll("\\s+", "")));
            return "secure identification key";
        });
        if (!hash || !key) {
            // Our key with eMule's user hash would fail every identification, and the reverse.
            this.dropPendingIdentity();
            this.report(
                    this.emule,
                    ImportReport.Outcome.SKIPPED,
                    "identity: the user hash and the key are taken only together");
            return;
        }
        this.pending("clients.met", data -> ClientsMet.read(data).size() + " credits");
        this.pending("preferencesKad.dat", data -> {
            PreferencesKadDat.read(data);
            return "Kad id";
        });
        long udpKey = ini.map(i -> i.getLong(SECTION, "KadUDPKey"))
                        .orElse(OptionalLong.empty())
                        .orElse(0)
                & 0xFFFF_FFFFL;
        if (!this.options.dryRun() && udpKey != 0) {
            Path pending = this.target.configDir().resolve(PENDING_IDENTITY);
            try {
                Files.createDirectories(pending);
                AtomicFiles.writeBytes(
                        pending.resolve(KAD_KEY_FILE),
                        new PacketWriter().u32(udpKey).toByteArray());
            } catch (IOException e) {
                this.report(pending, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
            }
        }
    }

    private void dropPendingIdentity() {
        Path pending = this.target.configDir().resolve(PENDING_IDENTITY);
        try {
            for (String name : IDENTITY) {
                Files.deleteIfExists(pending.resolve(name));
            }
            Files.deleteIfExists(pending);
        } catch (IOException e) {
            logger.warn("Cannot remove {}", pending, e);
        }
    }

    /** Checks an identity file and puts it aside for the next start; returns whether it could. */
    private boolean pending(String name, Reader check) {
        ImportReport.Outcome outcome = this.with(this.emule.resolve(name), (file, data) -> {
            String what = check.read(data);
            if (this.options.dryRun()) {
                return item(file, ImportReport.Outcome.WOULD_IMPORT, what);
            }
            Path pending = this.target.configDir().resolve(PENDING_IDENTITY);
            Files.createDirectories(pending);
            AtomicFiles.writeBytes(pending.resolve(name), data);
            return item(file, ImportReport.Outcome.IMPORTED, what + ", from the next start");
        });
        return outcome == ImportReport.Outcome.IMPORTED || outcome == ImportReport.Outcome.WOULD_IMPORT;
    }

    // Downloads are copied, never moved, and only while eMule does not write them.
    private void downloads(Optional<IniFile> ini) {
        List<Path> temps = this.tempDirectories(ini);
        Path where = temps.isEmpty() ? this.emule.resolve("Temp") : temps.getFirst();
        if (temps.isEmpty()) {
            this.report(where, ImportReport.Outcome.MISSING, "");
            return;
        }
        if (!this.options.downloads()) {
            this.report(where, ImportReport.Outcome.SKIPPED, "downloads were not asked for");
            return;
        }
        boolean running = this.emuleRunning.getAsBoolean();
        int found = 0;
        for (Path temp : temps) {
            try (DirectoryStream<Path> mets = Files.newDirectoryStream(temp, "*.part.met")) {
                for (Path met : mets) {
                    found++;
                    this.with(met, (file, data) -> this.download(file, data, running));
                }
            } catch (IOException e) {
                this.report(temp, ImportReport.Outcome.FAILED, String.valueOf(e.getMessage()));
            }
        }
        if (found == 0) {
            this.report(where, ImportReport.Outcome.MISSING, "no downloads in progress");
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
            return item(met, ImportReport.Outcome.FAILED, name + ": no data file");
        }
        if (running && !contents.paused()) {
            return item(met, ImportReport.Outcome.SKIPPED, name + ": pause it in eMule first");
        }
        long mib = Files.size(part) / (1024 * 1024);
        if (this.options.dryRun()) {
            return item(met, ImportReport.Outcome.WOULD_IMPORT, name + ", " + mib + " MiB");
        }
        String adopted = this.target.adoptDownload(met, part);
        return item(met, ImportReport.Outcome.IMPORTED, adopted + ", " + mib + " MiB copied");
    }

    private static Optional<Path> path(String text) {
        try {
            return text.isBlank() ? Optional.empty() : Optional.of(Path.of(text.strip()));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** Reads a file and says what is in it. */
    @FunctionalInterface
    private interface Reader {
        String read(byte[] data) throws IOException;
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
                item = step.run(file, Files.readAllBytes(file));
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

    private static ImportReport.Item item(Path file, ImportReport.Outcome outcome, String detail) {
        return new ImportReport.Item(String.valueOf(file.getFileName()), outcome, detail);
    }
}
