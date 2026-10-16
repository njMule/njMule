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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
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

    private SyntheticEmuleProfile emule;
    private SortedMap<String, String> before;
    private int port;
    private AppDirectories dirs;
    private NjMuleEngine engine;
    private final List<EngineEvent> events = new CopyOnWriteArrayList<>();
    private ImportReport report;

    @AfterEach
    void close() {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldGoOnWithTheImportedDownloadsAndTakeTheIdentityAtTheNextStart() throws Exception {
        givenAnEmuleProfile();
        givenAProfileOfOurs();
        givenARunningEngine();
        givenTheEmuleProfileWasImported(new ImportOptions(false, true, true));
        givenNothingFailedInTheImport();
        givenTheNickIsTakenButNotThePort();
        givenTheKadContactsSaved(3);
        givenTheCompleteDownloadIsFinished();
        givenTheHalfDownloadGoesOn();
        givenTheFileIsShared("shared.bin");
        givenTheIdentityWaitsForTheNextStart();

        whenTheEngineStartsAgain();

        thenTheEngineRuns();
        thenNoIdentityWaitsAnyMore();
        thenTheUserHashIsTheOneOfTheEmuleProfile();
        thenTheEmuleProfileIsUntouched();
    }

    @Test
    void shouldStartAfterTwoIdentityImports() throws Exception {
        givenAnEmuleProfile();
        givenAProfileOfOurs();
        givenARunningEngine();
        givenTheEmuleProfileWasImported(new ImportOptions(false, true, false));
        givenTheEmuleProfileWasImported(new ImportOptions(false, true, false));

        whenTheEngineStartsAgain();

        thenTheEngineRuns();
        thenNoIdentityWaitsAnyMore();
        thenOurFileIsTheOneOfTheEmuleProfile("cryptkey.dat");
    }

    @Test
    void shouldKeepTheSearchesOfEmuleBeyondTheSessionOfTheImport() throws Exception {
        givenAnEmuleProfile();
        givenTheEmuleProfileHasTheSearch("linux");
        givenAProfileOfOurs();
        givenARunningEngine();
        givenTheEmuleProfileWasImported(new ImportOptions(false, false, false));
        givenTheReportSaysTheSearchesComeFromTheNextStart();
        givenTheInterfaceSavesItsSearches();

        whenTheEngineStartsAgain();

        thenTheSavedSearchesAre("linux");
    }

    @Test
    void shouldNotPutADownloadInAnUnrelatedCategoryOfOurs() throws Exception {
        givenAnEmuleProfile();
        givenTheEmuleProfileHasTheCategory("Films");
        givenTheHalfDownloadOfEmuleIsInCategory(1);
        givenAProfileOfOurs();
        givenARunningEngine();
        givenOurCategory(1, "Mine");

        whenTheEmuleProfileIsImported(new ImportOptions(false, false, true));

        thenTheHalfDownloadIsInCategory(Category.NONE);
    }

    @Test
    void shouldAnnounceAnImportedDownloadInTheCategoryItEndsIn() throws Exception {
        givenAnEmuleProfile();
        givenTheEmuleProfileHasTheCategory("Films");
        givenTheHalfDownloadOfEmuleIsInCategory(1);
        givenAProfileOfOurs();
        givenARunningEngine();
        givenOurCategory(1, "Mine");
        givenTheEventsAreWatched();

        whenTheEmuleProfileIsImported(new ImportOptions(false, false, true));

        thenTheHalfDownloadWasAnnouncedInCategory(Category.NONE);
    }

    @Test
    void shouldLeaveTheEngineAsItWasAfterADryRun() throws Exception {
        givenAnEmuleProfile();
        givenAProfileOfOurs();
        givenARunningEngine();

        whenTheEmuleProfileIsImported(new ImportOptions(true, true, true));

        thenTheReportIsADryRun();
        thenTheNickIsNotTaken();
        thenThereIsNoDownload();
        thenNoKadContactIsSaved();
    }

    private void givenAnEmuleProfile() throws IOException {
        this.emule = SyntheticEmuleProfile.create(this.dir.resolve("emule"));
        this.before = this.emule.fingerprint();
    }

    private void givenTheEmuleProfileHasTheSearch(String title) throws IOException {
        Files.write(
                this.emule.config.resolve(StoredSearches.FILE_NAME),
                SearchFiles.writeStored(
                        List.of(new SearchFiles.StoredSearch(1, 1, false, title, title, "", List.of()))));
    }

    private void givenTheEmuleProfileHasTheCategory(String title) throws IOException {
        Files.writeString(this.emule.config.resolve("Category.ini"), "[Cat#1]\r\nTitle=" + title + "\r\n");
    }

    private void givenTheHalfDownloadOfEmuleIsInCategory(int category) throws IOException {
        this.emule.putInCategory(2, category);
    }

    private void givenAProfileOfOurs() throws IOException {
        this.port = FreePorts.tcp();
        this.dirs = directories(this.port);
    }

    private void givenARunningEngine() throws IOException {
        this.engine = Engine.start(new EngineConfig(this.dirs), () -> false);
    }

    private void givenOurCategory(int id, String name) throws Exception {
        this.engine
                .setCategories(
                        List.of(new Category(id, name, Optional.empty(), "", 0, DownloadPriority.AUTO, "", false)))
                .get(5, TimeUnit.SECONDS);
    }

    private void givenTheEmuleProfileWasImported(ImportOptions options) throws Exception {
        whenTheEmuleProfileIsImported(options);
    }

    private void givenNothingFailedInTheImport() {
        assertThat(this.report.items())
                .filteredOn(i -> i.outcome() == ImportReport.Outcome.FAILED)
                .isEmpty();
    }

    private void givenTheNickIsTakenButNotThePort() {
        assertThat(this.engine.preferences().nick()).isEqualTo(SyntheticEmuleProfile.NICK);
        assertThat(this.engine.preferences().tcpPort()).isEqualTo(this.port);
    }

    private void givenTheKadContactsSaved(int count) throws IOException {
        assertThat(NodesDat.read(Files.readAllBytes(this.dirs.config().resolve(KadNode.NODES_FILE)))
                        .contacts())
                .hasSize(count);
    }

    // Both copies are one file: the complete one finishes, the other is then known. The name is reserved by an empty
    // file before the data replaces it.
    private void givenTheCompleteDownloadIsFinished() throws Exception {
        Path finished =
                this.engine.preferences().effectiveIncomingDir(this.dirs).resolve("complete.bin");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(finished) || Files.size(finished) != this.emule.downloadData.length) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(Files.readAllBytes(finished)).isEqualTo(this.emule.downloadData);
    }

    private void givenTheHalfDownloadGoesOn() throws Exception {
        List<DownloadInfo> downloads = this.engine.downloads().get(5, TimeUnit.SECONDS);
        assertThat(downloads).hasSize(1);
        assertThat(downloads.getFirst().hash()).isEqualTo(this.emule.halfHash);
        assertThat(downloads.getFirst().completed()).isEqualTo(SyntheticEmuleProfile.DOWNLOAD_SIZE / 2);
    }

    private void givenTheFileIsShared(String name) throws Exception {
        assertThat(this.engine.sharedFiles().get(5, TimeUnit.SECONDS))
                .anyMatch(f -> f.name().equals(name));
    }

    private void givenTheIdentityWaitsForTheNextStart() {
        assertThat(this.dirs.config().resolve(EmuleImporter.PENDING_IDENTITY)).isDirectory();
    }

    private void givenTheReportSaysTheSearchesComeFromTheNextStart() {
        assertThat(this.report.items())
                .filteredOn(i -> i.file().equals(StoredSearches.FILE_NAME))
                .singleElement()
                .satisfies(i -> assertThat(i.detail()).endsWith("from the next start"));
    }

    // What closing the interface after a look at the searches does.
    private void givenTheInterfaceSavesItsSearches() throws Exception {
        this.engine.saveSearches(List.of()).get(5, TimeUnit.SECONDS);
    }

    private void whenTheEmuleProfileIsImported(ImportOptions options) throws Exception {
        this.report = this.engine.importFromEmule(this.emule.config, options).get(30, TimeUnit.SECONDS);
    }

    private void whenTheEngineStartsAgain() throws IOException {
        this.engine.close();
        this.engine = Engine.start(new EngineConfig(this.dirs), () -> false);
    }

    private void thenTheEngineRuns() {
        assertThat(this.engine.isRunning()).isTrue();
    }

    private void thenNoIdentityWaitsAnyMore() {
        assertThat(this.dirs.config().resolve(EmuleImporter.PENDING_IDENTITY)).doesNotExist();
    }

    private void thenTheUserHashIsTheOneOfTheEmuleProfile() throws IOException {
        assertThat(PreferencesDat.read(Files.readAllBytes(this.dirs.config().resolve(UserHashStore.FILE_NAME)))
                        .userHash())
                .isEqualTo(PreferencesDat.read(Files.readAllBytes(this.emule.config.resolve(UserHashStore.FILE_NAME)))
                        .userHash());
    }

    private void thenOurFileIsTheOneOfTheEmuleProfile(String name) {
        assertThat(this.dirs.config().resolve(name)).hasSameBinaryContentAs(this.emule.config.resolve(name));
    }

    private void thenTheEmuleProfileIsUntouched() throws IOException {
        assertThat(this.emule.fingerprint()).isEqualTo(this.before);
    }

    private void thenTheSavedSearchesAre(String... titles) throws Exception {
        assertThat(this.engine.savedSearches().get(5, TimeUnit.SECONDS))
                .extracting(SavedSearch::title)
                .containsExactly(titles);
    }

    private void givenTheEventsAreWatched() {
        this.engine.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EngineEvent event) {
                EngineImportTest.this.events.add(event);
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
    }

    // Events reach their watchers on a thread of their own: the announcement may come just after the import.
    private void thenTheHalfDownloadWasAnnouncedInCategory(int category) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (announcedHalfDownloads().isEmpty()) {
            assertThat(System.nanoTime()).as("the half download announced").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(announcedHalfDownloads())
                .singleElement()
                .extracting(DownloadInfo::category)
                .isEqualTo(category);
    }

    private List<DownloadInfo> announcedHalfDownloads() {
        return this.events.stream()
                .filter(EngineEvent.DownloadAdded.class::isInstance)
                .map(e -> ((EngineEvent.DownloadAdded) e).download())
                .filter(d -> d.hash().equals(this.emule.halfHash))
                .toList();
    }

    private void thenTheHalfDownloadIsInCategory(int category) throws Exception {
        assertThat(this.engine.downloads().get(5, TimeUnit.SECONDS))
                .filteredOn(d -> d.hash().equals(this.emule.halfHash))
                .singleElement()
                .extracting(DownloadInfo::category)
                .isEqualTo(category);
    }

    private void thenTheReportIsADryRun() {
        assertThat(this.report.dryRun()).isTrue();
    }

    private void thenTheNickIsNotTaken() {
        assertThat(this.engine.preferences().nick()).isNotEqualTo(SyntheticEmuleProfile.NICK);
    }

    private void thenThereIsNoDownload() throws Exception {
        assertThat(this.engine.downloads().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private void thenNoKadContactIsSaved() {
        assertThat(this.dirs.config().resolve(KadNode.NODES_FILE)).doesNotExist();
    }

    // No Kad: the imported contacts must not be greeted from a test.
    private AppDirectories directories(int port) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\nnet.kad.enabled=false\n");
        return dirs;
    }
}
