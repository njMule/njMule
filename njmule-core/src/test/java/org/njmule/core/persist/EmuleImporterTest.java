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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.ImportReport.Outcome;
import org.njmule.core.config.NextDownload;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.persist.format.IniFile;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.io.PacketReader;

class EmuleImporterTest {

    @TempDir
    Path dir;

    private SyntheticEmuleProfile emule;
    private Path ours;
    private final Recorder target = new Recorder();

    /** Records what the importer asks for. */
    private final class Recorder implements EmuleImporter.Target {
        Preferences preferences = Preferences.defaults();
        final List<Path> servers = new ArrayList<>();
        int contacts;
        int known;
        final List<Path> shared = new ArrayList<>();
        final List<Path> refused = new ArrayList<>();
        final List<String> adopted = new ArrayList<>();
        final List<Category> categories = new ArrayList<>();

        @Override
        public Path configDir() {
            return EmuleImporterTest.this.ours;
        }

        @Override
        public void updatePreferences(UnaryOperator<Preferences> change) {
            this.preferences = change.apply(this.preferences);
        }

        @Override
        public int addServers(Path serverMet) {
            this.servers.add(serverMet);
            return 2;
        }

        @Override
        public int addKadContacts(NodesDat.Contents contents) {
            this.contacts += contents.contacts().size();
            return contents.contacts().size();
        }

        @Override
        public int addKnownFiles(List<KnownMet.KnownFile> files, List<Known2Met.Entry> trees) {
            this.known += files.size();
            return files.size();
        }

        @Override
        public void shareDirectory(Path directory) {
            if (this.refused.contains(directory)) {
                throw new IllegalArgumentException("refusing " + directory);
            }
            this.shared.add(directory);
        }

        @Override
        public int addCategories(List<Category> taken) {
            this.categories.addAll(taken);
            return taken.size();
        }

        @Override
        public String adoptDownload(Path met, Path data) {
            assertThat(data).exists();
            this.adopted.add(String.valueOf(met.getFileName()));
            return String.valueOf(met.getFileName());
        }

        boolean untouched() {
            return this.servers.isEmpty()
                    && this.contacts == 0
                    && this.known == 0
                    && this.shared.isEmpty()
                    && this.adopted.isEmpty()
                    && this.preferences.equals(Preferences.defaults());
        }
    }

    @BeforeEach
    void profile() throws IOException {
        this.emule = SyntheticEmuleProfile.create(this.dir.resolve("emule"));
        this.ours = Files.createDirectories(this.dir.resolve("njmule"));
    }

    @Test
    void aDryRunReportsEverythingAndChangesNothing() throws IOException {
        SortedMap<String, String> before = this.emule.fingerprint();

        ImportReport report = this.run(new ImportOptions(true, true, true), false);

        assertThat(report.dryRun()).isTrue();
        Map<String, Outcome> outcomes = outcomes(report);
        assertThat(outcomes)
                .containsEntry("preferences.ini", Outcome.WOULD_IMPORT)
                .containsEntry("server.met", Outcome.WOULD_IMPORT)
                .containsEntry("nodes.dat", Outcome.WOULD_IMPORT)
                .containsEntry("known.met", Outcome.WOULD_IMPORT)
                .containsEntry("shareddir.dat", Outcome.WOULD_IMPORT)
                .containsEntry("sharedfiles.dat", Outcome.SKIPPED)
                .containsEntry("ipfilter.dat", Outcome.WOULD_IMPORT)
                .containsEntry("cancelled.met", Outcome.WOULD_IMPORT)
                .containsEntry("emfriends.met", Outcome.MISSING)
                .containsEntry("key_index.dat", Outcome.SKIPPED)
                .containsEntry("preferences.dat", Outcome.WOULD_IMPORT)
                .containsEntry("cryptkey.dat", Outcome.WOULD_IMPORT)
                .containsEntry("clients.met", Outcome.WOULD_IMPORT)
                .containsEntry("preferencesKad.dat", Outcome.WOULD_IMPORT)
                .containsEntry("001.part.met", Outcome.WOULD_IMPORT)
                .containsEntry("002.part.met", Outcome.WOULD_IMPORT);
        assertThat(detail(report, "server.met")).isEqualTo("2 servers");
        assertThat(detail(report, "nodes.dat")).isEqualTo("3 Kad contacts");
        assertThat(this.target.untouched()).isTrue();
        try (Stream<Path> left = Files.list(this.ours)) {
            assertThat(left.count()).isZero();
        }
        assertThat(this.emule.fingerprint()).isEqualTo(before);
    }

