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
package org.njmule.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

@Timeout(60)
class SharedFilesTest {

    private static final Hash20 TREE_A = Hash20.parseBase32("A".repeat(32));
    private static final Hash20 TREE_B = Hash20.parseBase32("B".repeat(32));

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final Map<String, byte[]> contents = new HashMap<>();
    private final Map<String, Throwable> refusals = new LinkedHashMap<>();
    private Path incoming;
    private Path config;
    private SharedFiles shares;
    private Path directory;
    private boolean privateDirectory;
    private String newName;
    private Optional<SharedFiles.SharedFile> renamed;
    private UploadPriority priority;
    private AichTree firstAichTree;
    private Optional<AichTree> aichTree;
    private List<String> order;
    private Path tracked;
    private Hash16 oldHash;
    private PartFile download;
    private Hash20 linkAichHash;
    private FileTime modified;
    private List<KnownMet.KnownFile> records;
    private List<Known2Met.Entry> trees;
    private int added;
    private Throwable exception;

    @AfterEach
    void stop() throws Exception {
        try {
            if (this.shares != null) {
                closeTheShares();
            }
        } finally {
            this.loop.close();
        }
    }

    @Test
    void shouldShareTheIncomingAndSharedDirectoriesAndRememberTheHashes() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 100_000, 1);
        givenAFile("extra/b.bin", 50_000, 2);
        givenAOneByteFile("incoming/desktop.ini");
        givenAOneByteFile("incoming/001.part");
        givenAOneByteFile("incoming/sub/c.bin");
        givenTheSharesAreOpen();
        givenTheSharesReport("shared a.bin");
        givenTheDirectoryIsShared("extra");
        givenTheSharesReport("shared b.bin");
        givenTheSharedFileHasTheSizeOfItsContent("a.bin");
        givenTheNumberOfSharedFilesIs(2);
        givenTheSharesAreClosed();
        givenTheConfigDirectoryHolds(SharedFiles.KNOWN_MET);
        givenTheSharesAreOpen();
        givenAScanSharesTheKnownFilesAtOnce(2);
        givenTheFileIsDeleted("extra/b.bin");
        givenTheEventsSoFarAreIgnored();

        whenTheSharesAreScanned();

        thenTheSharesReportWithin(10, "unshared b.bin");
        thenTheNumberOfSharedFilesIs(1);
    }

    @ParameterizedTest
    @MethodSource("privateDirectories")
    void shouldTreatPrivateDirectoriesAsPrivate(String directory) throws Exception {
        givenPrivateDirectoriesAndABackupBesideAFileToShare();
        givenTheSharesAreOpen();
        givenTheSharesReport("shared real.bin");

        whenItIsAskedWhetherTheDirectoryIsPrivate(directory);

        thenTheDirectoryIsPrivate();
    }

    @Test
    void shouldNotTreatTheIncomingDirectoryAsPrivate() throws Exception {
        givenPrivateDirectoriesAndABackupBesideAFileToShare();
        givenTheSharesAreOpen();
        givenTheSharesReport("shared real.bin");

        whenItIsAskedWhetherTheDirectoryIsPrivate("incoming");

        thenTheDirectoryIsNotPrivate();
    }

    @Test
    void shouldNeverSharePrivateDirectoriesOrBackups() throws Exception {
        givenPrivateDirectoriesAndABackupBesideAFileToShare();
        givenTheSharesAreOpen();
        givenTheSharesReport("shared real.bin");

        whenThePrivateDirectoriesAreShared();

        thenEveryPrivateDirectoryIsRefused();
        thenTheSharesReportNothingWithin(2, TimeUnit.SECONDS);
        thenTheNumberOfSharedFilesIs(1);
        thenNoDirectoryIsShared();
    }

    @Test
    void shouldKeepAPrivateDirectoryListedByAnOlderVersionUnshared() throws Exception {
        givenPrivateDirectoriesAndABackupBesideAFileToShare();
        givenTheSharesAreOpen();
        givenTheSharesReport("shared real.bin");
        givenTheSharesAreClosed();
        givenTheSharedDirectoriesListHolds("emule-config");

        whenTheSharesAreOpened();

        thenTheSharesReport("shared real.bin");
        thenTheSharesReportNothingWithin(2, TimeUnit.SECONDS);
    }

    @Test
    void shouldShareATreeWithItsSubdirectoriesAlsoLaterOnesUntilItIsUnshared() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("tree/sub/c.bin", 3000, 4);
        givenAFile("tree/sub/deeper/d.bin", 4000, 5);
        givenTheSharesAreOpen();
        givenTheTreeIsShared("tree");
        givenTheSharesReportInAnyOrder("shared c.bin", "shared d.bin");
        givenTheSharedTreesAre("tree");
        givenNoDirectoryIsShared();
        givenAFileInADirectoryMadeLater("tree/later/e.bin", 5000, 6);
        givenTheSharesReport("shared e.bin");

        whenTheDirectoryIsUnshared("tree");

        thenTheSharesReportInAnyOrder("unshared c.bin", "unshared d.bin", "unshared e.bin");
        thenNoTreeIsShared();
    }

    @Test
    void shouldKeepTheUploadPriorityOfEachFileInKnownMet() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 20_000, 3);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared a.bin");
        givenTheUploadPriorityIs("a.bin", UploadPriority.AUTO);
        givenTheUploadPriorityIsSet("a.bin", UploadPriority.RELEASE);
        givenTheUploadPriorityOfAnUnknownFileCannotBeSet("00".repeat(16), UploadPriority.LOW);
        givenTheSharesAreClosed();
        givenKnownMetHoldsOneRecordWithTheUploadPriority(3);
        givenTheSharesAreOpen();

        whenTheSharesAreScanned();

        thenTheUploadPriorityIs("a.bin", UploadPriority.RELEASE);
    }

    @Test
    void shouldRenameASharedFileAndKeepWhatIsKnownOfIt() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 20_000, 6);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared a.bin");
        givenTheUploadPriorityIsSet("a.bin", UploadPriority.HIGH);

        whenTheFileIsRenamed("a.bin", "b?.bin");

        thenTheRenamedFileIsNamed("b_.bin", "a name the file system takes");
        thenTheFileHasTheContentOf("incoming/b_.bin", "a.bin");
        thenTheFileDoesNotExist("incoming/a.bin");
        thenTheSharesReportWithin(10, "unshared a.bin", "shared b_.bin");
        thenTheUploadPriorityIs("a.bin", UploadPriority.HIGH);
    }

    @Test
    void shouldRefuseToRenameASharedFileOverAnotherFile() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 20_000, 6);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared a.bin");
        givenAOneByteFile("incoming/c.bin");

        whenTheFileIsRenamed("a.bin", "c.bin");

        thenTheRenameIsRefusedWithTheRootCause(FileAlreadyExistsException.class);
    }

    @Test
    void shouldRenameNothingForAnUnknownHash() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 20_000, 6);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared a.bin");

        whenAnUnknownFileIsRenamed("00".repeat(16), "d.bin");

        thenNothingIsRenamed();
    }

    @Test
    void shouldBuildTheAichTreeOfAFileOnceForAllItsRequests() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 500_000, 7);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared a.bin");
        givenTheAichTreeIsRequested("a.bin");

        whenTheAichTreeIsRequested("a.bin");

        thenTheSameAichTreeIsGiven();
        thenTheAichTreeCoversTheFile("a.bin");
    }

    @Test
    void shouldGoOnScanningADirectoryPastAFileThatCannotBeRead() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenNumberedFilesInTheIncomingDirectory(6);
        givenTheSharesAreOpen();
        givenTheOrderInWhichTheFilesAreShared(6);
        givenTheSharesAreClosed();

        whenTheSharesAreOpenedAndTheSecondFileGoesWhileTheDirectoryIsRead();

        thenAllTheFilesButTheSecondAreShared();
    }

    @Test
    void shouldKeepTheContentSharedThroughAnotherCopyWhenOneGoes() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/one.bin", 4000, 30);
        givenACopyOf("one.bin", "incoming/two.bin");
        givenTheSharesAreOpen();
        givenTheSecondCopyIsTracked("one.bin");
        givenTheTrackedCopyIsDeleted();
        givenTheEventsSoFarAreIgnored();

        whenTheSharesAreScanned();

        thenTheOtherCopyIsShared("one.bin");
        thenTheSharesReportNothingWithin(1, TimeUnit.SECONDS);
    }

    @Test
    void shouldUnshareAFileWhoseContentChangesUnderItsOldHash() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/changing.bin", 4000, 31);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared changing.bin");
        givenTheHashOfTheSharedFileIsNoted();
        givenTheFileChanges("incoming/changing.bin", 5000, 32);

        whenTheSharesAreScanned();

        thenTheSharesReport("unshared changing.bin", "shared changing.bin");
        thenNoFileIsSharedUnderTheOldHash();
    }

    @Test
    void shouldKeepAFileRenamedWhileHashedAgainSharedUnderItsNewName() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenTheSharesAreOpen();
        givenAFile("incoming/done.bin", 600_000, 33);
        givenTheDownload(1, "done.bin");
        givenTheDownloadFinishesAndIsRenamed("done.bin", "renamed.bin");
        givenTheFileGetsAnAichTree("done.bin", "hashed");

        whenTheSharesAreScanned();

        thenTheFileIsSharedAs("done.bin", "renamed.bin");
        thenTheFileHasAnAichTree("done.bin");
    }

    @Test
    void shouldGiveAFileRenamedBeforeItIsHashedAgainItsAichTreeUnderItsNewName() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenTheSharesAreOpen();
        givenAFile("incoming/done.bin", 600_000, 37);
        givenTheDownload(1, "done.bin");
        givenTheHashingThreadBusyWithALargeFile(2, 36, "77".repeat(16));

        whenTheDownloadFinishesAndIsRenamed("done.bin", "renamed.bin");

        thenTheFileGetsAnAichTree("done.bin", "hashed under its new name");
        thenTheFileIsSharedAs("done.bin", "renamed.bin");
        thenTheFileWithTheHashKeepsNoAichTree("77".repeat(16));
    }

    @Test
    void shouldGiveADownloadFinishedWhileAScanWaitsToReadItsFileItsAichTreeOnceRenamed() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenTheSharesAreOpen();
        givenTheHashingThreadBusyWithALargeFile(2, 40, "66".repeat(16));

        whenADownloadFinishesAfterAScanQueuedItsFileAndIsRenamed("renamed.bin");

        thenTheFileGetsAnAichTree("done.bin", "hashed");
        thenTheFileIsSharedAs("done.bin", "renamed.bin");
    }

    @Test
    void shouldLeaveOutUnreadableListsAndRecordsAndHashTheFilesAgain() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenUnreadableListsAndRecordsInTheConfigDirectory();
        givenAFile("incoming/a.bin", 3000, 38);
        givenAnEmptyFile("incoming/empty.bin");
        givenAHiddenFile("incoming/.hidden.bin", 3000, 39);

        whenTheSharesAreOpened();

        thenTheSharesReport("shared a.bin");
        thenNoDirectoryIsShared();
        thenTheOnlySharedTreeIs("missing");
    }

    @Test
    void shouldScanNothingOnceClosedAndLeaveAnUnwritableKnownMetAsItIs() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenUnreadableListsAndRecordsInTheConfigDirectory();
        givenAFile("incoming/a.bin", 3000, 38);
        givenAnEmptyFile("incoming/empty.bin");
        givenAHiddenFile("incoming/.hidden.bin", 3000, 39);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared a.bin");

        whenTheSharesAreClosedAndScanned();

        thenTheSharesReportNothingWithin(200, TimeUnit.MILLISECONDS);
        thenTheConfigFileIsADirectory(SharedFiles.KNOWN_MET);
    }

    @Test
    void shouldTakeTheUsableRecordsOfAnotherClient() throws Exception {
        givenTheFilesAndTheRecordsOfAnotherClient();

        whenTheRecordsOfAnotherClientAreAdded();

        thenTheNumberOfRecordsTakenIs(2);
    }

    @Test
    void shouldShareTheFilesOfAnotherClientWithoutHashingAndOnlyWithTheirOwnTrees() throws Exception {
        givenTheFilesAndTheRecordsOfAnotherClient();
        givenTheRecordsOfAnotherClientAreAdded();

        whenTheDirectoryIsShared("incoming");

        thenTheFileIsSharedWithoutHashingWithTheAichHash("a.bin", TREE_A);
        thenTheFileHasNoAichTree("a.bin", "one block hash for three blocks");
        thenTheFileHasNoAichTree("b.bin", "block hashes of another tree");
    }

    @Test
    void shouldRenameAFileOfAnotherClientToTheNameItHas() throws Exception {
        givenTheFilesAndTheRecordsOfAnotherClient();
        givenTheRecordsOfAnotherClientAreAdded();
        givenTheDirectoryIsShared("incoming");

        whenTheFileIsRenamed("a.bin", "a.bin");

        thenAFileIsRenamed("the same name");
    }

    @Test
    void shouldRenameNothingForAHashUnknownToTheRecordsOfAnotherClient() throws Exception {
        givenTheFilesAndTheRecordsOfAnotherClient();
        givenTheRecordsOfAnotherClientAreAdded();
        givenTheDirectoryIsShared("incoming");

        whenAnUnknownFileIsRenamed("24".repeat(16), "x.bin");

        thenNothingIsRenamed();
    }

    @Test
    void shouldKeepTheOtherTagsOfARenamedRecordOfAnotherClient() throws Exception {
        givenTheFilesAndTheRecordsOfAnotherClient();
        givenTheRecordsOfAnotherClientAreAdded();
        givenTheDirectoryIsShared("incoming");
        givenTheFileIsRenamed("a.bin", "kept.bin");

        whenTheSharesAreClosed();

        thenKnownMetNamesTheRecordWithItsOtherTags("a.bin", "kept.bin");
    }

    @ParameterizedTest
    @EnumSource(UploadPriority.class)
    void shouldKeepEveryUploadPriorityUnderTheCodeOtherClientsUse(UploadPriority priority) throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 2000, 42);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared a.bin");

        whenTheUploadPriorityIsSet("a.bin", priority);

        thenTheUploadPriorityIs("a.bin", priority);
    }

    @Test
    void shouldGiveAnUnknownFileTheAutomaticUploadPriority() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 2000, 42);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared a.bin");

        whenTheUploadPriorityIsAskedFor("25".repeat(16));

        thenTheUploadPriorityAnsweredIs(UploadPriority.AUTO);
    }

    @Test
    void shouldHashAFinishedFileForItsOwnTreeAndLeaveOutOneThatCannotBeHashed() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenTheSharesAreOpen();
        givenAFile("other/done.bin", 400_000, 44);
        givenTheDownloadWithTheAichHash(4, "done.bin", "D".repeat(32));
        givenAFile("extra/gone.bin", 3000, 45);
        givenTheHashingThreadBusyWithALargeFile(3, 43, "26".repeat(16));
        givenTheDownloadFinishedAt("never.bin");
        givenTheDownloadFinishedAt("other/done.bin");

        whenADirectoryIsSharedAndItsFileGoesBeforeItIsHashed("extra", "extra/gone.bin");

        thenTheFileGetsAnAichTree("done.bin", "hashed");
        thenTheAichHashIsTheFilesOwnNotTheLinks("done.bin");
        thenNoneOfTheseFilesIsShared("gone.bin", "never.bin");
    }

    @Test
    void shouldChangeTheCaseOfANameTooWhenRenaming() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/movie.avi", 3000, 34);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared movie.avi");

        whenTheFileIsRenamed("movie.avi", "Movie.avi");

        thenTheRenamedFileIsNamed("Movie.avi", "its new case");
        thenTheIncomingDirectoryHoldsOnly("Movie.avi");
        thenTheFileIsSharedUnderTheName("movie.avi", "Movie.avi");
    }

    @ParameterizedTest
    @ValueSource(strings = {"kept.bak", "kept.tmp", "001.part", "Desktop.ini", "link.lnk", ".hidden"})
    void shouldRefuseNamesThatAreNeverShared(String name) throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/kept.bin", 3000, 35);
        givenTheSharesAreOpen();
        givenTheSharesReport("shared kept.bin");

        whenTheFileIsRenamed("kept.bin", name);

        thenTheRenameIsRefusedWithTheRootCause(IllegalArgumentException.class);
        thenTheFileExists("incoming/kept.bin");
        thenTheFileIsSharedUnderTheName("kept.bin", "kept.bin");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void shouldKeepASharedTreeToItselfThroughJunctions() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("tree/sub/in.bin", 3000, 36);
        givenAFile("outside/secret.bin", 3000, 37);
        givenAJunction("tree/escape", "outside");
        givenAJunction("tree/loop", "tree");
        givenTheSharesAreOpen();

        whenTheTreeIsShared("tree");

        thenTheSharesReport("shared in.bin");
        thenTheSharesReportNothingWithin(3, TimeUnit.SECONDS, "nothing from outside the tree");
        thenTheOnlySharedFileIs("tree/sub/in.bin");
    }

    @Test
    void shouldForgetTheFilesNoLongerSharedWhenTheyAreNotToBeRemembered() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 20_000, 4);
        givenAFile("incoming/b.bin", 30_000, 5);
        givenTheSharesAreOpen();
        givenTheSharesReportInAnyOrder("shared a.bin", "shared b.bin");
        givenTheFileIsDeleted("incoming/b.bin");
        givenTheSharesAreScanned();
        givenTheSharesReportWithin(10, "unshared b.bin");
        givenTheKnownHashesAreThoseOf("a.bin", "b.bin");

        whenTheSharesAreClosedWithoutRememberingDownloads();

        thenKnownMetHoldsOnly("a.bin");
    }

    static Stream<String> privateDirectories() {
        // The user's home is absolute: resolving it against the test directory keeps it as it is.
        return Stream.of("config", "config/sub", "temp", "emule-config", System.getProperty("user.home"));
    }

    private void givenAnIncomingAndAConfigDirectory() throws IOException {
        this.incoming = Files.createDirectories(this.dir.resolve("incoming"));
        this.config = Files.createDirectories(this.dir.resolve("config"));
    }

    private void givenAFile(String path, int size, long seed) throws IOException {
        writeFile(path, bytes(size, seed));
    }

    private void givenAOneByteFile(String path) throws IOException {
        writeFile(path, new byte[] {1});
    }

    private void givenAnEmptyFile(String path) throws IOException {
        writeFile(path, new byte[0]);
    }

    private void givenAHiddenFile(String path, int size, long seed) throws IOException {
        Path hidden = writeFile(path, bytes(size, seed));
        if (Files.getFileStore(hidden).supportsFileAttributeView("dos")) {
            Files.setAttribute(hidden, "dos:hidden", true);
        }
    }

    private void givenACopyOf(String original, String path) throws IOException {
        writeFile(path, this.contents.get(original));
    }

    // A directory made later is watched and shared without a rescan.
    private void givenAFileInADirectoryMadeLater(String path, int size, long seed) throws IOException {
        givenAFile(path, size, seed);
    }

    private void givenNumberedFilesInTheIncomingDirectory(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            givenAFile("incoming/f" + i + ".bin", 3000, 20 + i);
        }
    }

    private void givenPrivateDirectoriesAndABackupBesideAFileToShare() throws IOException {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("config/cryptkey.dat", 300, 3);
        givenAFile("temp/x.bin", 300, 4);
        givenAFile("emule-config/" + SharedFiles.CLIENT_KEY, 300, 5);
        givenAFile("emule-config/known.met", 300, 6);
        givenAFile("incoming/001.part.met.bak", 300, 7);
        givenAFile("incoming/real.bin", 300, 8);
    }

    // A private directory already in the list, from an older version, stays unshared.
    private void givenTheSharedDirectoriesListHolds(String path) throws IOException {
        Files.write(
                this.config.resolve(SharedFiles.SHARED_DIRS),
                TextFiles.writeLines(List.of(this.dir.resolve(path).toString())));
    }

    private void givenUnreadableListsAndRecordsInTheConfigDirectory() throws IOException {
        // known.met is a directory: it can be neither read nor written.
        Files.createDirectories(this.config.resolve(SharedFiles.KNOWN_MET).resolve("x"));
        Files.write(this.config.resolve(SharedFiles.KNOWN2_MET), new byte[] {9, 9, 9});
        Files.createDirectories(this.config.resolve(SharedFiles.SHARED_DIRS));
        Files.write(
                this.config.resolve(SharedFiles.SHARED_TREES),
                TextFiles.writeLines(
                        List.of("\u0000bad", this.dir.resolve("missing").toString())));
    }

    private void givenTheFilesAndTheRecordsOfAnotherClient() throws Exception {
        givenAnIncomingAndAConfigDirectory();
        givenAFile("incoming/a.bin", 400_000, 40);
        givenAFile("incoming/b.bin", 400_000, 41);
        givenTheFilesWereModifiedAt(1_700_000_000_000L, "a.bin", "b.bin");
        Hash20 sha = Hash20.of(AichTree.sha1().digest(new byte[1]));
        this.records = List.of(
                record("a.bin", this.contents.get("a.bin"), TREE_A, this.modified),
                record("b.bin", this.contents.get("b.bin"), TREE_B, this.modified),
                new KnownMet.KnownFile(0, Hash16.parseHex("21".repeat(16)), List.of(), List.of()),
                new KnownMet.KnownFile(
                        0,
                        Hash16.parseHex("22".repeat(16)),
                        List.of(Hash16.parseHex("23".repeat(16))),
                        List.of(
                                new Tag.StringTag(TagName.id(FileTag.FILENAME), "odd.bin"),
                                new Tag.IntTag(TagName.id(FileTag.FILESIZE), 10))));
        this.trees = List.of(
                new Known2Met.Entry(TREE_A, List.of(sha)),
                new Known2Met.Entry(TREE_B, List.of(sha, sha, sha)),
                new Known2Met.Entry(Hash20.parseBase32("C".repeat(32)), List.of(sha)));
        this.shares = open(this.config, Files.createDirectories(this.dir.resolve("empty")));
    }

    private void givenTheFilesWereModifiedAt(long millis, String... names) throws IOException {
        this.modified = FileTime.fromMillis(millis);
        for (String name : names) {
            Files.setLastModifiedTime(this.incoming.resolve(name), this.modified);
        }
    }

    private void givenTheRecordsOfAnotherClientAreAdded() throws Exception {
        onLoop(() -> this.shares.addKnown(this.records, this.trees));
    }

    private void givenAJunction(String link, String target) throws Exception {
        junction(this.dir.resolve(link), this.dir.resolve(target));
    }

    private void givenTheSharesAreOpen() throws Exception {
        this.shares = open(this.config, this.incoming);
    }

    private void givenTheSharesAreClosed() throws Exception {
        closeTheShares();
    }

    private void givenTheSharesAreScanned() throws Exception {
        scanTheShares();
    }

    private void givenTheSharesReport(String... expected) throws InterruptedException {
        assertTheNextEvents(30, expected);
    }

    private void givenTheSharesReportWithin(long seconds, String... expected) throws InterruptedException {
        assertTheNextEvents(seconds, expected);
    }

    private void givenTheSharesReportInAnyOrder(String... expected) throws InterruptedException {
        assertThat(nextEvents(expected.length, 30)).containsExactlyInAnyOrder(expected);
    }

    private void givenTheEventsSoFarAreIgnored() {
        this.events.clear();
    }

    private void givenTheDirectoryIsShared(String path) throws Exception {
        shareTheDirectory(path);
    }

    private void givenTheTreeIsShared(String path) throws Exception {
        shareTheTree(path);
    }

    private void givenTheSharedFileHasTheSizeOfItsContent(String name) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.get(hash).orElseThrow().size())).isEqualTo(this.contents.get(name).length);
    }

    private void givenTheNumberOfSharedFilesIs(int count) throws Exception {
        assertThat(onLoop(this.shares::all)).hasSize(count);
    }

    private void givenTheConfigDirectoryHolds(String file) {
        assertThat(this.config.resolve(file)).exists();
    }

    // Known files are shared again at once, without hashing.
    private void givenAScanSharesTheKnownFilesAtOnce(int count) throws Exception {
        assertThat(onLoop(() -> {
                    this.shares.scan();
                    return this.shares.all().size();
                }))
                .isEqualTo(count);
    }

    private void givenTheFileIsDeleted(String path) throws IOException {
        Files.delete(this.dir.resolve(path));
    }

    private void givenTheSharedTreesAre(String path) throws Exception {
        assertThat(onLoop(this.shares::trees))
                .containsExactly(this.dir.resolve(path).toAbsolutePath());
    }

    private void givenNoDirectoryIsShared() throws Exception {
        assertThat(onLoop(this.shares::directories)).isEmpty();
    }

    private void givenTheUploadPriorityIs(String name, UploadPriority expected) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.priority(hash))).isEqualTo(expected);
    }

    private void givenTheUploadPriorityIsSet(String name, UploadPriority priority) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.setPriority(hash, priority))).isTrue();
    }

    private void givenTheUploadPriorityOfAnUnknownFileCannotBeSet(String hex, UploadPriority priority)
            throws Exception {
        assertThat(onLoop(() -> this.shares.setPriority(Hash16.parseHex(hex), priority)))
                .isFalse();
    }

    private void givenKnownMetHoldsOneRecordWithTheUploadPriority(int code) throws IOException {
        assertThat(KnownMet.read(Files.readAllBytes(this.config.resolve(SharedFiles.KNOWN_MET))))
                .singleElement()
                .satisfies(record -> assertThat(record.uploadPriority())
                        .as("the value other clients write")
                        .hasValue(code));
    }

    private void givenTheFileIsRenamed(String name, String newName) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.rename(hash, newName)))
                .hasValueSatisfying(f -> assertThat(f.name()).isEqualTo(newName));
    }

    private void givenTheAichTreeIsRequested(String name) throws Exception {
        Hash16 hash = hashOf(name);
        this.firstAichTree = onLoop(() -> this.shares.aichTree(hash)).orElseThrow();
    }

    private void givenTheOrderInWhichTheFilesAreShared(int count) throws InterruptedException {
        this.order = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            this.order.add(this.events.poll(30, TimeUnit.SECONDS).substring("shared ".length()));
        }
    }

    private void givenTheSecondCopyIsTracked(String name) throws Exception {
        Hash16 hash = hashOf(name);
        String shared = this.events.poll(30, TimeUnit.SECONDS);
        assertThat(shared).startsWith("shared ");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (onLoop(() -> this.shares.get(hash).orElseThrow().name()).equals(shared.substring(7))
                && System.nanoTime() < deadline) {
            // Until the second copy is hashed and tracked.
            Thread.sleep(50);
        }
        this.tracked = onLoop(() -> this.shares.get(hash).orElseThrow().path());
    }

    private void givenTheTrackedCopyIsDeleted() throws IOException {
        Files.delete(this.tracked);
    }

    private void givenTheHashOfTheSharedFileIsNoted() throws Exception {
        this.oldHash = onLoop(() -> this.shares.all().getFirst().hash());
    }

    private void givenTheFileChanges(String path, int size, long seed) throws IOException {
        Path file = this.dir.resolve(path);
        Files.write(file, bytes(size, seed));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
    }

    private void givenTheDownload(int number, String name) throws IOException {
        byte[] data = this.contents.get(name);
        this.download = PartFile.create(number, this.dir, name, data.length, hashOf(name), Optional.empty(), List.of());
    }

    private void givenTheDownloadWithTheAichHash(int number, String name, String aichHash) throws IOException {
        byte[] data = this.contents.get(name);
        this.linkAichHash = Hash20.parseBase32(aichHash);
        this.download = PartFile.create(
                number, this.dir, name, data.length, hashOf(name), Optional.of(this.linkAichHash), List.of());
    }

    // Hashed again for its AICH tree, and renamed meanwhile.
    private void givenTheDownloadFinishesAndIsRenamed(String name, String newName) throws Exception {
        finishTheDownloadAndRename(name, newName);
    }

    private void givenTheFileGetsAnAichTree(String name, String why) throws Exception {
        waitForTheAichTree(name, why);
    }

    // A large file hashed first keeps the hashing thread busy until the rename is done.
    private void givenTheHashingThreadBusyWithALargeFile(int number, long seed, String hex) throws Exception {
        Path large = Files.write(
                Files.createDirectories(this.dir.resolve("other")).resolve("large.bin"), bytes(32_000_000, seed));
        PartFile busy = PartFile.create(
                number, this.dir, "large.bin", Files.size(large), Hash16.parseHex(hex), Optional.empty(), List.of());
        onLoop(() -> {
            this.shares.finished(large, busy);
            return null;
        });
    }

    private void givenTheDownloadFinishedAt(String path) throws Exception {
        onLoop(() -> {
            this.shares.finished(this.dir.resolve(path), this.download);
            return null;
        });
    }

    private void givenTheKnownHashesAreThoseOf(String... names) throws Exception {
        List<Hash16> hashes = new ArrayList<>();
        for (String name : names) {
            hashes.add(hashOf(name));
        }
        assertThat(onLoop(this.shares::knownHashes))
                .as("downloaded before")
                .containsExactlyInAnyOrderElementsOf(hashes);
    }

    private void whenTheSharesAreOpened() throws Exception {
        this.shares = open(this.config, this.incoming);
    }

    private void whenTheSharesAreScanned() throws Exception {
        scanTheShares();
    }

    private void whenTheSharesAreClosed() throws Exception {
        closeTheShares();
    }

    private void whenTheSharesAreClosedAndScanned() throws Exception {
        onLoop(() -> {
            this.shares.close();
            this.shares.scan();
            return null;
        });
        this.shares = null;
    }

    private void whenTheSharesAreClosedWithoutRememberingDownloads() throws Exception {
        onLoop(() -> {
            this.shares.rememberDownloaded(false);
            this.shares.close();
            return null;
        });
        this.shares = null;
    }

    private void whenTheDirectoryIsShared(String path) throws Exception {
        shareTheDirectory(path);
    }

    private void whenTheTreeIsShared(String path) throws Exception {
        shareTheTree(path);
    }

    private void whenTheDirectoryIsUnshared(String path) throws Exception {
        onLoop(() -> {
            this.shares.removeDirectory(this.dir.resolve(path));
            return null;
        });
    }

    private void whenItIsAskedWhetherTheDirectoryIsPrivate(String path) throws Exception {
        this.directory = this.dir.resolve(path);
        this.privateDirectory = onLoop(() -> this.shares.isPrivate(this.directory));
    }

    private void whenThePrivateDirectoriesAreShared() {
        privateDirectories().forEach(path -> this.refusals.put(path, catchThrowable(() -> shareTheDirectory(path))));
    }

    private void whenTheFileIsRenamed(String name, String newName) {
        Hash16 hash = hashOf(name);
        this.newName = newName;
        this.exception = catchThrowable(() -> this.renamed = onLoop(() -> this.shares.rename(hash, newName)));
    }

    private void whenAnUnknownFileIsRenamed(String hex, String newName) {
        this.newName = newName;
        this.exception =
                catchThrowable(() -> this.renamed = onLoop(() -> this.shares.rename(Hash16.parseHex(hex), newName)));
    }

    private void whenTheUploadPriorityIsSet(String name, UploadPriority priority) throws Exception {
        Hash16 hash = hashOf(name);
        onLoop(() -> this.shares.setPriority(hash, priority));
    }

    private void whenTheUploadPriorityIsAskedFor(String hex) throws Exception {
        this.priority = onLoop(() -> this.shares.priority(Hash16.parseHex(hex)));
    }

    private void whenTheAichTreeIsRequested(String name) throws Exception {
        Hash16 hash = hashOf(name);
        this.aichTree = onLoop(() -> this.shares.aichTree(hash));
    }

    // The second file goes while the directory is read: it is listed, but gone.
    private void whenTheSharesAreOpenedAndTheSecondFileGoesWhileTheDirectoryIsRead() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        this.shares = onLoop(() -> SharedFiles.open(
                this.loop, this.config, () -> List.of(this.incoming), () -> List.of(), new SharedFiles.Listener() {
                    @Override
                    public void shared(SharedFiles.SharedFile file) {
                        if (seen.isEmpty()) {
                            try {
                                Files.delete(SharedFilesTest.this.incoming.resolve(SharedFilesTest.this.order.get(1)));
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                        seen.add(file.name());
                    }

                    @Override
                    public void unshared(SharedFiles.SharedFile file) {
                        seen.add("unshared " + file.name());
                    }
                }));
    }

    private void whenTheDownloadFinishesAndIsRenamed(String name, String newName) throws Exception {
        finishTheDownloadAndRename(name, newName);
    }

    private void whenADownloadFinishesAfterAScanQueuedItsFileAndIsRenamed(String newName) throws Exception {
        byte[] data = bytes(600_000, 41);
        this.contents.put("done.bin", data);
        Hash16 hash = hashOf("done.bin");
        Path completed = Files.write(
                Files.createDirectories(this.dir.resolve("completed")).resolve("done.bin"), data);
        PartFile finished = PartFile.create(1, this.dir, "done.bin", data.length, hash, Optional.empty(), List.of());
        onLoop(() -> {
            Path done = Files.move(completed, this.incoming.resolve("done.bin"));
            this.shares.scan();
            this.shares.finished(done, finished);
            return this.shares.rename(hash, newName);
        });
    }

    // Found by the scan, then gone before the hashing thread reads it.
    private void whenADirectoryIsSharedAndItsFileGoesBeforeItIsHashed(String path, String gone) throws Exception {
        onLoop(() -> {
            this.shares.addDirectory(this.dir.resolve(path));
            Files.delete(this.dir.resolve(gone));
            return null;
        });
    }

    private void whenTheRecordsOfAnotherClientAreAdded() throws Exception {
        this.added = onLoop(() -> this.shares.addKnown(this.records, this.trees));
    }

    private void thenTheSharesReport(String... expected) throws InterruptedException {
        assertTheNextEvents(30, expected);
    }

    private void thenTheSharesReportWithin(long seconds, String... expected) throws InterruptedException {
        assertTheNextEvents(seconds, expected);
    }

    private void thenTheSharesReportInAnyOrder(String... expected) throws InterruptedException {
        assertThat(nextEvents(expected.length, 30)).containsExactlyInAnyOrder(expected);
    }

    private void thenTheSharesReportNothingWithin(long timeout, TimeUnit unit) throws InterruptedException {
        assertThat(this.events.poll(timeout, unit)).isNull();
    }

    private void thenTheSharesReportNothingWithin(long timeout, TimeUnit unit, String why) throws InterruptedException {
        assertThat(this.events.poll(timeout, unit)).as(why).isNull();
    }

    private void thenTheNumberOfSharedFilesIs(int count) throws Exception {
        assertThat(onLoop(this.shares::all)).hasSize(count);
    }

    private void thenNoDirectoryIsShared() throws Exception {
        assertThat(onLoop(this.shares::directories)).isEmpty();
    }

    private void thenNoTreeIsShared() throws Exception {
        assertThat(onLoop(this.shares::trees)).isEmpty();
    }

    private void thenTheOnlySharedTreeIs(String path) throws Exception {
        assertThat(onLoop(this.shares::trees)).containsExactly(this.dir.resolve(path));
    }

    private void thenTheDirectoryIsPrivate() {
        assertThat(this.privateDirectory).as(this.directory.toString()).isTrue();
    }

    private void thenTheDirectoryIsNotPrivate() {
        assertThat(this.privateDirectory).isFalse();
    }

    private void thenEveryPrivateDirectoryIsRefused() {
        assertThat(this.refusals).isNotEmpty();
        this.refusals.forEach(
                (path, refusal) -> assertThat(refusal).as(path).hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    private void thenTheUploadPriorityIs(String name, UploadPriority expected) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.priority(hash))).isEqualTo(expected);
    }

    private void thenTheUploadPriorityAnsweredIs(UploadPriority expected) {
        assertThat(this.priority).isEqualTo(expected);
    }

    private void thenTheRenamedFileIsNamed(String name, String why) {
        assertThat(this.renamed).map(SharedFiles.SharedFile::name).as(why).contains(name);
    }

    private void thenAFileIsRenamed(String why) {
        assertThat(this.renamed).as(why).isPresent();
    }

    private void thenNothingIsRenamed() {
        assertThat(this.exception).isNull();
        assertThat(this.renamed).isEmpty();
    }

    private void thenTheRenameIsRefusedWithTheRootCause(Class<? extends Throwable> type) {
        assertThat(this.exception).as(this.newName).hasRootCauseInstanceOf(type);
    }

    private void thenTheFileHasTheContentOf(String path, String original) {
        assertThat(this.dir.resolve(path)).hasBinaryContent(this.contents.get(original));
    }

    private void thenTheFileExists(String path) {
        assertThat(this.dir.resolve(path)).exists();
    }

    private void thenTheFileDoesNotExist(String path) {
        assertThat(this.dir.resolve(path)).doesNotExist();
    }

    private void thenTheIncomingDirectoryHoldsOnly(String name) throws IOException {
        try (Stream<Path> names = Files.list(this.incoming)) {
            assertThat(names.map(p -> p.getFileName().toString())).containsExactly(name);
        }
    }

    private void thenTheFileIsSharedUnderTheName(String file, String name) throws Exception {
        Hash16 hash = hashOf(file);
        assertThat(onLoop(() -> this.shares.get(hash).orElseThrow().name())).isEqualTo(name);
    }

    private void thenTheFileIsSharedAs(String file, String name) throws Exception {
        Hash16 hash = hashOf(file);
        assertThat(onLoop(() -> this.shares.get(hash)))
                .hasValueSatisfying(f -> assertThat(f.path()).isEqualTo(this.incoming.resolve(name)));
    }

    private void thenTheOnlySharedFileIs(String path) throws Exception {
        assertThat(onLoop(this.shares::all))
                .singleElement()
                .satisfies(f -> assertThat(f.path()).isEqualTo(this.dir.resolve(path)));
    }

    private void thenTheSameAichTreeIsGiven() {
        assertThat(this.aichTree).containsSame(this.firstAichTree);
    }

    private void thenTheAichTreeCoversTheFile(String name) {
        assertThat(this.firstAichTree.size()).isEqualTo(this.contents.get(name).length);
    }

    private void thenTheFileGetsAnAichTree(String name, String why) throws Exception {
        waitForTheAichTree(name, why);
    }

    private void thenTheFileHasAnAichTree(String name) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.aichTree(hash))).isPresent();
    }

    private void thenTheFileHasNoAichTree(String name, String why) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.aichTree(hash))).as(why).isEmpty();
    }

    private void thenTheFileWithTheHashKeepsNoAichTree(String hex) throws Exception {
        assertThat(onLoop(() -> this.shares.get(Hash16.parseHex(hex))))
                .as("a file that is not what was downloaded keeps no tree")
                .hasValueSatisfying(f -> assertThat(f.aichHash()).isEmpty());
    }

    private void thenTheFileIsSharedWithoutHashingWithTheAichHash(String name, Hash20 aichHash) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.get(hash)))
                .as("shared without hashing")
                .hasValueSatisfying(f -> assertThat(f.aichHash()).contains(aichHash));
    }

    private void thenTheAichHashIsTheFilesOwnNotTheLinks(String name) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.get(hash).orElseThrow().aichHash()))
                .as("the file's own AICH hash, not the link's")
                .hasValueSatisfying(h -> assertThat(h).isNotEqualTo(this.linkAichHash));
    }

    private void thenNoneOfTheseFilesIsShared(String... names) throws Exception {
        assertThat(onLoop(() -> this.shares.all().stream()
                        .map(SharedFiles.SharedFile::name)
                        .toList()))
                .doesNotContain(names);
    }

    private void thenTheOtherCopyIsShared(String name) throws Exception {
        Hash16 hash = hashOf(name);
        assertThat(onLoop(() -> this.shares.get(hash)))
                .as("the other copy")
                .hasValueSatisfying(f -> assertThat(f.path()).isNotEqualTo(this.tracked));
    }

    private void thenNoFileIsSharedUnderTheOldHash() throws Exception {
        assertThat(onLoop(() -> this.shares.get(this.oldHash))).isEmpty();
    }

    private void thenAllTheFilesButTheSecondAreShared() throws Exception {
        assertThat(onLoop(() -> this.shares.all().stream()
                        .map(SharedFiles.SharedFile::name)
                        .toList()))
                .containsExactlyInAnyOrderElementsOf(this.order.stream()
                        .filter(n -> !n.equals(this.order.get(1)))
                        .toList());
    }

    private void thenTheConfigFileIsADirectory(String file) {
        assertThat(this.config.resolve(file)).isDirectory();
    }

    private void thenTheNumberOfRecordsTakenIs(int count) {
        assertThat(this.added).isEqualTo(count);
    }

    private void thenKnownMetNamesTheRecordWithItsOtherTags(String file, String name) throws IOException {
        Hash16 hash = hashOf(file);
        assertThat(KnownMet.read(Files.readAllBytes(this.config.resolve(SharedFiles.KNOWN_MET))))
                .filteredOn(r -> r.fileHash().equals(hash))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.name()).contains(name);
                    assertThat(r.tags())
                            .as("its other tags stay")
                            .anyMatch(t -> !t.name().isId());
                });
    }

    private void thenKnownMetHoldsOnly(String name) throws IOException {
        Hash16 hash = hashOf(name);
        assertThat(KnownMet.read(Files.readAllBytes(this.config.resolve(SharedFiles.KNOWN_MET))))
                .singleElement()
                .satisfies(record -> assertThat(record.fileHash()).isEqualTo(hash));
    }

    private Path writeFile(String path, byte[] data) throws IOException {
        Path file = this.dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, data);
        this.contents.put(file.getFileName().toString(), data);
        return file;
    }

    private Hash16 hashOf(String name) {
        byte[] data = this.contents.get(name);
        return Ed2k.md4(data, 0, data.length);
    }

    private List<String> nextEvents(int count, long seconds) throws InterruptedException {
        List<String> next = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            next.add(this.events.poll(seconds, TimeUnit.SECONDS));
        }
        return next;
    }

    private void assertTheNextEvents(long seconds, String... expected) throws InterruptedException {
        assertThat(nextEvents(expected.length, seconds)).containsExactly(expected);
    }

    private void scanTheShares() throws Exception {
        onLoop(() -> {
            this.shares.scan();
            return null;
        });
    }

    private void shareTheDirectory(String path) throws Exception {
        onLoop(() -> {
            this.shares.addDirectory(this.dir.resolve(path));
            return null;
        });
    }

    private void shareTheTree(String path) throws Exception {
        onLoop(() -> {
            this.shares.addDirectory(this.dir.resolve(path), true);
            return null;
        });
    }

    private void closeTheShares() throws Exception {
        onLoop(() -> {
            this.shares.close();
            return null;
        });
        this.shares = null;
    }

    private void finishTheDownloadAndRename(String name, String newName) throws Exception {
        Hash16 hash = hashOf(name);
        Path done = this.incoming.resolve(name);
        onLoop(() -> {
            this.shares.finished(done, this.download);
            return this.shares.rename(hash, newName);
        });
    }

    private void waitForTheAichTree(String name, String why) throws Exception {
        Hash16 hash = hashOf(name);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (onLoop(() -> this.shares.aichTree(hash)).isEmpty()) {
            assertThat(System.nanoTime()).as(why).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static KnownMet.KnownFile record(String name, byte[] data, Hash20 aich, FileTime time) {
        return new KnownMet.KnownFile(
                time.toMillis() / 1000,
                Ed2k.md4(data, 0, data.length),
                List.of(),
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), data.length),
                        new Tag.StringTag(TagName.id(FileTag.AICH_HASH), aich.toBase32()),
                        new Tag.StringTag(TagName.of("note"), "from another client")));
    }

    private static void junction(Path link, Path target) throws Exception {
        Process mklink = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        mklink.getInputStream().readAllBytes();
        assertThat(mklink.waitFor()).as("mklink /J").isZero();
    }

    private SharedFiles open(Path config, Path incoming) throws Exception {
        return onLoop(() -> SharedFiles.open(
                this.loop,
                config,
                () -> List.of(incoming),
                () -> List.of(this.dir.resolve("temp")),
                new SharedFiles.Listener() {
                    @Override
                    public void shared(SharedFiles.SharedFile file) {
                        SharedFilesTest.this.events.add("shared " + file.name());
                    }

                    @Override
                    public void unshared(SharedFiles.SharedFile file) {
                        SharedFilesTest.this.events.add("unshared " + file.name());
                    }
                }));
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    private static byte[] bytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
