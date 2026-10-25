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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Ticker;
import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.config.NextDownload;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.peer.Identities;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.peer.PeerLinks;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.util.Ipv4;

@Timeout(60)
class DownloadManagerTest {

    private static final int PORT = 4662;
    private static final int OUR_IP = Ipv4.of(198, 51, 100, 7);
    private static final int BLOCKED_IP = Ipv4.of(203, 0, 113, 9);
    private static final int CORRUPT = Ipv4.of(192, 0, 2, 66);
    private static final int HONEST = Ipv4.of(192, 0, 2, 67);
    private static final Hash16 CORRUPT_FILE = Hash16.parseHex("c0".repeat(16));
    private static final Hash16 IDLE_FILE = Hash16.parseHex("1d".repeat(16));
    // A link with a loopback, a blocked and a LAN source.
    private static final String LINK_WITH_SOURCES = "ed2k://|file|link.bin|5000|" + "34".repeat(16)
            + "|/|sources,127.0.0.1:" + PORT + "," + Ipv4.format(BLOCKED_IP) + ":" + PORT + ",172.20.0.9:" + PORT
            + "|/";
    private static final String LINK_TO_STOP =
            "ed2k://|file|stop.bin|5000|" + "56".repeat(16) + "|/|sources,192.0.2.10:" + PORT + "|/";
    private static final String LINK_WITH_ODD_SOURCES = "ed2k://|file|odd.bin|5000|" + "4a".repeat(16)
            + "|/|sources,0.1.2.3:" + PORT + ",224.0.0.251:" + PORT + ",240.1.2.3:" + PORT
            + ",255.255.255.255:" + PORT + "|/";

    @TempDir
    Path dir;

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final CompletableFuture<Consumer<List<DownloadManager.KadSource>>> kadSearch = new CompletableFuture<>();
    private DownloadManager manager;
    private List<Category> categories = List.of();
    private DownloadManager.Listener listener = new Quiet();
    private final List<String> banned = new CopyOnWriteArrayList<>();
    private Consumer<List<DownloadManager.KadSource>> kadFound;
    private final List<DownloadInfo> added = new CopyOnWriteArrayList<>();
    private final List<String> keptLinks = new CopyOnWriteArrayList<>();
    private HighIdServer server;
    private Hash16 hash;
    private DownloadInfo info;
    private boolean answer;
    private Optional<DownloadInfo> next;
    private Optional<Path> preview;
    private Throwable exception;
    private DownloadManager.Settings settings =
            new DownloadManager.Settings(ObfuscationMode.SUPPORTED, 100, PORT, 0, true);

    @AfterEach
    void stop() throws Exception {
        if (this.manager != null) {
            onLoop(() -> {
                this.manager.close();
                return null;
            });
        }
        this.loop.close();
    }

    @Test
    void shouldDropASourceFoundSendingCorruptDataAndReportItForABan() throws Exception {
        givenAPausedDownloadWithTheKadSources(CORRUPT, HONEST);

        whenTheSourceIsFoundSendingCorruptData(CORRUPT);

        thenItsSourcesAre(HONEST);
        thenTheBannedAre(CORRUPT);
    }

    @Test
    void shouldDeleteARunningDownloadIdleForTheDaysSetAndKeepItsLinkFirst() throws Exception {
        givenDownloadsDeletedAfterIdleDays(1);
        givenTheLinksKept(true);
        givenADownloadIdleForSeconds("idle.bin", IDLE_FILE, 86_399);

        whenTheManagerHasRunAWhile();

        thenTheDownloadIsGone(true);
        thenTheKeptLinksAre("ed2k://|file|idle.bin|5000|" + IDLE_FILE.toHex() + "|/");
    }

    @Test
    void shouldLeaveAPausedDownloadAloneWhateverItsIdleTime() throws Exception {
        givenDownloadsDeletedAfterIdleDays(1);
        givenTheLinksKept(true);
        givenAPausedDownloadIdleForSeconds("paused.bin", IDLE_FILE, 86_400);

        whenTheManagerHasRunAWhile();

        thenTheDownloadIsGone(false);
        thenTheKeptLinksAre();
    }

    @Test
    void shouldKeepADownloadWhoseLinkCannotBeKeptAndCountItsDaysAgain() throws Exception {
        givenDownloadsDeletedAfterIdleDays(1);
        givenTheLinksKept(false);
        givenADownloadIdleForSeconds("kept.bin", IDLE_FILE, 86_399);

        whenTheManagerHasRunAWhile();

        thenTheDownloadIsGone(false);
        thenItsIdleTimeIsBelow(86_399);
    }

    @Test
    void shouldNotTakeAgainASourceFoundSendingCorruptData() throws Exception {
        givenAPausedDownloadWithTheKadSources(CORRUPT, HONEST);
        givenTheSourceWasFoundSendingCorruptData(CORRUPT);

        whenKadFindsTheSourcesAgain(CORRUPT, HONEST);

        thenItsSourcesAre(HONEST);
    }

    private void givenAPausedDownloadWithTheKadSources(int... ips) throws Exception {
        List<String> banned = this.banned;
        this.listener = new Quiet() {
            @Override
            public void corruptSender(InetAddress ip) {
                banned.add(ip.getHostAddress());
            }
        };
        DownloadManager manager = manager();
        onLoop(() -> manager.add(Ed2kLink.FileLink.of("corrupt.bin", 5000, CORRUPT_FILE)));
        this.kadFound = this.kadSearch.get(30, TimeUnit.SECONDS);
        onLoop(() -> manager.pause(CORRUPT_FILE));
        whenKadFindsTheSourcesAgain(ips);
    }