    @Test
    void anImportTakesEverythingAndLeavesEmuleAlone() throws IOException {
        SortedMap<String, String> before = this.emule.fingerprint();

        ImportReport report = this.run(new ImportOptions(false, true, true), false);

        Map<String, Outcome> outcomes = outcomes(report);
        assertThat(outcomes)
                .containsEntry("preferences.ini", Outcome.IMPORTED)
                .containsEntry("server.met", Outcome.IMPORTED)
                .containsEntry("preferences.dat", Outcome.IMPORTED)
                .containsEntry("001.part.met", Outcome.IMPORTED);
        assertThat(this.target.preferences.nick()).isEqualTo(SyntheticEmuleProfile.NICK);
        assertThat(this.target.preferences.maxUploadKiBps()).isEqualTo(50);
        assertThat(this.target.preferences.maxDownloadKiBps()).isZero();
        assertThat(this.target.preferences.kadEnabled()).isFalse();
        assertThat(this.target.preferences.rememberCancelledFiles()).isFalse();
        assertThat(this.target.preferences.rememberDownloadedFiles()).isTrue();
        assertThat(this.target.preferences.storeSearches()).isFalse();
        assertThat(this.target.preferences.addDownloadsPaused()).isTrue();
        assertThat(this.target.preferences.nextDownload()).isEqualTo(NextDownload.SAME_CATEGORY_FIRST);
        assertThat(this.target.preferences.filterLanAddresses()).isFalse();
        assertThat(this.target.preferences.messagesFromFriendsOnly()).isTrue();
        assertThat(this.target.preferences.messageFilter()).isEqualTo("buy now|cheap");
        assertThat(this.target.preferences.advancedSpamFilter())
                .as("not in the profile")
                .isTrue();
        assertThat(this.target.preferences.newConnectionsPerFiveSeconds()).isEqualTo(35);
        assertThat(this.target.preferences.uploadQueueSize()).isEqualTo(9000);
        assertThat(this.target.preferences.tcpPort()).isEqualTo(Preferences.DEFAULT_TCP_PORT);
        assertThat(this.target.servers).containsExactly(this.emule.config.resolve("server.met"));
        assertThat(this.target.contacts).isEqualTo(3);
        assertThat(this.target.known).isEqualTo(1);
        assertThat(this.target.shared).containsExactly(this.emule.incoming, this.emule.shared);
        assertThat(this.target.adopted).containsExactlyInAnyOrder("001.part.met", "002.part.met");
        assertThat(this.ours.resolve("cancelled.met")).exists();
        assertThat(this.ours.resolve("ipfilter.dat")).exists();
        Path pending = this.ours.resolve(EmuleImporter.PENDING_IDENTITY);
        assertThat(pending.resolve("preferences.dat"))
                .hasSameBinaryContentAs(this.emule.config.resolve("preferences.dat"));
        assertThat(pending.resolve("cryptkey.dat")).exists();
        assertThat(pending.resolve("clients.met")).exists();
        assertThat(pending.resolve("preferencesKad.dat")).exists();
        assertThat(PacketReader.of(Files.readAllBytes(pending.resolve("kadudpkey.dat")))
                        .u32())
                .isEqualTo(SyntheticEmuleProfile.KAD_UDP_KEY);
        assertThat(this.emule.fingerprint()).isEqualTo(before);
    }

