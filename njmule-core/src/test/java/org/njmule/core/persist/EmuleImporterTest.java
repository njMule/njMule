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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Category;
import org.njmule.core.api.Hash16;
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
import org.njmule.core.persist.format.PreferencesDat;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Bytes;

class EmuleImporterTest {

    @TempDir
    Path dir;

    private SyntheticEmuleProfile emule;
    private Path ours;
    private final Recorder target = new Recorder();
    private Path missingProfile;
    private boolean emuleRunning;
    private SortedMap<String, String> emuleBefore;
    private SortedMap<String, String> oursBefore;
    private ImportReport report;
    private String preferencesIni;
    private Preferences preferences;
    private int colour;
    private int filesApplied;
    private boolean identityApplied;

    /** Records what the importer asks for. */
    private final class Recorder implements EmuleImporter.Target {
        Preferences preferences = Preferences.defaults();
        final List<Path> servers = new ArrayList<>();
        int contacts;
        int known;
        final List<Path> shared = new ArrayList<>();
        final List<Path> refused = new ArrayList<>();
        final List<String> adopted = new ArrayList<>();
        final List<Path> adoptedFrom = new ArrayList<>();
        final Map<String, Integer> adoptedCategories = new HashMap<>();
        final List<Category> categories = new ArrayList<>();
        boolean ownCategories;

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
            if (this.ownCategories) {
                return 0;
            }
            this.categories.addAll(taken);
            return taken.size();
        }

