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
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.ImportReport.Outcome;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.persist.format.IniFile;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.io.PacketReader;

class EmuleImporterTest {

    @TempDir Path dir;

    private SyntheticEmuleProfile emule;
    private Path ours;
    private final Recorder target = new Recorder();

    /** Records what the importer asks for. */
    private final class Recorder implements EmuleImporter.Target {
        Preferences preferences = Preferences.defaults();
        final List<Path> servers = new ArrayList<>();
        int contacts;
        int known;
        int trees;
        final List<Path> shared = new ArrayList<>();
        final List<String> adopted = new ArrayList<>();

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
            this.trees += trees.size();
            return files.size();
        }

        @Override
        public void shareDirectory(Path directory) {
            this.shared.add(directory);
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
        TreeMap<String, String> before = this.emule.fingerprint();

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
        assertThat(Files.list(this.ours).count()).isZero();
        assertThat(this.emule.fingerprint()).isEqualTo(before);
    }

    @Test
    void anImportTakesEverythingAndLeavesEmuleAlone() throws IOException {
        TreeMap<String, String> before = this.emule.fingerprint();

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
        assertThat(PacketReader.of(Files.readAllBytes(pending.resolve("kadudpkey.dat"))).u32())
                .isEqualTo(SyntheticEmuleProfile.KAD_UDP_KEY);
        assertThat(this.emule.fingerprint()).isEqualTo(before);
    }

    @Test
    void ourOwnFilesAreKept() throws IOException {
        Files.writeString(this.ours.resolve("ipfilter.dat"), "");
        Files.writeString(this.ours.resolve("cancelled.met"), "");

        Map<String, Outcome> outcomes =
                outcomes(this.run(new ImportOptions(false, false, false), false));

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
        assertThat(report.items())
                .anyMatch(i -> i.detail().contains("the user hash and the key are taken only"));
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
    void preferencesTakeOnlyUsableValues() {
        IniFile ini =
                IniFile.parse(
                        String.join(
                                        "\n",
                                        "[eMule]",
                                        "Nick=" + "x".repeat(Preferences.MAX_NICK_LENGTH + 1),
                                        "MaxUpload=-1",
                                        "MaxDownload=0",
                                        "MaxConnections=999999",
                                        "CryptLayerRequired=1")
                                .getBytes(StandardCharsets.US_ASCII));

        Preferences p = EmuleImporter.mapPreferences(ini, Preferences.defaults());

        assertThat(p.nick()).isEqualTo(Preferences.defaults().nick());
        assertThat(p.maxUploadKiBps()).isZero();
        assertThat(p.maxDownloadKiBps()).isZero();
        assertThat(p.maxConnections()).isEqualTo(10_000);
        assertThat(p.obfuscation()).isEqualTo(ObfuscationMode.REQUIRED);
        assertThat(
                        EmuleImporter.mapPreferences(
                                        IniFile.parse(
                                                "[eMule]\nCryptLayerSupported=0"
                                                        .getBytes(StandardCharsets.US_ASCII)),
                                        Preferences.defaults())
                                .obfuscation())
                .isEqualTo(ObfuscationMode.DISABLED);
        assertThat(
                        EmuleImporter.mapPreferences(
                                        IniFile.parse(new byte[0]), Preferences.defaults())
                                .obfuscation())
                .isEqualTo(Preferences.defaults().obfuscation());
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

    private ImportReport run(ImportOptions options, boolean emuleRunning) {
        return new EmuleImporter(this.emule.config, options, this.target, () -> emuleRunning).run();
    }

    private static Map<String, Outcome> outcomes(ImportReport report) {
        return report.items().stream()
                .collect(
                        Collectors.toMap(
                                ImportReport.Item::file, ImportReport.Item::outcome, (a, b) -> a));
    }

    private static String detail(ImportReport report, String file) {
        return report.items().stream()
                .filter(i -> i.file().equals(file))
                .findFirst()
                .orElseThrow()
                .detail();
    }
}