    @Test
    void categoriesKeepTheirNumbersPatternsAndAColourOfOurs() throws IOException {
        Files.writeString(
                this.emule.config.resolve("Category.ini"),
                String.join(
                        "\r\n",
                        "[General]",
                        "Count=3",
                        "[Cat#0]",
                        "Title=all",
                        "[Cat#1]",
                        "Title=Films",
                        "Incoming=" + this.emule.incoming,
                        "Comment=evening",
                        "Color=255",
                        "AutoCat=*.mkv|*.avi",
                        "[Cat#2]",
                        "Title=",
                        "[Cat#3]",
                        "Title=Linux",
                        "Color=16711680",
                        "AutoCat=ubuntu|debian"));

        Map<String, Outcome> outcomes = outcomes(this.run(new ImportOptions(false, false, false), false));

        assertThat(outcomes).containsEntry("Category.ini", Outcome.IMPORTED);
        assertThat(this.target.categories).extracting(Category::id).containsExactly(1, 3);
        Category films = this.target.categories.getFirst();
        assertThat(films.name()).isEqualTo("Films");
        assertThat(films.incoming()).contains(this.emule.incoming);
        assertThat(films.color()).as("red").isEqualTo(1);
        assertThat(films.matches("Big Buck Bunny.MKV")).isTrue();
        assertThat(this.target.categories.get(1).color()).as("blue").isEqualTo(6);
        assertThat(EmuleImporter.color(OptionalLong.of(0x808080))).as("grey").isZero();
        assertThat(EmuleImporter.color(OptionalLong.of(0x00A5FF))).as("orange").isEqualTo(2);
    }

    @Test
    void ourOwnFilesAreKept() throws IOException {
        Files.writeString(this.ours.resolve("ipfilter.dat"), "");
        Files.writeString(this.ours.resolve("cancelled.met"), "");

        Map<String, Outcome> outcomes = outcomes(this.run(new ImportOptions(false, false, false), false));

        assertThat(outcomes)
                .containsEntry("ipfilter.dat", Outcome.SKIPPED)
                .containsEntry("cancelled.met", Outcome.SKIPPED);
        assertThat(this.ours.resolve("ipfilter.dat")).isEmptyFile();
    }

    @Test
    void identityAndDownloadsNeedAsking() throws IOException {
        ImportReport report = this.run(new ImportOptions(false, false, false), false);

        assertThat(outcomes(report))
                .containsEntry("preferences.dat", Outcome.SKIPPED)
                .containsEntry("cryptkey.dat", Outcome.SKIPPED)
                .containsEntry("Temp", Outcome.SKIPPED)
                .doesNotContainKey("001.part.met");
        assertThat(this.ours.resolve(EmuleImporter.PENDING_IDENTITY)).doesNotExist();
        assertThat(this.target.adopted).isEmpty();
    }

    @Test
    void aRunningEmuleKeepsItsIdentityAndActiveDownloads() throws IOException {
        ImportReport report = this.run(new ImportOptions(false, true, true), true);

        assertThat(outcomes(report))
                .containsEntry("preferences.dat", Outcome.SKIPPED)
                .containsEntry("001.part.met", Outcome.IMPORTED)
                .containsEntry("002.part.met", Outcome.SKIPPED);
        assertThat(detail(report, "002.part.met")).contains("pause it in eMule");
        assertThat(this.ours.resolve(EmuleImporter.PENDING_IDENTITY)).doesNotExist();
        assertThat(this.target.adopted).containsExactly("001.part.met");
    }

    @Test
    void theUserHashComesOnlyWithItsKey() throws IOException {
        Files.writeString(this.emule.config.resolve("cryptkey.dat"), "not base64 at all!");

        ImportReport report = this.run(new ImportOptions(false, true, false), false);

        assertThat(outcomes(report)).containsEntry("cryptkey.dat", Outcome.FAILED);
        assertThat(report.items()).anyMatch(i -> i.detail().contains("the user hash and the key are taken only"));
        assertThat(this.ours.resolve(EmuleImporter.PENDING_IDENTITY)).doesNotExist();
    }