        @Override
        public String adoptDownload(Path met, Path data, int category) {
            assertThat(data).exists();
            this.adopted.add(String.valueOf(met.getFileName()));
            this.adoptedFrom.add(met);
            this.adoptedFrom.add(data);
            this.adoptedCategories.put(String.valueOf(met.getFileName()), category);
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
    void shouldReportEverythingAndChangeNothingOnADryRun() throws IOException {
        givenAFingerprintOfTheEmuleProfile();

        whenTheProfileIsImported(new ImportOptions(true, true, true));

        thenTheReportIsADryRun();
        thenTheOutcomeOfIs("preferences.ini", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("server.met", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("nodes.dat", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("known.met", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("shareddir.dat", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("sharedfiles.dat", Outcome.SKIPPED);
        thenTheOutcomeOfIs("ipfilter.dat", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("cancelled.met", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("emfriends.met", Outcome.MISSING);
        thenTheOutcomeOfIs("key_index.dat", Outcome.SKIPPED);
        thenTheOutcomeOfIs("preferences.dat", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("cryptkey.dat", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("clients.met", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("preferencesKad.dat", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("001.part.met", Outcome.WOULD_IMPORT);
        thenTheOutcomeOfIs("002.part.met", Outcome.WOULD_IMPORT);
        thenTheDetailOfIs("server.met", "2 servers");
        thenTheDetailOfIs("nodes.dat", "3 Kad contacts");
        thenTheTargetIsUntouched();
        thenNothingIsWrittenIntoOurDirectory();
        thenTheEmuleProfileIsUnchanged();
    }

    @Test
    void shouldTakeEverythingAndLeaveEmuleAlone() throws IOException {
        givenAFingerprintOfTheEmuleProfile();

        whenTheProfileIsImported(new ImportOptions(false, true, true));

        thenTheOutcomeOfIs("preferences.ini", Outcome.IMPORTED);
        thenTheOutcomeOfIs("server.met", Outcome.IMPORTED);
        thenTheOutcomeOfIs("preferences.dat", Outcome.IMPORTED);
        thenTheOutcomeOfIs("001.part.met", Outcome.IMPORTED);
        thenTheNickIs(SyntheticEmuleProfile.NICK);
        thenTheSpeedLimitsAre(50, 0);
        thenKadIsOff();
        thenOnlyTheDownloadedFilesAreRemembered();
        thenSearchesAreNotStored();
        thenDownloadsAreAddedPaused();
        thenTheNextDownloadIs(NextDownload.SAME_CATEGORY_FIRST);
        thenLanAddressesAreNotFiltered();
        thenOnlyFriendsMaySendMessages();
        thenTheMessageFilterIs("buy now|cheap");
        thenTheNamesOfNewDownloadsAreCleanedUpOf("dvdrip|sponsored");
        thenTheAdvancedSpamFilterIsOnAsItIsNotInTheProfile();
        thenTheConnectionLimitsAre(35, 9000);
        thenTheTcpPortIs(Preferences.DEFAULT_TCP_PORT);
        thenTheServersAreTakenFromTheEmuleFile("server.met");
        thenTheKadContactsTakenAre(3);
        thenTheKnownFilesTakenAre(1);
        thenTheDirectoriesSharedAre(this.emule.incoming, this.emule.shared);
        thenTheDownloadsAdoptedAre("001.part.met", "002.part.met");
        thenTheFileWaitsForTheNextStart("cancelled.met");
        thenOurFileExists("ipfilter.dat");
        thenThePendingIdentityHoldsTheEmuleFile("preferences.dat");
        thenThePendingIdentityHolds("cryptkey.dat", "clients.met", "preferencesKad.dat");
        thenThePendingKadUdpKeyIs(SyntheticEmuleProfile.KAD_UDP_KEY);
        thenTheEmuleProfileIsUnchanged();
    }

    @Test
    void shouldKeepTheNumbersAndPatternsOfTheCategoriesAndGiveThemAColourOfOurs() throws IOException {
        givenTheEmuleFile(
                "Category.ini",
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
                        "Incoming=" + this.emule.shared,
                        "Color=16711680",
                        "AutoCat=ubuntu|debian"));

        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheOutcomeOfIs("Category.ini", Outcome.IMPORTED);
        thenTheCategoryIdsTakenAre(1, 3);
        thenTheCategoryIsNamed(0, "Films");
        thenTheCategoryLeavesTheIncomingDirectoryOfEmuleOut(0);
        thenTheCategoryHasTheColour(0, 1, "red");
        thenTheCategoryMatches(0, "Big Buck Bunny.MKV");
        thenTheCategoryHasTheIncomingDirectory(1, this.emule.shared);
        thenTheCategoryHasTheColour(1, 6, "blue");
    }

    @ParameterizedTest
    @CsvSource({"0x808080, 0, grey", "0x00A5FF, 2, orange"})
    void shouldGiveAnEmuleColourOneOfOurs(String rgb, int expected, String name) {
        whenTheEmuleColourIsMapped(rgb);

        thenTheColourIs(expected, name);
    }

    @Test
    void shouldShortenACategoryWeCannotFullyRepresentInsteadOfLeavingItOut() throws IOException {
        givenTheEmuleFile(
                "Category.ini",
                String.join(
                        "\r\n",
                        "[Cat#1]",
                        "Title=" + "t".repeat(Category.MAX_NAME + 10),
                        "Comment=" + "c".repeat(Category.MAX_COMMENT + 10),
                        "AutoCat=" + "p".repeat(Category.MAX_PATTERNS + 10),
                        "[Cat#2]",
                        "Title=Broken",
                        "AutoCat=(unclosed",
                        "AutoCatAsRegularExpression=1",
                        ""));

        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheCategoryIdsTakenAre(1, 2);
        thenTheNameCommentAndPatternsOfTheCategoryHaveTheLengths(
                0, Category.MAX_NAME, Category.MAX_COMMENT, Category.MAX_PATTERNS);
        thenTheCategoryHasNoPatterns(1);
        thenTheDetailOfContains("Category.ini", "2 categories", "Broken");
    }

    @Test
    void shouldKeepOnlyTheCategoriesThatCameWithTheDownloads() throws IOException {
        givenTheDownloadIsInCategory(1, 1);
        givenTheDownloadIsInCategory(2, 2);
        givenTheEmuleFile("Category.ini", "[Cat#1]\r\nTitle=Films\r\n");

        whenTheProfileIsImported(new ImportOptions(false, false, true));

        thenTheDownloadIsAdoptedInCategory("001.part.met", 1);
        thenTheDownloadIsAdoptedInCategory("002.part.met", Category.NONE, "eMule's category 2 was not taken");
    }

    @Test
    void shouldAdoptTheDownloadsWithoutCategoryWhenTheTargetKeepsItsOwnCategories() throws IOException {
        givenTheDownloadIsInCategory(1, 1);
        givenTheDownloadIsInCategory(2, 2);
        givenTheEmuleFile("Category.ini", "[Cat#1]\r\nTitle=Films\r\n");
        givenAPreviousImport(new ImportOptions(false, false, true));
        givenTheTargetKeepsItsOwnCategories();

        whenTheProfileIsImported(new ImportOptions(false, false, true));

        thenTheDownloadIsAdoptedInCategory("001.part.met", Category.NONE);
    }

    @Test
    void shouldCountOnlyTheCanonicalSectionOfACategory() throws IOException {
        givenTheEmuleFile(
                "Category.ini",
                String.join(
                        "\r\n", "[Cat#1]", "Title=Films", "[Cat#01]", "Title=Copy", "[Cat# 2]", "Title=Spaced", ""));

        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheOutcomeOfIs("Category.ini", Outcome.IMPORTED);
        thenTheCategoryNamesTakenAre("Films");
    }

    @Test
    void shouldKeepOurOwnFiles() throws IOException {
        givenOurFile("ipfilter.dat", "");
        givenOurFile("cancelled.met", "");

        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheOutcomeOfIs("ipfilter.dat", Outcome.SKIPPED);
        thenTheOutcomeOfIs("cancelled.met", Outcome.SKIPPED);
        thenOurFileIsEmpty("ipfilter.dat");
    }

    @Test
    void shouldPutTheFilesTheEngineRewritesInTheirPlaceAtTheNextStart() throws IOException {
        givenTheEmuleFile("staticservers.dat", "10.1.2.3:4661,1,Static\r\n");
        givenAPreviousImport(new ImportOptions(false, false, false));
        givenTheImportPutOffTheFileToTheNextStart("cancelled.met");
        // What the engine writes before it stops.
        givenOurFile("staticservers.dat", "");

        whenThePendingFilesAreApplied();

        thenTheFilesAppliedAre(2);
        thenOurFileIsTheEmuleOne("cancelled.met");
        thenOurFileIsTheEmuleOne("staticservers.dat");
        thenNoFileWaitsForTheNextStart();
    }

    @Test
    void shouldApplyThePendingFilesOnlyOnce() throws IOException {
        givenTheEmuleFile("staticservers.dat", "10.1.2.3:4661,1,Static\r\n");
        givenAPreviousImport(new ImportOptions(false, false, false));
        givenOurFile("staticservers.dat", "");
        givenThePendingFilesWereApplied();

        whenThePendingFilesAreApplied();

        thenTheFilesAppliedAre(0);
    }

    @Test
    void shouldReplaceTheFilesWaitingAndKeepOurOwnOnASecondImport() throws IOException {
        givenTheEmuleFile("staticservers.dat", "10.1.2.3:4661,1,Static\r\n");
        givenOurFile("staticservers.dat", "");
        givenAPreviousImport(new ImportOptions(false, false, false));
        // Written by the engine after the first import, so not of its own before it.
        givenOurFile("cancelled.met", "");

        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheOutcomeOfIs("cancelled.met", Outcome.IMPORTED);
        thenTheOutcomeOfIs("staticservers.dat", Outcome.SKIPPED);
        thenTheFileDoesNotWaitForTheNextStart("staticservers.dat");
    }

    @Test
    void shouldTakeTheIdentityAndTheDownloadsOnlyWhenAsked() throws IOException {
        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheOutcomeOfIs("preferences.dat", Outcome.SKIPPED);
        thenTheOutcomeOfIs("cryptkey.dat", Outcome.SKIPPED);
        thenTheOutcomeOfIs("Temp", Outcome.SKIPPED);
        thenThereIsNoOutcomeFor("001.part.met");
        thenNoIdentityWaits();
        thenNoDownloadIsAdopted();
    }

    @Test
    void shouldLeaveARunningEmuleItsIdentityAndActiveDownloads() throws IOException {
        givenEmuleIsRunning();

        whenTheProfileIsImported(new ImportOptions(false, true, true));

        thenTheOutcomeOfIs("preferences.dat", Outcome.SKIPPED);
        thenTheOutcomeOfIs("001.part.met", Outcome.IMPORTED);
        thenTheOutcomeOfIs("002.part.met", Outcome.SKIPPED);
        thenTheDetailOfContains("002.part.met", "pause it in eMule");
        thenNoIdentityWaits();
        thenTheDownloadsAdoptedAre("001.part.met");
    }

    @Test
    void shouldTakeTheUserHashOnlyWithItsKey() throws IOException {
        givenTheEmuleFile("cryptkey.dat", "not base64 at all!");

        whenTheProfileIsImported(new ImportOptions(false, true, false));

        thenTheOutcomeOfIs("cryptkey.dat", Outcome.FAILED);
        thenADetailContains("the user hash and the key are taken only");
        thenNoIdentityWaits();
    }

    @Test
    void shouldReportBrokenFilesAndGoOnWithTheRest() throws IOException {
        givenTheEmuleFile("server.met", new byte[] {(byte) 0xE0, 1});
        givenTheEmuleFileIsDeleted("preferences.ini");

        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheOutcomeOfIs("server.met", Outcome.FAILED);
        thenTheOutcomeOfIs("preferences.ini", Outcome.MISSING);
        thenTheOutcomeOfIs("nodes.dat", Outcome.IMPORTED);
        thenNoServersAreTaken();
    }

    @Test
    void shouldKeepThePendingIdentityWaitingWhileEmuleRuns() throws IOException {
        givenAPreviousImport(new ImportOptions(false, true, false));
        givenEmuleIsRunning();

        whenThePendingIdentityIsApplied();

        thenThePendingIdentityIsNotApplied();
        thenThePendingIdentityStillWaits();
    }

    @Test
    void shouldApplyThePendingIdentityOnceEmuleIsClosed() throws IOException {
        givenAPreviousImport(new ImportOptions(false, true, false));
        givenEmuleIsRunning();
        givenAnAttemptToApplyThePendingIdentity();
        givenEmuleIsClosed();

        whenThePendingIdentityIsApplied();

        thenThePendingIdentityIsApplied();
        thenNoIdentityWaits();
        thenOurFileIsTheEmuleOne("preferences.dat");
        thenOurFileExists("kadudpkey.dat");
    }

    @Test
    void shouldApplyThePendingIdentityOnlyOnce() throws IOException {
        givenAPreviousImport(new ImportOptions(false, true, false));
        givenAnAttemptToApplyThePendingIdentity();

        whenThePendingIdentityIsApplied();

        thenThePendingIdentityIsNotApplied();
    }

    @Test
    void shouldStillTakeAnIdentityImportedTwiceBeforeARestart() throws IOException {
        givenAPreviousImport(new ImportOptions(false, true, false));
        givenAPreviousImport(new ImportOptions(false, true, false));

        whenThePendingIdentityIsApplied();

        thenThePendingIdentityIsApplied();
        thenNoIdentityWaits();
        thenOurFileIsTheEmuleOne("preferences.dat");
    }

    @Test
    void shouldNotLetLeftoversInThePendingIdentityStopTheStart() throws IOException {
        givenAPreviousImport(new ImportOptions(false, true, false));
        givenALeftoverInThePendingIdentity("preferences.dat.bak", "older");
        givenALeftoverInThePendingIdentity("cryptkey.dat.tmp", "interrupted");
        // A folder among the leftovers must not stop the removal of the files listed after it.
        givenAFolderLeftInThePendingIdentity("a folder");

        whenThePendingIdentityIsApplied();

        thenThePendingIdentityIsApplied();
        thenNoIdentityWaits();
        thenOurFileIsTheEmuleOne("cryptkey.dat");
    }

    @Test
    void shouldReplaceTheWholePendingIdentityWithThatOfAnotherProfile() throws IOException {
        givenAPreviousImport(new ImportOptions(false, true, false));
        givenAnotherEmuleProfile();
        givenTheEmuleFileIsDeleted("clients.met");
        givenTheEmuleFileIsDeleted("preferencesKad.dat");
        givenTheEmulePreferencesWithTextReplaced("KadUDPKey=", "Unused=");

        whenTheProfileIsImported(new ImportOptions(false, true, false));

        thenThePendingIdentityHoldsTheEmuleFile("preferences.dat");
        thenThePendingIdentityLacks("clients.met", "preferencesKad.dat", "kadudpkey.dat");
    }

    @Test
    void shouldNotApplyAUserHashWithoutItsKey() throws IOException {
        givenAPreviousImport(new ImportOptions(false, true, false));
        givenThePendingIdentityLosesItsFile("cryptkey.dat");

        whenThePendingIdentityIsApplied();

        thenThePendingIdentityIsNotApplied();
        thenOurFileDoesNotExist("preferences.dat");
        thenNoIdentityWaits();
    }

    @Test
    void shouldKeepThePendingIdentityOnADryRun() throws IOException {
        givenAPreviousImport(new ImportOptions(false, true, false));
        givenTheEmuleFileIsDeleted("cryptkey.dat");

        whenTheProfileIsImported(new ImportOptions(true, true, false));

        thenThePendingIdentityHolds("preferences.dat", "cryptkey.dat");
    }

    @Test
    void shouldTakeOnlyUsableValuesOfThePreferences() {
        givenAnEmulePreferencesIni(String.join(
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
                "CryptLayerRequired=1"));

        whenThePreferencesAreMapped();

        thenTheNickIs(Preferences.defaults().nick());
        thenTheSpeedLimitsAre(0, 0);
        thenTheMaximumConnectionsAre(10_000);
        thenTheDownloadCapacityIs(2048);
        thenTheUploadCapacityIs(Preferences.DEFAULT_UPLOAD_CAPACITY, "an unknown capacity keeps ours");
        thenTheMinimumFreeSpaceIs(1024, "eMule keeps bytes");
        thenTheObfuscationIs(ObfuscationMode.REQUIRED);
    }

    @Test
    void shouldUseTheFreeSpaceMinimumOfEmuleOnlyWhileItsCheckIsOn() {
        givenAnEmulePreferencesIni("[eMule]\nCheckDiskspace=0\nMinFreeDiskSpace=1");

        whenThePreferencesAreMapped();

        thenTheMinimumFreeSpaceIs(
                Preferences.DEFAULT_MIN_FREE_SPACE, "eMule's minimum is used only while its check is on");
    }

    @Test
    void shouldTurnObfuscationOffWhenEmuleDoesNotSupportIt() {
        givenAnEmulePreferencesIni("[eMule]\nCryptLayerSupported=0");

        whenThePreferencesAreMapped();

        thenTheObfuscationIs(ObfuscationMode.DISABLED);
    }

    @Test
    void shouldKeepOurObfuscationWithoutPreferences() {
        givenAnEmulePreferencesIni("");

        whenThePreferencesAreMapped();

        thenTheObfuscationIs(Preferences.defaults().obfuscation());
    }

    @Test
    void shouldKeepTheSizeOfAFreeSpaceMinimumOfTwoGigabytesOrMore() {
        // eMule writes the 32-bit byte count as a signed number: 3 GiB comes out negative.
        givenAnEmulePreferencesIni("[eMule]\nCheckDiskspace=1\nMinFreeDiskSpace=-1073741824\n");

        whenThePreferencesAreMapped();

        thenTheMinimumFreeSpaceIs(3072);
    }

    @ParameterizedTest
    @CsvSource({
        "-1, ANY",
        "0, NONE",
        "1, ANY",
        "2, SAME_CATEGORY_FIRST",
        "3, SAME_CATEGORY",
        "4, SAME_CATEGORY_FIRST",
        "9, SAME_CATEGORY_FIRST"
    })
    void shouldGiveEveryStartNextFileValueItsMeaning(int value, NextDownload next) {
        givenAnEmulePreferencesIni("[eMule]\nStartNextFile=" + value);

        whenThePreferencesAreMapped();

        thenTheNextDownloadIs(next);
    }

    @Test
    void shouldTakeTheSocksProxyEmuleUses() {
        givenAnEmulePreferencesIni("""
                [eMule]
                [Proxy]
                ProxyEnableProxy=1
                ProxyType=3
                ProxyName=proxy.lan
                ProxyPort=9050
                ProxyEnablePassword=1
                ProxyUser=me
                ProxyPassword=pw
                """);

        whenThePreferencesAreMapped();

        thenTheProxyIs(new ProxySettings(ProxySettings.Type.SOCKS, "proxy.lan", 9050, "me", "pw"));
    }

    @Test
    void shouldTakeTheHttpProxyEmuleUsesWithTheDefaultPortAndNoCredentials() {
        givenAnEmulePreferencesIni("""
                [eMule]
                [Proxy]
                ProxyEnableProxy=1
                ProxyType=5
                ProxyName=h
                ProxyUser=x
                """);

        whenThePreferencesAreMapped();

        thenTheProxyIs(new ProxySettings(ProxySettings.Type.HTTP, "h", 1080, "", ""));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[Proxy]\nProxyEnableProxy=0\nProxyType=3\nProxyName=h\n",
                "[Proxy]\nProxyEnableProxy=1\nProxyType=9\nProxyName=h\n",
                "[Proxy]\nProxyEnableProxy=1\nProxyType=3\nProxyName=\n"
            })
    void shouldTakeNoProxyWhenEmuleUsesNoneOrAnUnusableOne(String proxy) {
        givenAnEmulePreferencesIni("[eMule]\n" + proxy);

        whenThePreferencesAreMapped();

        thenTheProxyIs(ProxySettings.NONE);
    }

    @Test
    void shouldFailAProfileWithoutPreferencesSection() throws IOException {
        givenTheEmuleFile("preferences.ini", "[Other]\nA=1\n");

        whenTheProfileIsImported(new ImportOptions(false, true, true));

        thenTheOutcomeOfIs("preferences.ini", Outcome.FAILED);
        thenThePreferencesAreOurDefaults();
        // Without preferences.ini the temp directory is looked for next to the config.
        thenTheDownloadsAdoptedAre("001.part.met", "002.part.met");
    }

    @Test
    void shouldTakeTheDownloadsFromEveryTempDirectory() throws IOException {
        givenTheEmuleDownloadFilesMovedTo("emule/Temp2", "002.part.met", "002.part");
        givenTheEmuleTempDirectories(
                this.dir.resolve("gone").toString(),
                this.dir.resolve("emule/Temp2").toString());

        whenTheProfileIsImported(new ImportOptions(false, false, true));

        thenTheDownloadsAdoptedAre("001.part.met", "002.part.met");
    }

    @Test
    void shouldReportAnEmptyTempDirectory() throws IOException {
        givenAnEmptyEmuleTempDirectory();

        whenTheProfileIsImported(new ImportOptions(false, false, true));

        thenTheOutcomeOfIs("Temp", Outcome.MISSING);
        thenTheDetailOfIs("Temp", "no downloads in progress");
    }

    @Test
    void shouldIgnoreTheRelativeDirectoriesOfTheProfile() throws IOException {
        givenTheEmulePreferencesWithTextReplaced("IncomingDir=" + this.emule.incoming, "IncomingDir=.");
        givenTheEmulePreferencesWithTextReplaced("TempDir=" + this.emule.temp, "TempDir=.");
        givenTheEmuleFile("shareddir.dat", TextFiles.writeLines(List.of(".", "..")));

        whenTheProfileIsImported(new ImportOptions(false, false, true));

        thenNoDirectoryIsShared();
        thenTheOutcomeOfIs("shareddir.dat", Outcome.MISSING);
        // Without a usable temp directory, the one next to the config.
        thenTheDownloadsAdoptedAre("001.part.met", "002.part.met");
    }

    @Test
    void shouldFailAHugeFileAloneInsteadOfExhaustingMemory() throws IOException {
        givenAHugeSparseEmuleFile("known.met", 3L << 30);

        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheOutcomeOfIs("known.met", Outcome.FAILED);
        thenTheOutcomeOfIs("server.met", Outcome.IMPORTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "njmule"})
    void shouldImportNothingIntoTheOwnDirectoryOfEmule(String inside) throws IOException {
        givenOurDirectoryInsideTheEmuleProfileAt(inside);
        givenAFingerprintOfTheEmuleProfile();

        whenTheProfileIsImported(new ImportOptions(false, true, true));

        thenTheOnlyItemFailed();
        thenTheTargetIsUntouched();
        thenTheEmuleProfileIsUnchanged();
    }

    @Test
    void shouldLeaveOnlyTheAichTreesOutWhenTheirFileIsBroken() throws IOException {
        givenTheEmuleFile("known2_64.met", new byte[] {2, 1});

        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheOutcomeOfIs("known.met", Outcome.IMPORTED);
        thenTheDetailOfContains("known.met", "known2_64.met");
        thenTheKnownFilesTakenAre(1);
    }

    @Test
    void shouldChangeNeitherEmuleNorWhatItTookAlreadyOnASecondImport() throws IOException {
        givenAFingerprintOfTheEmuleProfile();
        givenAPreviousImport(new ImportOptions(false, true, false));

        whenTheProfileIsImported(new ImportOptions(false, true, false));

        thenTheOutcomeOfIs("ipfilter.dat", Outcome.SKIPPED);
        thenTheOutcomeOfIs("preferences.dat", Outcome.IMPORTED);
        thenTheEmuleProfileIsUnchanged();
    }

    @Test
    void shouldLeaveOurFilesAsTheyWereOnADryRun() throws IOException {
        givenAPreviousImport(new ImportOptions(false, true, false));
        givenAFingerprintOfOurDirectory();

        whenTheProfileIsImported(new ImportOptions(true, true, true));

        thenOurDirectoryIsUnchanged();
    }

    @Test
    void shouldReportAMissingProfileAndWriteNothing() throws IOException {
        givenNoEmuleProfileAt("no such profile");

        whenTheProfileIsImported(new ImportOptions(false, true, true));

        thenEveryItemIsMissingOrSkipped();
        thenTheTargetIsUntouched();
        thenNothingIsWrittenIntoOurDirectory();
    }

    @Test
    void shouldFailEmptyOrMisplacedFilesAloneAndGoOnWithTheRest() throws IOException {
        givenEmptyEmuleFiles("server.met", "known.met", "nodes.dat");
        givenADirectoryInPlaceOfTheEmuleFile("preferences.ini");

        whenTheProfileIsImported(new ImportOptions(false, false, true));

        thenTheOutcomeOfIs("server.met", Outcome.FAILED);
        thenTheOutcomeOfIs("known.met", Outcome.FAILED);
        thenTheOutcomeOfIs("nodes.dat", Outcome.FAILED);
        thenTheOutcomeOfIs("preferences.ini", Outcome.FAILED);
        thenTheOutcomeOfIs("cancelled.met", Outcome.IMPORTED);
        thenTheDownloadsAdoptedAre("001.part.met", "002.part.met");
    }

    @Test
    void shouldLeaveADownloadNamedToClimbOutWhereItIs() throws IOException {
        givenTheDownloadIsNamed(2, "../../evil.bin");

        whenTheProfileIsImported(new ImportOptions(false, false, true));

        thenEveryDownloadIsAdoptedFromTheEmuleTempDirectory();
        thenNoFileEscapedTo("evil.bin");
    }

    @Test
    void shouldAdoptEachDownloadOnceFromATempDirectoryNamedTwice() throws IOException {
        givenTheEmuleTempDirectories(this.emule.temp.resolve(".").toString(), this.emule.temp + File.separator);

        whenTheProfileIsImported(new ImportOptions(false, false, true));

        thenTheDownloadsAdoptedAre("001.part.met", "002.part.met");
    }

    @Test
    void shouldLeaveNoIdentityWaitingForAnUnmarkedUserHash() throws IOException {
        givenTheEmuleUserHash("0102030405060708090A0B0C0D0E0F10");

        whenTheProfileIsImported(new ImportOptions(false, true, false));

        thenTheOutcomeOfIs("preferences.dat", Outcome.FAILED);
        thenNoIdentityWaits();
    }

    @Test
    void shouldKeepTheRestOfTheIdentityWhenTheCreditsAreCorrupt() throws IOException {
        givenTheEmuleFile("clients.met", new byte[] {0x12, 1, 0});

        whenTheProfileIsImported(new ImportOptions(false, true, false));

        thenTheOutcomeOfIs("clients.met", Outcome.FAILED);
        thenThePendingIdentityHolds("preferences.dat", "cryptkey.dat");
        thenThePendingIdentityLacks("clients.met");
    }

    @Test
    void shouldShareTheOtherDirectoriesWhenOneIsRefused() throws IOException {
        givenTheTargetRefusesToShare(this.emule.incoming);

        whenTheProfileIsImported(new ImportOptions(false, false, false));

        thenTheOutcomeOfIs("shareddir.dat", Outcome.IMPORTED);
        thenTheDetailOfContains("shareddir.dat", "1 directories", "1 not shared", "refusing");
        thenTheDirectoriesSharedAre(this.emule.shared);
    }

    private void givenAFingerprintOfTheEmuleProfile() throws IOException {
        this.emuleBefore = this.emule.fingerprint();
    }

    private void givenAFingerprintOfOurDirectory() throws IOException {
        this.oursBefore = fingerprint(this.ours);
    }

    private void givenTheEmuleFile(String name, String content) throws IOException {
        Files.writeString(this.emule.config.resolve(name), content);
    }

    private void givenTheEmuleFile(String name, byte[] content) throws IOException {
        Files.write(this.emule.config.resolve(name), content);
    }

    private void givenEmptyEmuleFiles(String... names) throws IOException {
        for (String name : names) {
            givenTheEmuleFile(name, new byte[0]);
        }
    }

    private void givenTheEmuleFileIsDeleted(String name) throws IOException {
        Files.delete(this.emule.config.resolve(name));
    }

    private void givenADirectoryInPlaceOfTheEmuleFile(String name) throws IOException {
        givenTheEmuleFileIsDeleted(name);
        Files.createDirectory(this.emule.config.resolve(name));
    }

    private void givenAHugeSparseEmuleFile(String name, long lastByte) throws IOException {
        Path file = this.emule.config.resolve(name);
        Files.delete(file);
        try (SeekableByteChannel huge = Files.newByteChannel(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            huge.position(lastByte).write(ByteBuffer.wrap(new byte[] {1}));
        }
    }

    private void givenTheEmuleUserHash(String hex) throws IOException {
        givenTheEmuleFile(
                "preferences.dat",
                PreferencesDat.write(
                        new PreferencesDat.Contents(PreferencesDat.VERSION, Hash16.parseHex(hex), Bytes.EMPTY)));
    }

    private void givenTheEmulePreferencesWithTextReplaced(String text, String replacement) throws IOException {
        Path ini = this.emule.config.resolve("preferences.ini");
        Files.writeString(
                ini,
                Files.readString(ini, StandardCharsets.ISO_8859_1).replace(text, replacement),
                StandardCharsets.ISO_8859_1);
    }

    private void givenTheEmuleTempDirectories(String... directories) throws IOException {
        Path ini = this.emule.config.resolve("preferences.ini");
        Files.writeString(
                ini,
                Files.readString(ini, StandardCharsets.ISO_8859_1)
                        + "TempDirs="
                        + String.join("|", directories)
                        + "\r\n",
                StandardCharsets.ISO_8859_1);
    }

    private void givenTheEmuleDownloadFilesMovedTo(String directory, String... names) throws IOException {
        Path other = Files.createDirectories(this.dir.resolve(directory));
        for (String name : names) {
            Files.move(this.emule.temp.resolve(name), other.resolve(name));
        }
    }

    private void givenAnEmptyEmuleTempDirectory() throws IOException {
        try (var files = Files.list(this.emule.temp)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    private void givenTheDownloadIsInCategory(int download, int category) throws IOException {
        this.emule.putInCategory(download, category);
    }

    private void givenTheDownloadIsNamed(int download, String name) throws IOException {
        this.emule.retag(download, new Tag.StringTag(TagName.id(FileTag.FILENAME), name));
    }

    private void givenAnotherEmuleProfile() throws IOException {
        this.emule = SyntheticEmuleProfile.create(this.dir.resolve("other"));
    }

    private void givenNoEmuleProfileAt(String name) {
        this.missingProfile = this.dir.resolve(name);
    }

    private void givenOurDirectoryInsideTheEmuleProfileAt(String inside) {
        this.ours = this.emule.config.resolve(inside);
    }

    private void givenOurFile(String name, String content) throws IOException {
        Files.writeString(this.ours.resolve(name), content);
    }

    private void givenEmuleIsRunning() {
        this.emuleRunning = true;
    }

    private void givenEmuleIsClosed() {
        this.emuleRunning = false;
    }

    private void givenTheTargetKeepsItsOwnCategories() {
        this.target.ownCategories = true;
    }

    private void givenTheTargetRefusesToShare(Path directory) {
        this.target.refused.add(directory);
    }

    private void givenAPreviousImport(ImportOptions options) {
        this.report = run(options);
    }

    private void givenTheImportPutOffTheFileToTheNextStart(String name) {
        assertThat(detail(this.report, name)).endsWith("from the next start");
        assertThat(this.ours.resolve(name)).doesNotExist();
    }

    private void givenThePendingFilesWereApplied() {
        EmuleImporter.applyPendingFiles(this.ours);
    }

    private void givenAnAttemptToApplyThePendingIdentity() throws IOException {
        boolean running = this.emuleRunning;
        EmuleImporter.applyPendingIdentity(this.ours, () -> running);
    }

    private void givenALeftoverInThePendingIdentity(String name, String content) throws IOException {
        Files.writeString(pendingIdentity().resolve(name), content);
    }

    private void givenAFolderLeftInThePendingIdentity(String name) throws IOException {
        Files.writeString(
                Files.createDirectories(pendingIdentity().resolve(name)).resolve("x"), "x");
    }

    private void givenThePendingIdentityLosesItsFile(String name) throws IOException {
        Files.delete(pendingIdentity().resolve(name));
    }

    private void givenAnEmulePreferencesIni(String text) {
        this.preferencesIni = text;
    }

    private void whenTheProfileIsImported(ImportOptions options) {
        this.report = run(options);
        this.preferences = this.target.preferences;
    }

    private void whenThePendingFilesAreApplied() {
        this.filesApplied = EmuleImporter.applyPendingFiles(this.ours);
    }

    private void whenThePendingIdentityIsApplied() throws IOException {
        boolean running = this.emuleRunning;
        this.identityApplied = EmuleImporter.applyPendingIdentity(this.ours, () -> running);
    }

    private void whenThePreferencesAreMapped() {
        this.preferences = EmuleImporter.mapPreferences(
                IniFile.parse(this.preferencesIni.getBytes(StandardCharsets.US_ASCII)), Preferences.defaults());
    }

    private void whenTheEmuleColourIsMapped(String rgb) {
        this.colour = EmuleImporter.color(OptionalLong.of(Long.decode(rgb)));
    }

    private void thenTheReportIsADryRun() {
        assertThat(this.report.dryRun()).isTrue();
    }

    private void thenTheOutcomeOfIs(String file, Outcome outcome) {
        assertThat(outcomes(this.report)).containsEntry(file, outcome);
    }

    private void thenThereIsNoOutcomeFor(String file) {
        assertThat(outcomes(this.report)).doesNotContainKey(file);
    }

    private void thenTheOnlyItemFailed() {
        assertThat(this.report.items())
                .singleElement()
                .satisfies(i -> assertThat(i.outcome()).isEqualTo(Outcome.FAILED));
    }

    private void thenEveryItemIsMissingOrSkipped() {
        assertThat(this.report.items())
                .isNotEmpty()
                .allSatisfy(i -> assertThat(i.outcome()).isIn(Outcome.MISSING, Outcome.SKIPPED));
    }

    private void thenTheDetailOfIs(String file, String detail) {
        assertThat(detail(this.report, file)).isEqualTo(detail);
    }

    private void thenTheDetailOfContains(String file, String... parts) {
        assertThat(detail(this.report, file)).contains(parts);
    }

    private void thenADetailContains(String part) {
        assertThat(this.report.items()).anyMatch(i -> i.detail().contains(part));
    }

    private void thenTheTargetIsUntouched() {
        assertThat(this.target.untouched()).isTrue();
    }

    private void thenNothingIsWrittenIntoOurDirectory() throws IOException {
        try (Stream<Path> left = Files.list(this.ours)) {
            assertThat(left.count()).isZero();
        }
    }

    private void thenOurDirectoryIsUnchanged() throws IOException {
        assertThat(fingerprint(this.ours)).isEqualTo(this.oursBefore);
    }

    private void thenTheEmuleProfileIsUnchanged() throws IOException {
        assertThat(this.emule.fingerprint()).isEqualTo(this.emuleBefore);
    }

    private void thenTheNickIs(String nick) {
        assertThat(this.preferences.nick()).isEqualTo(nick);
    }

    private void thenTheSpeedLimitsAre(int maxUploadKiBps, int maxDownloadKiBps) {
        assertThat(this.preferences.maxUploadKiBps()).isEqualTo(maxUploadKiBps);
        assertThat(this.preferences.maxDownloadKiBps()).isEqualTo(maxDownloadKiBps);
    }

    private void thenKadIsOff() {
        assertThat(this.preferences.kadEnabled()).isFalse();
    }

    private void thenOnlyTheDownloadedFilesAreRemembered() {
        assertThat(this.preferences.rememberCancelledFiles()).isFalse();
        assertThat(this.preferences.rememberDownloadedFiles()).isTrue();
    }

    private void thenSearchesAreNotStored() {
        assertThat(this.preferences.storeSearches()).isFalse();
    }

    private void thenDownloadsAreAddedPaused() {
        assertThat(this.preferences.addDownloadsPaused()).isTrue();
    }

    private void thenTheNextDownloadIs(NextDownload next) {
        assertThat(this.preferences.nextDownload()).isEqualTo(next);
    }

    private void thenLanAddressesAreNotFiltered() {
        assertThat(this.preferences.filterLanAddresses()).isFalse();
    }

    private void thenOnlyFriendsMaySendMessages() {
        assertThat(this.preferences.messagesFromFriendsOnly()).isTrue();
    }

    private void thenTheNamesOfNewDownloadsAreCleanedUpOf(String words) {
        assertThat(this.preferences.cleanUpNewNames()).isTrue();
        assertThat(this.preferences.nameCleanups()).isEqualTo(words);
    }

    private void thenTheMessageFilterIs(String filter) {
        assertThat(this.preferences.messageFilter()).isEqualTo(filter);
    }

    private void thenTheAdvancedSpamFilterIsOnAsItIsNotInTheProfile() {
        assertThat(this.preferences.advancedSpamFilter())
                .as("not in the profile")
                .isTrue();
    }

    private void thenTheConnectionLimitsAre(int newConnectionsPerFiveSeconds, int uploadQueueSize) {
        assertThat(this.preferences.newConnectionsPerFiveSeconds()).isEqualTo(newConnectionsPerFiveSeconds);
        assertThat(this.preferences.uploadQueueSize()).isEqualTo(uploadQueueSize);
    }

    private void thenTheTcpPortIs(int port) {
        assertThat(this.preferences.tcpPort()).isEqualTo(port);
    }

    private void thenTheMaximumConnectionsAre(int connections) {
        assertThat(this.preferences.maxConnections()).isEqualTo(connections);
    }

    private void thenTheDownloadCapacityIs(int kibps) {
        assertThat(this.preferences.downloadCapacityKiBps()).isEqualTo(kibps);
    }

    private void thenTheUploadCapacityIs(int kibps, String reason) {
        assertThat(this.preferences.uploadCapacityKiBps()).as(reason).isEqualTo(kibps);
    }

    private void thenTheMinimumFreeSpaceIs(int mib) {
        assertThat(this.preferences.minFreeSpaceMiB()).isEqualTo(mib);
    }

    private void thenTheMinimumFreeSpaceIs(int mib, String reason) {
        assertThat(this.preferences.minFreeSpaceMiB()).as(reason).isEqualTo(mib);
    }

    private void thenTheObfuscationIs(ObfuscationMode mode) {
        assertThat(this.preferences.obfuscation()).isEqualTo(mode);
    }

    private void thenTheProxyIs(ProxySettings proxy) {
        assertThat(this.preferences.proxy()).isEqualTo(proxy);
    }

    private void thenThePreferencesAreOurDefaults() {
        assertThat(this.preferences).isEqualTo(Preferences.defaults());
    }

    private void thenTheServersAreTakenFromTheEmuleFile(String name) {
        assertThat(this.target.servers).containsExactly(this.emule.config.resolve(name));
    }

    private void thenNoServersAreTaken() {
        assertThat(this.target.servers).isEmpty();
    }

    private void thenTheKadContactsTakenAre(int contacts) {
        assertThat(this.target.contacts).isEqualTo(contacts);
    }

    private void thenTheKnownFilesTakenAre(int known) {
        assertThat(this.target.known).isEqualTo(known);
    }

    private void thenTheDirectoriesSharedAre(Path... directories) {
        assertThat(this.target.shared).containsExactly(directories);
    }

    private void thenNoDirectoryIsShared() {
        assertThat(this.target.shared).isEmpty();
    }

    private void thenTheDownloadsAdoptedAre(String... downloads) {
        assertThat(this.target.adopted).containsExactlyInAnyOrder(downloads);
    }

    private void thenNoDownloadIsAdopted() {
        assertThat(this.target.adopted).isEmpty();
    }

    private void thenTheDownloadIsAdoptedInCategory(String download, int category) {
        assertThat(this.target.adoptedCategories).containsEntry(download, category);
    }

    private void thenTheDownloadIsAdoptedInCategory(String download, int category, String reason) {
        assertThat(this.target.adoptedCategories).as(reason).containsEntry(download, category);
    }

    private void thenEveryDownloadIsAdoptedFromTheEmuleTempDirectory() {
        assertThat(this.target.adoptedFrom)
                .allSatisfy(p -> assertThat(p.getParent()).isEqualTo(this.emule.temp));
    }

    private void thenNoFileEscapedTo(String name) {
        assertThat(this.dir.resolve(name)).doesNotExist();
    }

    private void thenTheCategoryIdsTakenAre(Integer... ids) {
        assertThat(this.target.categories).extracting(Category::id).containsExactly(ids);
    }

    private void thenTheCategoryNamesTakenAre(String... names) {
        assertThat(this.target.categories).extracting(Category::name).containsExactly(names);
    }

    private void thenTheCategoryIsNamed(int index, String name) {
        assertThat(this.target.categories.get(index).name()).isEqualTo(name);
    }

    private void thenTheCategoryLeavesTheIncomingDirectoryOfEmuleOut(int index) {
        assertThat(this.target.categories.get(index).incoming())
                .as("eMule's incoming folder is not ours: our own takes its place")
                .isEmpty();
    }

    private void thenTheCategoryHasTheIncomingDirectory(int index, Path directory) {
        assertThat(this.target.categories.get(index).incoming()).contains(directory);
    }

    private void thenTheCategoryHasTheColour(int index, int colour, String name) {
        assertThat(this.target.categories.get(index).color()).as(name).isEqualTo(colour);
    }

    private void thenTheCategoryMatches(int index, String fileName) {
        assertThat(this.target.categories.get(index).matches(fileName)).isTrue();
    }

    private void thenTheNameCommentAndPatternsOfTheCategoryHaveTheLengths(
            int index, int name, int comment, int patterns) {
        Category category = this.target.categories.get(index);
        assertThat(category.name()).hasSize(name);
        assertThat(category.comment()).hasSize(comment);
        assertThat(category.patterns()).hasSize(patterns);
    }

    private void thenTheCategoryHasNoPatterns(int index) {
        assertThat(this.target.categories.get(index).patterns()).isEmpty();
    }

    private void thenTheColourIs(int colour, String name) {
        assertThat(this.colour).as(name).isEqualTo(colour);
    }

    private void thenOurFileExists(String name) {
        assertThat(this.ours.resolve(name)).exists();
    }

    private void thenOurFileDoesNotExist(String name) {
        assertThat(this.ours.resolve(name)).doesNotExist();
    }

    private void thenOurFileIsEmpty(String name) {
        assertThat(this.ours.resolve(name)).isEmptyFile();
    }

    private void thenOurFileIsTheEmuleOne(String name) {
        assertThat(this.ours.resolve(name)).hasSameBinaryContentAs(this.emule.config.resolve(name));
    }

    private void thenTheFilesAppliedAre(int files) {
        assertThat(this.filesApplied).isEqualTo(files);
    }

    private void thenTheFileWaitsForTheNextStart(String name) {
        assertThat(this.ours.resolve(EmuleImporter.PENDING_FILES).resolve(name)).exists();
    }

    private void thenTheFileDoesNotWaitForTheNextStart(String name) {
        assertThat(this.ours.resolve(EmuleImporter.PENDING_FILES).resolve(name)).doesNotExist();
    }

    private void thenNoFileWaitsForTheNextStart() {
        assertThat(this.ours.resolve(EmuleImporter.PENDING_FILES)).doesNotExist();
    }

    private void thenThePendingIdentityIsApplied() {
        assertThat(this.identityApplied).isTrue();
    }

    private void thenThePendingIdentityIsNotApplied() {
        assertThat(this.identityApplied).isFalse();
    }

    private void thenThePendingIdentityStillWaits() {
        assertThat(pendingIdentity()).isDirectory();
    }

    private void thenNoIdentityWaits() {
        assertThat(pendingIdentity()).doesNotExist();
    }

    private void thenThePendingIdentityHoldsTheEmuleFile(String name) {
        assertThat(pendingIdentity().resolve(name)).hasSameBinaryContentAs(this.emule.config.resolve(name));
    }

    private void thenThePendingIdentityHolds(String... names) {
        for (String name : names) {
            assertThat(pendingIdentity().resolve(name)).exists();
        }
    }

    private void thenThePendingIdentityLacks(String... names) {
        for (String name : names) {
            assertThat(pendingIdentity().resolve(name)).doesNotExist();
        }
    }

    private void thenThePendingKadUdpKeyIs(long key) throws IOException {
        assertThat(PacketReader.of(Files.readAllBytes(pendingIdentity().resolve("kadudpkey.dat")))
                        .u32())
                .isEqualTo(key);
    }

    private Path pendingIdentity() {
        return this.ours.resolve(EmuleImporter.PENDING_IDENTITY);
    }

    private static SortedMap<String, String> fingerprint(Path root) throws IOException {
        SortedMap<String, String> sums = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                sums.put(
                        root.relativize(file).toString(),
                        Arrays.hashCode(Files.readAllBytes(file)) + "@"
                                + Files.getLastModifiedTime(file).toMillis());
            }
        }
        return sums;
    }

    private ImportReport run(ImportOptions options) {
        boolean running = this.emuleRunning;
        Path config = Objects.requireNonNullElse(this.missingProfile, this.emule.config);
        return new EmuleImporter(config, options, this.target, () -> running).run();
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
