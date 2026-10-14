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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.kad.KadNode;
import org.njmule.core.persist.EmuleImporter;
import org.njmule.core.persist.SyntheticEmuleProfile;
import org.njmule.core.persist.UserHashStore;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.persist.format.PreferencesDat;
import org.njmule.core.persist.format.SearchFiles;
import org.njmule.core.search.StoredSearches;

@Timeout(60)
class EngineImportTest {

    @TempDir
    Path dir;

    @Test
    void importedDownloadsGoOnAndTheIdentityFollowsAtTheNextStart() throws Exception {
        SyntheticEmuleProfile emule = SyntheticEmuleProfile.create(this.dir.resolve("emule"));
        SortedMap<String, String> before = emule.fingerprint();
        int port = freePort();
        AppDirectories dirs = this.directories(port);

        try (NjMuleEngine engine = Engine.start(new EngineConfig(dirs), () -> false)) {
            ImportReport report = engine.importFromEmule(emule.config, new ImportOptions(false, true, true))
                    .get(30, TimeUnit.SECONDS);

            assertThat(report.items())
                    .filteredOn(i -> i.outcome() == ImportReport.Outcome.FAILED)
                    .isEmpty();
            assertThat(engine.preferences().nick()).isEqualTo(SyntheticEmuleProfile.NICK);
            assertThat(engine.preferences().tcpPort()).isEqualTo(port);
            assertThat(NodesDat.read(Files.readAllBytes(dirs.config().resolve(KadNode.NODES_FILE)))
                            .contacts())
                    .hasSize(3);
            // Both copies are one file: the complete one finishes, the other is then known.
            Path finished = engine.preferences().effectiveIncomingDir(dirs).resolve("complete.bin");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(finished)) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(50);
            }
            assertThat(Files.readAllBytes(finished)).isEqualTo(emule.downloadData);
            List<DownloadInfo> downloads = engine.downloads().get(5, TimeUnit.SECONDS);
            assertThat(downloads).hasSize(1);
            assertThat(downloads.getFirst().hash()).isEqualTo(emule.halfHash);
            assertThat(downloads.getFirst().completed()).isEqualTo(SyntheticEmuleProfile.DOWNLOAD_SIZE / 2);
            assertThat(engine.sharedFiles().get(5, TimeUnit.SECONDS))
                    .anyMatch(f -> f.name().equals("shared.bin"));
            assertThat(dirs.config().resolve(EmuleImporter.PENDING_IDENTITY)).isDirectory();
        }

        try (NjMuleEngine engine = Engine.start(new EngineConfig(dirs), () -> false)) {
            assertThat(engine.isRunning()).isTrue();
            assertThat(dirs.config().resolve(EmuleImporter.PENDING_IDENTITY)).doesNotExist();
            assertThat(PreferencesDat.read(Files.readAllBytes(dirs.config().resolve(UserHashStore.FILE_NAME)))
                            .userHash())
                    .isEqualTo(PreferencesDat.read(Files.readAllBytes(emule.config.resolve(UserHashStore.FILE_NAME)))
                            .userHash());
        }
        assertThat(emule.fingerprint()).isEqualTo(before);
    }

    @Test
    void theEngineStartsAfterTwoIdentityImports() throws Exception {
        SyntheticEmuleProfile emule = SyntheticEmuleProfile.create(this.dir.resolve("emule"));
        AppDirectories dirs = this.directories(freePort());
        ImportOptions identity = new ImportOptions(false, true, false);

        try (NjMuleEngine engine = Engine.start(new EngineConfig(dirs), () -> false)) {
            engine.importFromEmule(emule.config, identity).get(30, TimeUnit.SECONDS);
            engine.importFromEmule(emule.config, identity).get(30, TimeUnit.SECONDS);
        }

        try (NjMuleEngine engine = Engine.start(new EngineConfig(dirs), () -> false)) {
            assertThat(engine.isRunning()).isTrue();
            assertThat(dirs.config().resolve(EmuleImporter.PENDING_IDENTITY)).doesNotExist();
            assertThat(dirs.config().resolve("cryptkey.dat"))
                    .hasSameBinaryContentAs(emule.config.resolve("cryptkey.dat"));
        }
    }

    @Test
    void eMulesSearchesOutliveTheSessionOfTheImport() throws Exception {
        SyntheticEmuleProfile emule = SyntheticEmuleProfile.create(this.dir.resolve("emule"));
        Files.write(
                emule.config.resolve(StoredSearches.FILE_NAME),
                SearchFiles.writeStored(
                        List.of(new SearchFiles.StoredSearch(1, 1, false, "linux", "linux", "", List.of()))));
        AppDirectories dirs = this.directories(freePort());

        try (NjMuleEngine engine = Engine.start(new EngineConfig(dirs), () -> false)) {
            ImportReport report = engine.importFromEmule(emule.config, new ImportOptions(false, false, false))
                    .get(30, TimeUnit.SECONDS);
            assertThat(report.items())
                    .filteredOn(i -> i.file().equals(StoredSearches.FILE_NAME))
                    .singleElement()
                    .satisfies(i -> assertThat(i.detail()).endsWith("from the next start"));
            // What closing the interface after a look at the searches does.
            engine.saveSearches(List.of()).get(5, TimeUnit.SECONDS);
        }

        try (NjMuleEngine engine = Engine.start(new EngineConfig(dirs), () -> false)) {
            assertThat(engine.savedSearches().get(5, TimeUnit.SECONDS))
                    .extracting(SavedSearch::title)
                    .containsExactly("linux");
        }
    }

    @Test
    void aDownloadDoesNotLandInAnUnrelatedCategoryOfOurs() throws Exception {
        SyntheticEmuleProfile emule = SyntheticEmuleProfile.create(this.dir.resolve("emule"));
        Files.writeString(emule.config.resolve("Category.ini"), "[Cat#1]\r\nTitle=Films\r\n");
        emule.putInCategory(2, 1);
        AppDirectories dirs = this.directories(freePort());

        try (NjMuleEngine engine = Engine.start(new EngineConfig(dirs), () -> false)) {
            engine.setCategories(
                            List.of(new Category(1, "Mine", Optional.empty(), "", 0, DownloadPriority.AUTO, "", false)))
                    .get(5, TimeUnit.SECONDS);
            engine.importFromEmule(emule.config, new ImportOptions(false, false, true))
                    .get(30, TimeUnit.SECONDS);

            assertThat(engine.downloads().get(5, TimeUnit.SECONDS))
                    .filteredOn(d -> d.hash().equals(emule.halfHash))
                    .singleElement()
                    .extracting(DownloadInfo::category)
                    .isEqualTo(Category.NONE);
        }
    }

    @Test
    void aDryRunLeavesTheEngineAsItWas() throws Exception {
        SyntheticEmuleProfile emule = SyntheticEmuleProfile.create(this.dir.resolve("emule"));
        AppDirectories dirs = this.directories(freePort());

        try (NjMuleEngine engine = Engine.start(new EngineConfig(dirs), () -> false)) {
            ImportReport report = engine.importFromEmule(emule.config, new ImportOptions(true, true, true))
                    .get(30, TimeUnit.SECONDS);

            assertThat(report.dryRun()).isTrue();
            assertThat(engine.preferences().nick()).isNotEqualTo(SyntheticEmuleProfile.NICK);
            assertThat(engine.downloads().get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(dirs.config().resolve(KadNode.NODES_FILE)).doesNotExist();
        }
    }

    // No Kad: the imported contacts must not be greeted from a test.
    private AppDirectories directories(int port) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\nnet.kad.enabled=false\n");
        return dirs;
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