    @Test
    void brokenFilesAreReportedAndTheRestGoesOn() throws IOException {
        Files.write(this.emule.config.resolve("server.met"), new byte[] {(byte) 0xE0, 1});
        Files.delete(this.emule.config.resolve("preferences.ini"));

        ImportReport report = this.run(new ImportOptions(false, false, false), false);

        assertThat(outcomes(report))
                .containsEntry("server.met", Outcome.FAILED)
                .containsEntry("preferences.ini", Outcome.MISSING)
                .containsEntry("nodes.dat", Outcome.IMPORTED);
        assertThat(this.target.servers).isEmpty();
    }

    @Test
    void aPendingIdentityWaitsUntilEmuleIsClosed() throws IOException {
        this.run(new ImportOptions(false, true, false), false);
        Path pending = this.ours.resolve(EmuleImporter.PENDING_IDENTITY);

        assertThat(EmuleImporter.applyPendingIdentity(this.ours, () -> true)).isFalse();
        assertThat(pending).isDirectory();
        assertThat(EmuleImporter.applyPendingIdentity(this.ours, () -> false)).isTrue();

        assertThat(pending).doesNotExist();
        assertThat(this.ours.resolve("preferences.dat"))
                .hasSameBinaryContentAs(this.emule.config.resolve("preferences.dat"));
        assertThat(this.ours.resolve("kadudpkey.dat")).exists();
        assertThat(EmuleImporter.applyPendingIdentity(this.ours, () -> false)).isFalse();
    }

    @Test
    void anIdentityImportedTwiceBeforeARestartIsStillTaken() throws IOException {
        this.run(new ImportOptions(false, true, false), false);
        this.run(new ImportOptions(false, true, false), false);

        assertThat(EmuleImporter.applyPendingIdentity(this.ours, () -> false)).isTrue();

        assertThat(this.ours.resolve(EmuleImporter.PENDING_IDENTITY)).doesNotExist();
        assertThat(this.ours.resolve("preferences.dat"))
                .hasSameBinaryContentAs(this.emule.config.resolve("preferences.dat"));
    }

    @Test
    void leftoversInThePendingIdentityDoNotStopTheStart() throws IOException {
        this.run(new ImportOptions(false, true, false), false);
        Path pending = this.ours.resolve(EmuleImporter.PENDING_IDENTITY);
        Files.writeString(pending.resolve("preferences.dat.bak"), "older");
        Files.writeString(pending.resolve("cryptkey.dat.tmp"), "interrupted");
        Files.writeString(Files.createDirectories(pending.resolve("unexpected")).resolve("x"), "x");

        assertThat(EmuleImporter.applyPendingIdentity(this.ours, () -> false)).isTrue();

        assertThat(pending.resolve("preferences.dat.bak")).doesNotExist();
        assertThat(pending.resolve("cryptkey.dat.tmp")).doesNotExist();
        assertThat(this.ours.resolve("cryptkey.dat")).hasSameBinaryContentAs(this.emule.config.resolve("cryptkey.dat"));
    }

    @Test
    void anotherProfileReplacesTheWholePendingIdentity() throws IOException {
        this.run(new ImportOptions(false, true, false), false);
        SyntheticEmuleProfile other = SyntheticEmuleProfile.create(this.dir.resolve("other"));
        Files.delete(other.config.resolve("clients.met"));
        Files.delete(other.config.resolve("preferencesKad.dat"));
        Path ini = other.config.resolve("preferences.ini");
        Files.writeString(
                ini,
                Files.readString(ini, StandardCharsets.ISO_8859_1).replace("KadUDPKey=", "Unused="),
                StandardCharsets.ISO_8859_1);

        new EmuleImporter(other.config, new ImportOptions(false, true, false), this.target, () -> false).run();

        Path pending = this.ours.resolve(EmuleImporter.PENDING_IDENTITY);
        assertThat(pending.resolve("preferences.dat")).hasSameBinaryContentAs(other.config.resolve("preferences.dat"));
        assertThat(pending.resolve("clients.met")).doesNotExist();
        assertThat(pending.resolve("preferencesKad.dat")).doesNotExist();
        assertThat(pending.resolve("kadudpkey.dat")).doesNotExist();
    }