    private void givenTheSourceWasFoundSendingCorruptData(int ip) throws Exception {
        whenTheSourceIsFoundSendingCorruptData(ip);
    }

    private void whenTheSourceIsFoundSendingCorruptData(int ip) throws Exception {
        onLoop(() -> {
            this.manager.banCorruptSender(InetAddresses.fromInteger(ip));
            return null;
        });
    }

    private void whenKadFindsTheSourcesAgain(int... ips) throws Exception {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (int ip : ips) {
            addresses.add(address(ip, PORT));
        }
        onLoop(() -> {
            this.kadFound.accept(direct(addresses.toArray(InetSocketAddress[]::new)));
            return null;
        });
    }

    private void thenItsSourcesAre(int... ips) throws Exception {
        List<String> expected = new ArrayList<>();
        for (int ip : ips) {
            expected.add(Ipv4.format(ip) + ":" + PORT);
        }
        assertThat(onLoop(() -> this.manager.sources().stream()
                        .filter(s -> s.file().equals(CORRUPT_FILE))
                        .map(SourceInfo::address)
                        .toList()))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    private void thenTheBannedAre(int... ips) {
        List<String> expected = new ArrayList<>();
        for (int ip : ips) {
            expected.add(Ipv4.format(ip));
        }
        assertThat(this.banned).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void shouldKeepOnlyUsableSourcesFromKad() throws Exception {
        givenTheDownloadManager();
        givenADownloadThatKadSearches("kad.bin", hashOf("12"));
        givenTheDownloadPaused();

        whenKadFinds(
                address(Ipv4.of(127, 0, 0, 1), PORT),
                address(Ipv4.of(224, 0, 0, 1), PORT),
                address(BLOCKED_IP, PORT),
                address(OUR_IP, PORT),
                address(Ipv4.of(192, 168, 1, 20), PORT),
                address(Ipv4.of(10, 1, 2, 3), PORT),
                address(Ipv4.of(192, 0, 2, 10), 0),
                address(Ipv4.of(192, 0, 2, 10), PORT));

        thenTheResultingDownloadHasSources(1);
    }

    @Test
    void shouldTakeTheLinkSourcesTheIpFilterAllows() throws Exception {
        givenTheDownloadManager();

        whenTheLinkIsAddedPaused(LINK_WITH_SOURCES);

        thenTheResultingDownloadHasSources(2, "the user chose them, LAN or loopback");
    }

    @Test
    void shouldTakeASourceAddedByHand() throws Exception {
        givenTheDownloadManager();
        givenTheLinkAddedPaused(LINK_WITH_SOURCES);

        whenASourceIsAddedByHand(address(Ipv4.of(10, 0, 0, 5), PORT));

        thenItIsAccepted();
        thenTheDownloadNowHasSources(3);
    }

    @Test
    void shouldNotTakeASourceAddedByHandTwice() throws Exception {
        givenTheDownloadManager();
        givenTheLinkAddedPaused(LINK_WITH_SOURCES);
        givenASourceAddedByHand(address(Ipv4.of(10, 0, 0, 5), PORT));

        whenASourceIsAddedByHand(address(Ipv4.of(10, 0, 0, 5), PORT));

        thenItIsRefused("known already");
        thenTheDownloadNowHasSources(3);
    }

    @Test
    void shouldApplyTheIpFilterToASourceAddedByHand() throws Exception {
        givenTheDownloadManager();
        givenTheLinkAddedPaused(LINK_WITH_SOURCES);

        whenASourceIsAddedByHand(address(BLOCKED_IP, PORT));

        thenItIsRefused("the IP filter still applies");
    }

    @Test
    void shouldForgetTheSourcesAndKeepThePriorityWhenStopping() throws Exception {
        givenTheDownloadManager();
        givenTheLinkAddedWithThePriority(LINK_TO_STOP, DownloadPriority.HIGH);

        whenTheDownloadIsStopped();

        thenTheResultingDownloadIs(DownloadInfo.Status.STOPPED);
        thenTheResultingDownloadHasSources(0);
        thenTheResultingDownloadHasThePriority(DownloadPriority.HIGH);
    }

    @Test
    void shouldResumeAStoppedDownload() throws Exception {
        givenTheDownloadManager();
        givenTheLinkAddedWithThePriority(LINK_TO_STOP, DownloadPriority.HIGH);
        givenTheDownloadStopped();

        whenTheDownloadIsResumed();

        thenTheResultingDownloadIs(DownloadInfo.Status.DOWNLOADING);
    }

    @Test
    void shouldNotStopADownloadWeDoNotHave() throws Exception {
        givenTheDownloadManager();

        whenADownloadWeDoNotHaveIsStopped(hashOf("00"));

        thenItIsRefused();
    }

    @Test
    void shouldResumeNoPausedDownloadWhenAskedForNone() throws Exception {
        givenTheCategories(category(1, "Films", 1, DownloadPriority.AUTO, "*.mkv"));
        givenTheDownloadManager();
        givenAPausedFilmAStoppedUrgentFileAndAPausedLaterFile(hashOf("a1"), hashOf("a2"), hashOf("a3"));

        whenTheNextDownloadIsResumed(1, NextDownload.NONE);

        thenNothingIsResumed();
    }

    @Test
    void shouldResumeThePausedDownloadOfTheCategoryFirstThoughOfLowPriority() throws Exception {
        givenTheCategories(category(1, "Films", 1, DownloadPriority.AUTO, "*.mkv"));
        givenTheDownloadManager();
        givenAPausedFilmAStoppedUrgentFileAndAPausedLaterFile(hashOf("a1"), hashOf("a2"), hashOf("a3"));

        whenTheNextDownloadIsResumed(1, NextDownload.SAME_CATEGORY_FIRST);

        thenTheResumedDownloadIs(hashOf("a1"), "the category's, though of low priority");
    }

    @Test
    void shouldResumeNothingWhenNoneIsLeftInTheCategory() throws Exception {
        givenTheCategories(category(1, "Films", 1, DownloadPriority.AUTO, "*.mkv"));
        givenTheDownloadManager();
        givenAPausedFilmAStoppedUrgentFileAndAPausedLaterFile(hashOf("a1"), hashOf("a2"), hashOf("a3"));
        givenTheNextDownloadResumed(1, NextDownload.SAME_CATEGORY_FIRST);

        whenTheNextDownloadIsResumed(1, NextDownload.SAME_CATEGORY);

        thenNothingIsResumed("none left in the category");
    }

    @Test
    void shouldResumeTheDownloadOfHighestPriorityThenAStoppedOneToo() throws Exception {
        givenTheCategories(category(1, "Films", 1, DownloadPriority.AUTO, "*.mkv"));
        givenTheDownloadManager();
        givenAPausedFilmAStoppedUrgentFileAndAPausedLaterFile(hashOf("a1"), hashOf("a2"), hashOf("a3"));
        givenTheNextDownloadResumed(1, NextDownload.SAME_CATEGORY_FIRST);

        whenTheNextDownloadIsResumed(1, NextDownload.SAME_CATEGORY_FIRST);

        thenTheResumedDownloadIs(hashOf("a2"), "then any, a stopped one too");
    }

    @Test
    void shouldResumeTheLastWaitingDownload() throws Exception {
        givenTheCategories(category(1, "Films", 1, DownloadPriority.AUTO, "*.mkv"));
        givenTheDownloadManager();
        givenAPausedFilmAStoppedUrgentFileAndAPausedLaterFile(hashOf("a1"), hashOf("a2"), hashOf("a3"));
        givenTheNextDownloadResumed(1, NextDownload.SAME_CATEGORY_FIRST);
        givenTheNextDownloadResumed(1, NextDownload.SAME_CATEGORY_FIRST);

        whenTheNextDownloadIsResumed(Category.NONE, NextDownload.ANY);

        thenTheResumedDownloadIs(DownloadInfo.Status.DOWNLOADING);
    }

    @Test
    void shouldResumeNothingOnceAllDownloadsRun() throws Exception {
        givenTheCategories(category(1, "Films", 1, DownloadPriority.AUTO, "*.mkv"));
        givenTheDownloadManager();
        givenAPausedFilmAStoppedUrgentFileAndAPausedLaterFile(hashOf("a1"), hashOf("a2"), hashOf("a3"));
        givenTheNextDownloadResumed(1, NextDownload.SAME_CATEGORY_FIRST);
        givenTheNextDownloadResumed(1, NextDownload.SAME_CATEGORY_FIRST);
        givenTheNextDownloadResumed(Category.NONE, NextDownload.ANY);

        whenTheNextDownloadIsResumed(Category.NONE, NextDownload.ANY);

        thenNothingIsResumed();
    }

    @Test
    void shouldPutANewDownloadInTheFirstCategoryItsNameMatches() throws Exception {
        givenTheCategories(
                category(1, "Films", 1, DownloadPriority.HIGH, "*.mkv"),
                category(2, "Video", 0, DownloadPriority.AUTO, "mkv|avi"));
        givenTheDownloadManager();

        whenADownloadIsAdded("Film.MKV", hashOf("bc"));

        thenTheResultingDownloadIsInTheCategory(1);
        thenTheResultingDownloadHasThePriority(DownloadPriority.HIGH, "the category's priority");
    }

    @Test
    void shouldPutANewDownloadThatMatchesNoCategoryInNone() throws Exception {
        givenTheCategories(
                category(1, "Films", 1, DownloadPriority.HIGH, "*.mkv"),
                category(2, "Video", 0, DownloadPriority.AUTO, "mkv|avi"));
        givenTheDownloadManager();

        whenADownloadIsAdded("notes.txt", hashOf("cd"));

        thenTheResultingDownloadIsInTheCategory(Category.NONE);
    }

    @Test
    void shouldMoveADownloadToAnotherCategory() throws Exception {
        givenTheCategories(
                category(1, "Films", 1, DownloadPriority.HIGH, "*.mkv"),
                category(2, "Video", 0, DownloadPriority.AUTO, "mkv|avi"));
        givenTheDownloadManager();
        givenADownload("notes.txt", hashOf("cd"));

        whenTheDownloadIsMovedToTheCategory(2);

        thenItIsAccepted();
        thenTheDownloadIsNowInTheCategory(hashOf("cd"), 2);
    }

    @Test
    void shouldRenameADownloadWithANameEveryFileSystemTakes() throws Exception {
        givenTheDownloadManager();
        givenADownload("draft.iso", hashOf("f1"));

        whenTheDownloadIsRenamed("a/b:c?.iso");

        thenItIsAccepted();
        thenTheDownloadIsNamed(hashOf("f1"), "a_b_c_.iso");
    }

    @Test
    void shouldKeepTheNameOfARenamedDownloadAfterARestart() throws Exception {
        givenTheDownloadManager();
        givenADownload("draft.iso", hashOf("f2"));
        givenTheDownloadRenamed("Debian 13.iso");

        whenTheDownloadManagerIsClosedAndOpenedAgain();

        thenTheDownloadIsNamed(hashOf("f2"), "Debian 13.iso");
    }

    @Test
    void shouldCleanUpTheNameOfANewDownloadWhenAsked() throws Exception {
        givenTheNamesOfNewDownloadsCleanedUpOf("sponsored");
        givenTheDownloadManager();

        whenADownloadIsAdded("Big_Buck_Bunny.sponsored.[www.site.com].mkv", hashOf("f5"));

        thenTheDownloadIsNamed(hashOf("f5"), "Big Buck Bunny.mkv");
    }

    @Test
    void shouldKeepTheNameOfANewDownloadAsItComesByDefault() throws Exception {
        givenTheDownloadManager();

        whenADownloadIsAdded("Big_Buck_Bunny.mkv", hashOf("f6"));

        thenTheDownloadIsNamed(hashOf("f6"), "Big_Buck_Bunny.mkv");
    }

    @Test
    void shouldRefuseABlankNameForADownload() throws Exception {
        givenTheDownloadManager();
        givenADownload("draft.iso", hashOf("f3"));

        whenTheDownloadIsRenamed("  ");

        thenExceptionOccurred(IllegalArgumentException.class);
        thenTheDownloadIsNamed(hashOf("f3"), "draft.iso");
    }

    @Test
    void shouldRenameNoDownloadWeDoNotHave() throws Exception {
        givenTheDownloadManager();

        whenADownloadWeDoNotHaveIsRenamed(hashOf("f4"));

        thenItIsRefused();
    }

    @Test
    void shouldNotMoveADownloadToACategoryThatDoesNotExist() throws Exception {
        givenTheCategories(
                category(1, "Films", 1, DownloadPriority.HIGH, "*.mkv"),
                category(2, "Video", 0, DownloadPriority.AUTO, "mkv|avi"));
        givenTheDownloadManager();
        givenADownload("notes.txt", hashOf("cd"));

        whenTheDownloadIsMovedToTheCategory(9);

        thenItIsRefused("no such category");
    }

    @Test
    void shouldTakeADownloadOutOfACategoryThatIsGone() throws Exception {
        givenTheCategories(
                category(1, "Films", 1, DownloadPriority.HIGH, "*.mkv"),
                category(2, "Video", 0, DownloadPriority.AUTO, "mkv|avi"));
        givenTheDownloadManager();
        givenAPausedDownload("Film.MKV", 5000, hashOf("bc"));
        givenAPausedDownload("notes.txt", 5000, hashOf("cd"));
        givenTheDownloadMovedToTheCategory(2);
        givenOnlyTheFirstCategoryLeft();

        whenTheCategoriesChange();

        thenTheDownloadIsNowInTheCategory(hashOf("cd"), Category.NONE, "its category is gone");
        thenTheDownloadIsNowInTheCategory(hashOf("bc"), 1);
    }

    @Test
    void shouldAskTheServerForHigherPrioritiesFirst() throws Exception {
        givenTheDownloadManagerWithAServerThatAnswers();

        whenALowAndAHighPriorityDownloadAreAddedTogether(hashOf("78"), hashOf("9a"));

        thenTheServerIsAskedFirstFor(hashOf("9a"), hashOf("78"));
    }

    @Test
    void shouldChangeNothingWhenResumingARunningDownload() throws Exception {
        givenTheDownloadManagerWithAServerThatAnswers();
        givenARunningDownloadTheServerWasAskedFor("running.bin", hashOf("bd"));

        whenTheRunningDownloadIsResumed();

        thenItIsRefused("it runs already");
        thenTheServerIsNotAskedAgainBeforeItsTime();
    }

    @Test
    void shouldKeepTheFirstAddedFirstAfterARestart() throws Exception {
        givenTheDownloadManager();
        givenTwoPausedDownloadsAfterACancelledOne(hashOf("e1"), hashOf("e2"), hashOf("e3"));
        givenTheDownloadManagerRestarted();

        whenTheNextDownloadIsResumed(Category.NONE, NextDownload.ANY);

        thenTheResumedDownloadIs(hashOf("e2"));
    }

    @Test
    void shouldRefuseLinkSourcesNoClientCanHave() throws Exception {
        givenTheDownloadManager();

        whenTheLinkIsAddedPaused(LINK_WITH_ODD_SOURCES);

        thenTheResultingDownloadHasSources(0);
    }

    @ParameterizedTest
    @MethodSource("addressesNoClientCanHave")
    void shouldRefuseSourcesByHandNoClientCanHave(int ip) throws Exception {
        givenTheDownloadManager();
        givenTheLinkAddedPaused(LINK_WITH_ODD_SOURCES);

        whenASourceIsAddedByHand(address(ip, PORT));

        thenItIsRefused(Ipv4.format(ip));
    }

    @Test
    void shouldTakeALoopbackSourceByHandAsTheUserChose() throws Exception {
        givenTheDownloadManager();
        givenTheLinkAddedPaused(LINK_WITH_ODD_SOURCES);

        whenASourceIsAddedByHand(address(Ipv4.of(127, 0, 0, 1), PORT));

        thenItIsAccepted("loopback, as the user chose");
    }

    @Test
    void shouldTellTheCategoryAndPriorityInTheAddedEvent() throws Exception {
        givenTheCategories(category(1, "Films", 1, DownloadPriority.HIGH, "*.mkv"));
        givenAListenerOfAddedDownloads();
        givenTheDownloadManager();

        whenADownloadIsAddedPaused("film.mkv", hashOf("4b"));

        thenOneAddedEventTells(1, DownloadPriority.HIGH);
    }

    @Test
    void shouldGiveNoSourcesWhenALinkOfAnotherSizeIsAddedAgain() throws Exception {
        givenTheDownloadManager();
        givenAPausedDownload("same.bin", 5000, hashOf("4c"));

        whenTheLinkIsAddedAgain(6000, Optional.empty(), Ipv4.of(192, 0, 2, 1));

        thenTheResultingDownloadHasSources(0, "another size");
    }

    @Test
    void shouldGiveSourcesAndTheAichHashWhenALinkOfTheSameFileIsAddedAgain() throws Exception {
        givenTheDownloadManager();
        givenAPausedDownload("same.bin", 5000, hashOf("4c"));

        whenTheLinkIsAddedAgain(5000, Optional.of(aich("A")), Ipv4.of(192, 0, 2, 2));

        thenTheResultingDownloadHasSources(1);
        thenTheFileTookTheAichHash(aich("A"));
    }

    @Test
    void shouldGiveNoSourcesWhenALinkWithAnotherAichHashIsAddedAgain() throws Exception {
        givenTheDownloadManager();
        givenAPausedDownload("same.bin", 5000, hashOf("4c"));
        givenTheLinkAddedAgain(5000, Optional.of(aich("A")), Ipv4.of(192, 0, 2, 2));

        whenTheLinkIsAddedAgain(5000, Optional.of(aich("B")), Ipv4.of(192, 0, 2, 3));

        thenTheResultingDownloadHasSources(1, "another AICH hash");
    }

    @Test
    void shouldNotPreviewWithoutTheStartOfTheFile() throws Exception {
        givenTheDownloadManager();
        givenAPausedDownload("film.avi", 100_000, hashOf("4d"));
        givenTheFileWritten(16 * 1024, 100_000);

        whenAFileToPreviewIsAskedFor();

        thenThereIsNoFileToPreview("all but the start");
    }

    @Test
    void shouldPreviewWithTheStartOfTheFile() throws Exception {
        givenTheDownloadManager();
        givenAPausedDownload("film.avi", 100_000, hashOf("4d"));
        givenTheFileWritten(16 * 1024, 100_000);
        givenTheFileWritten(0, 16 * 1024);

        whenAFileToPreviewIsAskedFor();

        thenThereIsAFileToPreview();
    }

    static IntStream addressesNoClientCanHave() {
        return IntStream.of(0, Ipv4.of(0, 0, 0, 1), Ipv4.of(239, 255, 255, 250), -1);
    }

    private void givenTheDownloadManager() throws Exception {
        manager();
    }

    private void givenTheDownloadManagerWithAServerThatAnswers() throws Exception {
        this.server = new HighIdServer(true);
        manager(this.server);
    }

    private void givenTheDownloadManagerRestarted() throws Exception {
        manager();
    }

    private void givenTheCategories(Category... list) {
        this.categories = List.of(list);
    }

    private void givenOnlyTheFirstCategoryLeft() {
        this.categories = List.of(this.categories.getFirst());
    }

    private void givenAListenerOfAddedDownloads() {
        List<DownloadInfo> events = this.added;
        this.listener = new Quiet() {
            @Override
            public void added(DownloadInfo download) {
                events.add(download);
            }
        };
    }

    private void givenADownload(String name, Hash16 hash) throws Exception {
        this.hash = hash;
        onLoop(() -> this.manager.add(Ed2kLink.FileLink.of(name, 5000, hash)));
    }

    private void givenDownloadsDeletedAfterIdleDays(int days) {
        this.settings = new DownloadManager.Settings(
                ObfuscationMode.SUPPORTED, 100, PORT, 0, true, false, "", 0, false, "", days);
    }

    private void givenTheLinksKept(boolean kept) {
        List<String> links = this.keptLinks;
        this.listener = new Quiet() {
            @Override
            public boolean keepLink(Ed2kLink.FileLink link) {
                if (kept) {
                    links.add(link.toUri());
                }
                return kept;
            }
        };
    }

    private void givenADownloadIdleForSeconds(String name, Hash16 hash, long seconds) throws Exception {
        manager();
        givenADownload(name, hash);
        onLoop(() -> {
            this.manager.find(hash).orElseThrow().file().setIdleSeconds(seconds);
            return null;
        });
    }

    private void givenAPausedDownloadIdleForSeconds(String name, Hash16 hash, long seconds) throws Exception {
        manager();
        givenAPausedDownload(name, 5000, hash);
        onLoop(() -> {
            this.manager.find(hash).orElseThrow().file().setIdleSeconds(seconds);
            return null;
        });
    }

    // A few ticks, of a second each.
    private void whenTheManagerHasRunAWhile() throws Exception {
        Thread.sleep(2500);
    }

    private void thenTheDownloadIsGone(boolean gone) throws Exception {
        assertThat(onLoop(() -> this.manager.download(this.hash)))
                .as("download")
                .matches(d -> d.isEmpty() == gone);
    }

    private void thenTheKeptLinksAre(String... links) {
        assertThat(this.keptLinks).containsExactly(links);
    }

    private void thenItsIdleTimeIsBelow(long seconds) throws Exception {
        assertThat(onLoop(
                        () -> this.manager.find(this.hash).orElseThrow().file().idleSeconds()))
                .isLessThan(seconds);
    }

    private void givenAPausedDownload(String name, long size, Hash16 hash) throws Exception {
        this.hash = hash;
        onLoop(() -> {
            this.manager.add(Ed2kLink.FileLink.of(name, size, hash));
            return this.manager.pause(hash);
        });
    }

    private void givenADownloadThatKadSearches(String name, Hash16 hash) throws Exception {
        this.hash = hash;
        onLoop(() -> this.manager.add(Ed2kLink.FileLink.of(name, 5000, hash)));
        this.kadFound = this.kadSearch.get(30, TimeUnit.SECONDS);
    }

    private void givenTheDownloadPaused() throws Exception {
        onLoop(() -> this.manager.pause(this.hash));
    }

    private void givenTheLinkAddedPaused(String uri) throws Exception {
        whenTheLinkIsAddedPaused(uri);
    }

    private void givenTheLinkAddedWithThePriority(String uri, DownloadPriority priority) throws Exception {
        Ed2kLink.FileLink link = (Ed2kLink.FileLink) Ed2kLink.parse(uri);
        this.hash = link.hash();
        onLoop(() -> {
            this.manager.add(link);
            return this.manager.setPriority(link.hash(), priority);
        });
    }

    private void givenTheDownloadStopped() throws Exception {
        onLoop(() -> this.manager.stop(this.hash));
    }

    private void givenASourceAddedByHand(InetSocketAddress address) throws Exception {
        whenASourceIsAddedByHand(address);
    }

    private void givenTheLinkAddedAgain(long size, Optional<Hash20> aich, int source) throws Exception {
        whenTheLinkIsAddedAgain(size, aich, source);
    }

    private void givenAPausedFilmAStoppedUrgentFileAndAPausedLaterFile(Hash16 film, Hash16 urgent, Hash16 later)
            throws Exception {
        onLoop(() -> {
            this.manager.add(Ed2kLink.FileLink.of("film.mkv", 5000, film));
            this.manager.add(Ed2kLink.FileLink.of("urgent.txt", 5000, urgent));
            this.manager.add(Ed2kLink.FileLink.of("later.txt", 5000, later));
            this.manager.setPriority(film, DownloadPriority.LOW);
            this.manager.setPriority(urgent, DownloadPriority.HIGH);
            this.manager.setPriority(later, DownloadPriority.NORMAL);
            this.manager.pause(film);
            this.manager.stop(urgent);
            this.manager.pause(later);
            return null;
        });
    }

    private void givenTheNextDownloadResumed(int category, NextDownload mode) throws Exception {
        assertThat(onLoop(() -> this.manager.resumeNext(category, mode))).isPresent();
    }

    private void givenTheDownloadMovedToTheCategory(int category) throws Exception {
        assertThat(onLoop(() -> this.manager.setCategory(this.hash, category))).isTrue();
    }

    private void givenARunningDownloadTheServerWasAskedFor(String name, Hash16 hash) throws Exception {
        givenADownload(name, hash);
        for (int i = 0; i < 100 && this.server.asked.isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(this.server.asked).hasSize(1);
    }

    private void givenTwoPausedDownloadsAfterACancelledOne(Hash16 gone, Hash16 first, Hash16 second) throws Exception {
        onLoop(() -> {
            this.manager.add(Ed2kLink.FileLink.of("gone.bin", 5000, gone));
            this.manager.add(Ed2kLink.FileLink.of("first.bin", 5000, first));
            this.manager.cancel(gone);
            // Takes the part file number the cancelled download left.
            this.manager.add(Ed2kLink.FileLink.of("second.bin", 5000, second));
            this.manager.pause(first);
            this.manager.pause(second);
            this.manager.close();
            return null;
        });
    }

    private void givenTheFileWritten(long start, long end) throws Exception {
        onLoop(() -> {
            this.manager.find(this.hash).orElseThrow().file().written(start, end);
            return null;
        });
    }

    private void whenKadFinds(InetSocketAddress... addresses) throws Exception {
        this.info = onLoop(() -> {
            this.kadFound.accept(direct(addresses));
            return this.manager.download(this.hash).orElseThrow();
        });
    }

    private void whenTheLinkIsAddedPaused(String uri) throws Exception {
        Ed2kLink.FileLink link = (Ed2kLink.FileLink) Ed2kLink.parse(uri);
        this.hash = link.hash();
        this.info = onLoop(() -> {
            DownloadInfo added = this.manager.add(link);
            this.manager.pause(link.hash());
            return added;
        });
    }

    private void whenADownloadIsAdded(String name, Hash16 hash) throws Exception {
        this.hash = hash;
        this.info = onLoop(() -> this.manager.add(Ed2kLink.FileLink.of(name, 5000, hash)));
    }

    private void whenADownloadIsAddedPaused(String name, Hash16 hash) throws Exception {
        this.hash = hash;
        onLoop(() -> {
            this.manager.add(Ed2kLink.FileLink.of(name, 5000, hash));
            return this.manager.pause(hash);
        });
    }

    private void whenASourceIsAddedByHand(InetSocketAddress address) throws Exception {
        this.answer = onLoop(() -> this.manager.addSourceByHand(this.hash, address));
    }

    private void whenTheDownloadIsStopped() throws Exception {
        this.info = onLoop(() -> {
            this.manager.stop(this.hash);
            return this.manager.download(this.hash).orElseThrow();
        });
    }

    private void whenTheDownloadIsResumed() throws Exception {
        this.info = onLoop(() -> {
            this.manager.resume(this.hash);
            DownloadInfo resumed = this.manager.download(this.hash).orElseThrow();
            // Paused again at once, so that it asks no source.
            this.manager.pause(this.hash);
            return resumed;
        });
    }

    private void whenADownloadWeDoNotHaveIsStopped(Hash16 unknown) throws Exception {
        this.answer = onLoop(() -> this.manager.stop(unknown));
    }

    private void whenTheNextDownloadIsResumed(int category, NextDownload mode) throws Exception {
        this.next = onLoop(() -> this.manager.resumeNext(category, mode));
    }

    private void whenTheDownloadIsMovedToTheCategory(int category) throws Exception {
        this.answer = onLoop(() -> this.manager.setCategory(this.hash, category));
    }

    private void givenTheNamesOfNewDownloadsCleanedUpOf(String words) {
        this.settings = new DownloadManager.Settings(
                ObfuscationMode.SUPPORTED, 100, PORT, 0, true, false, "", 0, true, words, 0);
    }

    private void givenTheDownloadRenamed(String name) throws Exception {
        whenTheDownloadIsRenamed(name);
        thenItIsAccepted();
    }

    private void whenTheDownloadIsRenamed(String name) throws Exception {
        try {
            this.answer = onLoop(() -> this.manager.rename(this.hash, name));
        } catch (ExecutionException e) {
            this.exception = e.getCause();
        }
    }

    private void whenADownloadWeDoNotHaveIsRenamed(Hash16 unknown) throws Exception {
        this.answer = onLoop(() -> this.manager.rename(unknown, "any.iso"));
    }

    // Saved as it closes, read back as it opens.
    private void whenTheDownloadManagerIsClosedAndOpenedAgain() throws Exception {
        onLoop(() -> {
            this.manager.close();
            return null;
        });
        manager();
    }

    private void thenTheDownloadIsNamed(Hash16 download, String name) throws Exception {
        assertThat(onLoop(() -> this.manager.download(download).orElseThrow().name()))
                .isEqualTo(name);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void whenTheCategoriesChange() throws Exception {
        onLoop(() -> {
            this.manager.categoriesChanged();
            return null;
        });
    }

    private void whenALowAndAHighPriorityDownloadAreAddedTogether(Hash16 low, Hash16 high) throws Exception {
        onLoop(() -> {
            this.manager.add(Ed2kLink.FileLink.of("low.bin", 5000, low));
            this.manager.setPriority(low, DownloadPriority.LOW);
            this.manager.add(Ed2kLink.FileLink.of("high.bin", 5000, high));
            this.manager.setPriority(high, DownloadPriority.HIGH);
            return null;
        });
    }

    private void whenTheRunningDownloadIsResumed() throws Exception {
        this.answer = onLoop(() -> this.manager.resume(this.hash));
    }

    private void whenTheLinkIsAddedAgain(long size, Optional<Hash20> aich, int source) throws Exception {
        this.info = onLoop(() -> this.manager.add(link(this.hash, size, aich, source)));
    }

    private void whenAFileToPreviewIsAskedFor() throws Exception {
        this.preview = onLoop(() -> this.manager.dataFile(this.hash));
    }

    private void thenTheResultingDownloadHasSources(int expected) {
        assertThat(this.info.sources()).isEqualTo(expected);
    }

    private void thenTheResultingDownloadHasSources(int expected, String description) {
        assertThat(this.info.sources()).as(description).isEqualTo(expected);
    }

    private void thenTheResultingDownloadIs(DownloadInfo.Status expected) {
        assertThat(this.info.status()).isEqualTo(expected);
    }

    private void thenTheResultingDownloadHasThePriority(DownloadPriority expected) {
        assertThat(this.info.priority()).isEqualTo(expected);
    }

    private void thenTheResultingDownloadHasThePriority(DownloadPriority expected, String description) {
        assertThat(this.info.priority()).as(description).isEqualTo(expected);
    }

    private void thenTheResultingDownloadIsInTheCategory(int expected) {
        assertThat(this.info.category()).isEqualTo(expected);
    }

    private void thenTheDownloadNowHasSources(int expected) throws Exception {
        assertThat(onLoop(() -> this.manager.download(this.hash).orElseThrow().sources()))
                .isEqualTo(expected);
    }

    private void thenTheDownloadIsNowInTheCategory(Hash16 download, int expected) throws Exception {
        assertThat(onLoop(() -> this.manager.download(download).orElseThrow().category()))
                .isEqualTo(expected);
    }

    private void thenTheDownloadIsNowInTheCategory(Hash16 download, int expected, String description) throws Exception {
        assertThat(onLoop(() -> this.manager.download(download).orElseThrow().category()))
                .as(description)
                .isEqualTo(expected);
    }

    private void thenItIsAccepted() {
        assertThat(this.answer).isTrue();
    }

    private void thenItIsAccepted(String description) {
        assertThat(this.answer).as(description).isTrue();
    }

    private void thenItIsRefused() {
        assertThat(this.answer).isFalse();
    }

    private void thenItIsRefused(String description) {
        assertThat(this.answer).as(description).isFalse();
    }

    private void thenNothingIsResumed() {
        assertThat(this.next).isEmpty();
    }

    private void thenNothingIsResumed(String description) {
        assertThat(this.next).as(description).isEmpty();
    }

    private void thenTheResumedDownloadIs(Hash16 expected) {
        assertThat(this.next).map(DownloadInfo::hash).contains(expected);
    }

    private void thenTheResumedDownloadIs(Hash16 expected, String description) {
        assertThat(this.next).map(DownloadInfo::hash).as(description).contains(expected);
    }

    private void thenTheResumedDownloadIs(DownloadInfo.Status expected) {
        assertThat(this.next).map(DownloadInfo::status).contains(expected);
    }

    private void thenTheServerIsAskedFirstFor(Hash16... files) throws Exception {
        for (int i = 0; i < 100 && this.server.asked.size() < files.length; i++) {
            Thread.sleep(100);
        }
        assertThat(this.server.asked).startsWith(files);
    }

    private void thenTheServerIsNotAskedAgainBeforeItsTime() throws Exception {
        Thread.sleep(2500);
        assertThat(this.server.asked).as("not asked again before its time").hasSize(1);
    }

    private void thenOneAddedEventTells(int category, DownloadPriority priority) {
        assertThat(this.added).singleElement().satisfies(info -> {
            assertThat(info.category()).isEqualTo(category);
            assertThat(info.priority()).isEqualTo(priority);
        });
    }

    private void thenTheFileTookTheAichHash(Hash20 expected) throws Exception {
        assertThat(onLoop(
                        () -> this.manager.find(this.hash).orElseThrow().file().aichHash()))
                .as("the link's AICH hash is taken")
                .contains(expected);
    }

    private void thenThereIsNoFileToPreview(String description) {
        assertThat(this.preview).as(description).isEmpty();
    }

    private void thenThereIsAFileToPreview() {
        assertThat(this.preview).isPresent();
    }

    private static Hash16 hashOf(String octet) {
        return Hash16.parseHex(octet.repeat(16));
    }

    private static Hash20 aich(String letter) {
        return Hash20.parseBase32(letter.repeat(32));
    }

    private static Category category(int id, String name, int color, DownloadPriority priority, String patterns) {
        return new Category(id, name, Optional.empty(), "", color, priority, patterns, false);
    }

    private static Ed2kLink.FileLink link(Hash16 hash, long size, Optional<Hash20> aich, int source) {
        return new Ed2kLink.FileLink(
                "same.bin",
                size,
                hash,
                aich,
                List.of(),
                List.of(new Ed2kLink.Source(Ipv4.format(source), PORT)),
                List.of());
    }

    private DownloadManager manager() throws Exception {
        return manager(new HighIdServer(false));
    }

    private DownloadManager manager(HighIdServer server) throws Exception {
        SecureRandom random = new SecureRandom();
        Identities identities = new Identities(
                IdentityHandshake.Identity.of(Identities.loadOrCreateKey(this.dir, random)),
                hash -> Optional.empty(),
                (hash, key) -> {},
                Optional::empty,
                random);
        Hello hello = Hello.of(Hash16.parseHex("ab".repeat(16)), Ipv4.toEd2k(OUR_IP), PORT, "test", 0, 0, 0, 0, 0, 0);
        DownloadManager.KadSources kad = (file, size, sources) -> this.kadSearch.complete(sources);
        this.manager = onLoop(() -> DownloadManager.open(
                this.loop,
                this.dir.resolve("temp"),
                () -> this.dir.resolve("incoming"),
                () -> hello,
                () -> this.settings,
                server,
                ip -> ip == BLOCKED_IP,
                random,
                Ticker.systemTicker(),
                new PeerLinks(this.loop, identities, link -> Optional.empty()),
                (hash, bytes) -> {},
                () -> Optional.of(kad),
                () -> this.categories,
                this.listener));
        return this.manager;
    }

    private static List<DownloadManager.KadSource> direct(InetSocketAddress... addresses) {
        return Stream.of(addresses).map(DownloadManager.KadSource::direct).toList();
    }

    private static InetSocketAddress address(int ip, int port) {
        return new InetSocketAddress(Ipv4.format(ip), port);
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(30, TimeUnit.SECONDS);
    }

    // Answers source requests as asked to, and keeps the files they were for.
    private static final class HighIdServer implements DownloadManager.ServerLink {
        private final boolean answers;
        private final List<Hash16> asked = new CopyOnWriteArrayList<>();

        HighIdServer(boolean answers) {
            this.answers = answers;
        }

        @Override
        public boolean askSources(Hash16 file, long size) {
            if (this.answers) {
                this.asked.add(file);
            }
            return this.answers;
        }

        @Override
        public long clientId() {
            return Ipv4.toEd2k(OUR_IP);
        }

        @Override
        public int serverIp() {
            return 0;
        }

        @Override
        public boolean requestCallback(long lowId) {
            return false;
        }
    }

    private static class Quiet implements DownloadManager.Listener {
        @Override
        public void added(DownloadInfo download) {}

        @Override
        public void progress(DownloadInfo download) {}

        @Override
        public void completed(DownloadInfo download, Path file, PartFile part) {}

        @Override
        public void failed(DownloadInfo download, String reason) {}

        @Override
        public void removed(Hash16 hash) {}
    }
}