    @Test
    void aUserHashWithoutItsKeyIsNotApplied() throws IOException {
        this.run(new ImportOptions(false, true, false), false);
        Path pending = this.ours.resolve(EmuleImporter.PENDING_IDENTITY);
        Files.delete(pending.resolve("cryptkey.dat"));

        assertThat(EmuleImporter.applyPendingIdentity(this.ours, () -> false)).isFalse();

        assertThat(this.ours.resolve("preferences.dat")).doesNotExist();
        assertThat(pending).doesNotExist();
    }

    @Test
    void aDryRunKeepsThePendingIdentity() throws IOException {
        this.run(new ImportOptions(false, true, false), false);
        Files.delete(this.emule.config.resolve("cryptkey.dat"));

        this.run(new ImportOptions(true, true, false), false);

        Path pending = this.ours.resolve(EmuleImporter.PENDING_IDENTITY);
        assertThat(pending.resolve("preferences.dat")).exists();
        assertThat(pending.resolve("cryptkey.dat")).exists();
    }

    @Test
    void preferencesTakeOnlyUsableValues() {
        IniFile ini = IniFile.parse(String.join(
                        "\n",
                        "[eMule]",
                        "Nick=" + "x".repeat(Preferences.MAX_NICK_LENGTH + 1),
                        "MaxUpload=-1",
                        "MaxDownload=0",
                        "MaxConnections=999999",
                        "DownloadCapacity=2048",
                        "UploadCapacityNew=-1",
                        "CheckDiskspace=1",
                        "MinFreeDiskSpace=1073741824",
                        "CryptLayerRequired=1")
                .getBytes(StandardCharsets.US_ASCII));

        Preferences p = EmuleImporter.mapPreferences(ini, Preferences.defaults());

        assertThat(p.nick()).isEqualTo(Preferences.defaults().nick());
        assertThat(p.maxUploadKiBps()).isZero();
        assertThat(p.maxDownloadKiBps()).isZero();
        assertThat(p.maxConnections()).isEqualTo(10_000);
        assertThat(p.downloadCapacityKiBps()).isEqualTo(2048);
        assertThat(p.uploadCapacityKiBps())
                .as("an unknown capacity keeps ours")
                .isEqualTo(Preferences.DEFAULT_UPLOAD_CAPACITY);
        assertThat(p.minFreeSpaceMiB()).as("eMule keeps bytes").isEqualTo(1024);
        assertThat(EmuleImporter.mapPreferences(
                                IniFile.parse("[eMule]\nCheckDiskspace=0\nMinFreeDiskSpace=1"
                                        .getBytes(StandardCharsets.US_ASCII)),
                                Preferences.defaults())
                        .minFreeSpaceMiB())
                .as("eMule's minimum is used only while its check is on")
                .isEqualTo(Preferences.DEFAULT_MIN_FREE_SPACE);
        assertThat(p.obfuscation()).isEqualTo(ObfuscationMode.REQUIRED);
        assertThat(EmuleImporter.mapPreferences(
                                IniFile.parse("[eMule]\nCryptLayerSupported=0".getBytes(StandardCharsets.US_ASCII)),
                                Preferences.defaults())
                        .obfuscation())
                .isEqualTo(ObfuscationMode.DISABLED);
        assertThat(EmuleImporter.mapPreferences(IniFile.parse(new byte[0]), Preferences.defaults())
                        .obfuscation())
                .isEqualTo(Preferences.defaults().obfuscation());
    }

    @Test
    void theProxyIsTakenWhenEmuleUsesOne() {
        String socks = """
                [eMule]
                [Proxy]
                ProxyEnableProxy=1
                ProxyType=3
                ProxyName=proxy.lan
                ProxyPort=9050
                ProxyEnablePassword=1
                ProxyUser=me
                ProxyPassword=pw
                """;
        assertThat(proxyOf(socks))
                .isEqualTo(new ProxySettings(ProxySettings.Type.SOCKS, "proxy.lan", 9050, "me", "pw"));
        String http = """
                [eMule]
                [Proxy]
                ProxyEnableProxy=1
                ProxyType=5
                ProxyName=h
                ProxyUser=x
                """;
        assertThat(proxyOf(http)).isEqualTo(new ProxySettings(ProxySettings.Type.HTTP, "h", 1080, "", ""));
        for (String off : List.of(
                "[Proxy]\nProxyEnableProxy=0\nProxyType=3\nProxyName=h\n",
                "[Proxy]\nProxyEnableProxy=1\nProxyType=9\nProxyName=h\n",
                "[Proxy]\nProxyEnableProxy=1\nProxyType=3\nProxyName=\n")) {
            assertThat(proxyOf("[eMule]\n" + off)).isEqualTo(ProxySettings.NONE);
        }
    }

    private static ProxySettings proxyOf(String ini) {
        return EmuleImporter.mapPreferences(
                        IniFile.parse(ini.getBytes(StandardCharsets.US_ASCII)), Preferences.defaults())
                .proxy();
    }

    @Test
    void aProfileWithoutPreferencesSectionFails() throws IOException {
        Files.writeString(this.emule.config.resolve("preferences.ini"), "[Other]\nA=1\n");

        ImportReport report = this.run(new ImportOptions(false, true, true), false);

        assertThat(outcomes(report)).containsEntry("preferences.ini", Outcome.FAILED);
        assertThat(this.target.preferences).isEqualTo(Preferences.defaults());
        // Without preferences.ini the temp directory is looked for next to the config.
        assertThat(this.target.adopted).containsExactlyInAnyOrder("001.part.met", "002.part.met");
    }

    @Test
    void downloadsComeFromEveryTempDirectory() throws IOException {
        Path other = Files.createDirectories(this.dir.resolve("emule/Temp2"));
        for (String name : List.of("002.part.met", "002.part")) {
            Files.move(this.emule.temp.resolve(name), other.resolve(name));
        }
        Path ini = this.emule.config.resolve("preferences.ini");
        Files.writeString(
                ini,
                Files.readString(ini, StandardCharsets.ISO_8859_1)
                        + "TempDirs="
                        + this.dir.resolve("gone")
                        + "|"
                        + other
                        + "\r\n",
                StandardCharsets.ISO_8859_1);

        this.run(new ImportOptions(false, false, true), false);

        assertThat(this.target.adopted).containsExactlyInAnyOrder("001.part.met", "002.part.met");
    }

    @Test
    void anEmptyTempDirectoryIsReported() throws IOException {
        try (var files = Files.list(this.emule.temp)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }

        ImportReport report = this.run(new ImportOptions(false, false, true), false);

        assertThat(outcomes(report)).containsEntry("Temp", Outcome.MISSING);
        assertThat(detail(report, "Temp")).isEqualTo("no downloads in progress");
    }

    @Test
    void aRefusedSharedDirectoryDoesNotStopTheOthers() throws IOException {
        this.target.refused.add(this.emule.incoming);

        ImportReport report = this.run(new ImportOptions(false, false, false), false);

        ImportReport.Item dirs = report.items().stream()
                .filter(i -> i.file().equals("shareddir.dat"))
                .findFirst()
                .orElseThrow();
        assertThat(dirs.outcome()).isEqualTo(Outcome.IMPORTED);
        assertThat(dirs.detail()).contains("1 directories", "1 not shared", "refusing");
        assertThat(this.target.shared).containsExactly(this.emule.shared);
    }

    private ImportReport run(ImportOptions options, boolean emuleRunning) {
        return new EmuleImporter(this.emule.config, options, this.target, () -> emuleRunning).run();
    }

    private static Map<String, Outcome> outcomes(ImportReport report) {
        return report.items().stream()
                .collect(Collectors.toMap(ImportReport.Item::file, ImportReport.Item::outcome, (a, b) -> a));
    }

    private static String detail(ImportReport report, String file) {
        return report.items().stream()
                .filter(i -> i.file().equals(file))
                .findFirst()
                .orElseThrow()
                .detail();
    }
}
