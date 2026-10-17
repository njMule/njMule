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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.ProxySettings;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.webui.WebUiSettings;

@Timeout(120)
class ScreensTest {

    private static final String LINK = "ed2k://|file|test%20file.bin|10000|0123456789ABCDEF0123456789ABCDEF|/";

    @TempDir
    Path dir;

    private TestGui test;
    private SettingsScreen settings;
    private List<String> languageNames;
    private KadInfo kad;
    private TransfersScreen transfers;
    private ServersScreen servers;
    private SearchScreen search;
    private SharedScreen shared;
    private KadScreen kadScreen;
    private StatisticsScreen statistics;
    private SchedulerPane scheduler;
    private Path serverMet;
    private Path sharedFolder;
    private long timeLeft;
    private List<?> serverLinks;
    private TreeItem<String> closedSection;
    private WebUiSettings savedWebUi;
    private Throwable exception;

    @AfterEach
    void close() throws Exception {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldAddADownloadFromALinkInTheDialog() throws Exception {
        givenTheTransfersScreen();

        whenTheLinksAreAdded(LINK);

        thenTheDownloadRowsBecome(1);
    }

    @Test
    void shouldResumeADownloadThatWasPausedThenStopped() throws Exception {
        givenTheTransfersScreen();
        givenTheDownloadOf(LINK);
        givenTheDownloadChosen();
        givenTheDownloadPaused();
        givenTheDownloadStopped();

        whenTheDownloadIsResumed();

        thenTheDownloadBecomes(DownloadInfo.Status.DOWNLOADING);
    }

    @Test
    void shouldRaiseThePriorityOfTheChosenDownload() throws Exception {
        givenTheTransfersScreen();
        givenTheDownloadOf(LINK);
        givenTheDownloadChosen();

        whenTheDownloadIsPrioritized(DownloadPriority.HIGH);

        thenTheDownloadPriorityBecomes(DownloadPriority.HIGH);
    }

    @Test
    void shouldCopyTheLinkOfTheChosenDownload() throws Exception {
        givenTheTransfersScreen();
        givenTheDownloadOf(LINK);
        givenTheDownloadChosen();

        whenTheLinksOfTheChosenDownloadsAreCopied();

        thenTheClipboardStartsWith("ed2k://|file|test%20file.bin|10000|");
    }

    @Test
    void shouldCancelTheChosenDownload() throws Exception {
        givenTheTransfersScreen();
        givenTheDownloadOf(LINK);
        givenTheDownloadChosen();

        whenTheChosenDownloadsAreCancelled();

        thenTheDownloadRowsBecome(0);
    }

    @Test
    void shouldAddNothingFromATextWithoutLinks() throws Exception {
        givenTheTransfersScreen();

        whenTheLinksAreAdded("not a link");

        thenTheDownloadRowsAre(0);
    }

    @Test
    void shouldReckonTheSecondsLeftFromTheRate() {
        whenTheTimeLeftIsReckonedFor(1000, 400, 100);

        thenTheSecondsLeftAre(6);
    }

    @Test
    void shouldPutANewDownloadInTheCategoryItsNameMatches() throws Exception {
        givenTheTransfersScreen();
        givenTheCategoriesFilmsAndTests();

        whenTheLinksAreAdded(LINK);

        thenTheDownloadRowsBecome(1);
        thenTheDownloadIsInTheCategory(2);
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "2, 1"})
    void shouldShowOnlyTheDownloadsOfACategory(int category, int rows) throws Exception {
        givenTheTransfersScreen();
        givenTheCategoriesFilmsAndTests();
        givenTheDownloadOf(LINK);

        whenTheCategoryIsShown(category);

        thenTheDownloadRowsAre(rows);
    }

    @Test
    void shouldTakeADownloadOutOfTheCategoryShown() throws Exception {
        givenTheTransfersScreen();
        givenTheCategoriesFilmsAndTests();
        givenTheDownloadOf(LINK);
        givenTheCategoryShown(2);
        givenTheDownloadChosen();

        whenTheDownloadIsMovedTo(Category.NONE);

        thenTheCategoryShownHasNoDownload();
    }

    @Test
    void shouldRefuseAnIpFilterFromALocalAddress() throws Exception {
        givenTheSettingsScreen();

        whenTheIpFilterIsUpdatedFrom("http://127.0.0.1:9/ipfilter.zip");

        thenTheUserIsToldTheAddressIsLocalOrPrivate();
        thenNoIpFilterAddressIsRemembered();
    }

    @Test
    void shouldNotRememberAServerListAddressThatFailed() throws Exception {
        givenTheServersScreen();

        whenTheServerListIsUpdatedFrom("http://127.0.0.1:9/server.met");

        thenTheUserIsToldTheAddressIsLocalOrPrivate();
        thenNoServerListAddressIsRemembered();
    }

    @Test
    void shouldRememberAServerListAddressOnceItWorked() throws Exception {
        givenTheServersScreen();

        whenTheServerListAddressWorked("https://example.org/server.met");

        thenTheServerListBoxOffers("https://example.org/server.met");
        thenTheSavedServerListAddressesAre("https://example.org/server.met");
    }

    @Test
    void shouldAddTheServersOfAServerMetFile() throws Exception {
        givenTheServersScreen();
        givenAServerMetWithTwoServers();

        whenTheServersAreAddedFromTheFile();

        thenTheServerRowsBecome(2);
        thenTheServerCountReads("Servers (2)");
        thenTheHiddenServerColumnsAre("Hard File Limit", "Version");
    }

    @Test
    void shouldNarrowTheServersAsTheFilterIsTyped() throws Exception {
        givenTheServersScreen();
        givenAServerMetWithTwoServers();
        givenTheServersAddedFromTheFile();

        whenTheServersAreFilteredBy("2.3.5");

        thenTheServerRowsAre(1);
        thenTheServerCountReads("Servers (1/2)");
    }

    @Test
    void shouldListAllServersAgainOnceTheFilterIsCleared() throws Exception {
        givenTheServersScreen();
        givenAServerMetWithTwoServers();
        givenTheServersAddedFromTheFile();
        givenTheServersFilteredBy("2.3.5");

        whenTheServerFilterIsCleared();

        thenTheServerRowsAre(2);
    }

    @Test
    void shouldMakeTheChosenServerStaticAndRaiseItsPriority() throws Exception {
        givenTheServersScreen();
        givenAServerMetWithTwoServers();
        givenTheServersAddedFromTheFile();
        givenTheServerChosen(0);
        givenTheChosenServerMadeStatic();

        whenTheChosenServerIsPrioritized(ServerInfo.Priority.HIGH);

        thenTheFirstServerBecomesStaticWith(ServerInfo.Priority.HIGH);
        thenTheStaticServersAreSaved();
    }

    @Test
    void shouldCopyTheLinkOfTheChosenServer() throws Exception {
        givenTheServersScreen();
        givenAServerMetWithTwoServers();
        givenTheServersAddedFromTheFile();
        givenTheServerChosen(0);

        whenTheLinksOfTheChosenServersAreCopied();

        thenTheClipboardHoldsTheLinkOfTheFirstServer();
    }

    @Test
    void shouldFindOnlyTheServerLinksInAText() {
        whenTheServerLinksAreFoundIn("see ed2k://|server|81.2.3.4|4661|/ and ed2k://|server|5.6.7.8|4242|/"
                + " ed2k://|file|x|1|0123456789ABCDEF0123456789ABCDEF|/");

        thenTheServerLinksFoundAre(2);
    }

    @Test
    void shouldAddAServerLinkPastedFromTheClipboard() throws Exception {
        givenTheServersScreen();
        givenAServerMetWithTwoServers();
        givenTheServersAddedFromTheFile();
        givenTheClipboardHolds("ed2k://|server|81.2.3.6|4663|/");

        whenTheServerLinksArePasted();

        thenTheServerRowsBecome(3);
    }

    @Test
    void shouldRemoveAllServers() throws Exception {
        givenTheServersScreen();
        givenAServerMetWithTwoServers();
        givenTheServersAddedFromTheFile();

        whenAllServersAreRemoved();

        thenTheServerRowsBecome(0);
    }

    @Test
    void shouldAddTheServersOfAFileAgainOnceAllWereRemoved() throws Exception {
        givenTheServersScreen();
        givenAServerMetWithTwoServers();
        givenTheServersAddedFromTheFile();
        givenAllServersRemoved();

        whenTheServersAreAddedFromTheFile();

        thenTheServerRowsBecome(2);
    }

    @Test
    void shouldRemoveTheChosenServer() throws Exception {
        givenTheServersScreen();
        givenAServerMetWithTwoServers();
        givenTheServersAddedFromTheFile();
        givenConnectedToTheFirstServer();
        givenDisconnected();
        givenTheOnlyServerChosen(0);

        whenTheChosenServerIsRemoved();

        thenTheServerRowsBecome(1);
    }

    @Test
    void shouldRefuseToConnectToAnAddressWithoutAPort() throws Exception {
        givenTheServersScreen();

        whenConnectingToTheAddress("nowhere");

        thenTheUserIsToldSomethingAbout("host:port: nowhere");
    }

    @Test
    void shouldRefuseASearchWithoutAServer() throws Exception {
        givenTheSearchScreen();
        givenTheSearchMethod(SearchScreen.Method.SERVER);
        givenTheSearchType(SearchScreen.FileType.VIDEO);
        givenTheSearchMinimumSize("100");
        givenMoreResultsAStopAndADownloadAskedForWithoutASearch();

        whenTheSearchIsStartedFor("ubuntu");

        thenNoExceptionOccurred();
        thenTheUserIsToldWhy();
        thenNoSearchTabIsOpen();
    }

    @Test
    void shouldIgnoreABlankSearch() throws Exception {
        givenTheSearchScreen();

        whenTheSearchIsStartedFor("  ");

        thenNoExceptionOccurred();
    }

    @Test
    void shouldBringBackTheSearchesLeftOpenWithTheirResults() throws Exception {
        givenTheGui();
        givenAKeptServerSearchFor("film @type=Video");

        whenTheSearchScreenShows();

        thenTheSearchTabsBecome(1);
        thenTheFirstSearchTabReads("film (1)");
    }

    @Test
    void shouldNotKeepTheSearchTabsClosedBeforeTheExit() throws Exception {
        givenTheGui();
        givenAKeptServerSearchFor("film @type=Video");
        givenTheSearchScreenShownWithItsTab();
        givenTheSearchTabsClosed();

        whenTheSearchScreenCloses();

        thenNoSearchIsKept();
    }

    @Test
    void shouldListCopyAndPrioritizeTheFilesOfASharedFolder() throws Exception {
        givenTheSharedScreen();
        givenAFolderWithAFile("share", "a.txt", 3000);
        givenTheFolderSharedAndListed();
        givenTheFirstSharedFileIs("a.txt");
        givenTheFirstSharedFileChosen();
        givenTheLinksOfTheChosenSharedFilesCopied();
        givenTheClipboardStartsWith("ed2k://|file|a.txt|3000|");

        whenTheChosenSharedFilesArePrioritized(UploadPriority.RELEASE);

        thenTheFirstSharedFilePriorityBecomes(UploadPriority.RELEASE);
    }

    @Test
    void shouldConnectKadByHand() throws Exception {
        givenTheKadScreen();

        whenKadIsConnectedByHand();

        thenTheEngineCanRecheckTheFirewall();
    }

    @Test
    void shouldDisconnectKadByHand() throws Exception {
        givenTheKadScreen();
        givenKadConnectedByHand();
        givenTheFirewallRechecked();

        whenKadIsDisconnectedByHand();

        thenKadIsOff();
    }

    @Test
    void shouldShowThatKadIsOff() throws Exception {
        givenTheGui();

        whenTheKadScreenShows();

        thenTheKadStateReads("Off");
    }

    @Test
    void shouldTellThatABootstrapNeedsKad() throws Exception {
        givenTheKadScreen();
        givenABootstrapFrom("bad");

        whenKadIsBootstrappedFrom("127.0.0.1:4672");

        thenTheUserIsToldAtLeast(2);
        thenTheUserIsToldSomethingAbout("Kad is not running");
    }

    @ParameterizedTest
    @CsvSource({"true, SERVING, Open, Buddy of a firewalled client", "false, CONNECTING, Unknown, Connecting"})
    void shouldNameTheTestedUdpStateAndTheBuddy(boolean udpVerified, KadInfo.Buddy buddy, String udp, String buddyName)
            throws Exception {
        givenTheGui();

        whenKadIs(new KadInfo(true, 10, 5, true, "", "", 4672, false, udpVerified, buddy));

        thenKadReads(udp, buddyName);
    }

    @Test
    void shouldShowTheStatisticsOfTheSession() throws Exception {
        givenTheStatisticsScreen();

        whenTheModelTicks();

        thenTheDownloadedTotalReads("0 B");
        thenTheStatisticsSectionsStartWith("Transfers", "Uploads", "Downloads", "Connection", "Time Statistics");
        thenTheStatisticsSectionsInclude("Clients (0)", "Servers", "Shared Files", "Free Space");
        thenTheFirstStatisticsSectionIsOpen();
        thenTheLastStatisticsSectionHoldsTempAndIncoming();
    }

    @Test
    void shouldKeepAStatisticsSectionClosedOnRefresh() throws Exception {
        givenTheStatisticsScreen();
        givenTheModelTicked();
        givenTheFirstStatisticsSectionClosed();

        whenTheStatisticsAreRefreshed();

        thenTheSameFirstStatisticsSectionStaysClosed();
    }

    @Test
    void shouldRefuseASchedulerRuleWithAWrongStart() throws Exception {
        givenTheSchedulerPane();
        givenARuleAddedForEveryDay("all week", "5");

        whenARuleIsAddedStartingAt("25:00");

        thenTheRulesListedAre(1);
        thenTheUserIsToldItWasNotSaved("Not saved");
    }

    @Test
    void shouldApplyAReplacedSchedulerRuleOnceSaved() throws Exception {
        givenTheSchedulerPane();
        givenARuleAddedForEveryDay("all week", "5");
        givenTheRuleChosen();
        givenTheRuleChanged("every day", "00:00");
        givenTheRuleReplaced();

        whenTheRulesAreSaved();

        thenARuleApplies();
        thenTheFirstRuleIsNamed("every day");
        thenTheActiveRuleShows("Now: every day");
    }

    @Test
    void shouldLoadNoSchedulerRuleOnceTheLastWasRemoved() throws Exception {
        givenTheSchedulerPane();
        givenARuleAddedForEveryDay("all week", "5");
        givenTheRuleSavedAs("every day", "00:00");
        givenTheRuleRemovedAndSaved();

        whenTheRulesAreLoaded();

        thenNoRuleIsListed();
    }

    @Test
    void shouldSaveTheNickAndTheDarkMode() throws Exception {
        givenTheSettingsShown();
        givenTheNick("Tester");
        givenTheMode(GuiSettings.Mode.DARK);

        whenTheSettingsAreApplied();

        thenTheSettingsAreSaved();
        thenTheNickIs("Tester");
        thenTheThemeIsDark();
        thenTheSavedModeIs(GuiSettings.Mode.DARK);
    }

    @Test
    void shouldSaveAProxy() throws Exception {
        givenTheSettingsShown();
        givenTheProxy(ProxySettings.Type.SOCKS, "proxy.lan");

        whenTheSettingsAreApplied();

        thenTheProxyHostBecomes("proxy.lan");
    }

    @Test
    void shouldTurnTheProxyOff() throws Exception {
        givenTheSettingsShown();
        givenTheProxySaved(ProxySettings.Type.SOCKS, "proxy.lan");
        givenTheProxyType(ProxySettings.Type.NONE);

        whenTheSettingsAreApplied();

        thenNoProxyIsUsed();
    }

    @Test
    void shouldRefuseAWrongPort() throws Exception {
        givenTheSettingsShown();
        givenTheTcpPort("port");

        whenTheSettingsAreApplied();

        thenTheUserIsToldItWasNotSaved("Not saved: TCP");
    }

    @Test
    void shouldRelaunchForAnotherLanguage() throws Exception {
        givenTheSettingsShown();
        givenTheSettingsLoadedFromTheEngine();
        givenTheLanguage(Locale.ITALIAN);

        whenTheSettingsAreApplied();

        thenTheRelaunchesAre(1);
    }

    @Test
    void shouldNeedAPasswordForTheWebInterface() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceTicked(true);

        whenTheSettingsAreApplied();

        thenTheUserIsTold("Not saved: the web interface needs a password");
    }

    @Test
    void shouldNeedAWebInterfacePasswordOfAtLeastEightCharacters() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceTicked(true);
        givenTheWebInterfacePasswords("short", "short");

        whenTheSettingsAreApplied();

        thenTheUserIsToldSomethingAbout("at least 8 characters");
    }

    @Test
    void shouldNeedTheSameWebInterfacePasswordTwice() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceTicked(true);
        givenTheWebInterfacePasswords("long enough", "long enough!");

        whenTheSettingsAreApplied();

        thenTheUserIsToldSomethingAbout("differ");
    }

    @Test
    void shouldRefuseAWebInterfacePortOutOfRangeAndSaveNothing() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceTicked(true);
        givenTheWebInterfacePasswords("long enough", "long enough!");
        givenTheWebInterfacePort("70000");

        whenTheSettingsAreApplied();

        thenTheUserIsToldSomethingAbout("65535");
        thenNoWebInterfaceSettingIsSaved();
    }

    @Test
    void shouldSaveAndShowAgainTheHostNamesOfTheWebInterface() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceTicked(true);
        givenTheWebInterfacePasswords("long enough", "long enough");
        givenTheWebInterfaceHostNames("NAS.local, njmule.example.com");

        whenTheSettingsAreApplied();

        thenTheSavedWebInterfaceHostNamesAre("nas.local", "njmule.example.com");
        thenTheWebInterfaceHostNamesReadAfterReset("nas.local, njmule.example.com");
    }

    @Test
    void shouldSaveTheTrustedProxiesOfTheWebInterface() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceTicked(true);
        givenTheWebInterfacePasswords("long enough", "long enough");
        givenTheWebInterfaceProxies("172.17.0.0/16, ::1");

        whenTheSettingsAreApplied();

        thenTheSavedWebInterfaceProxiesAre("172.17.0.0/16", "::1");
    }

    @Test
    void shouldRefuseAProxyNamedByItsHostNameAndSaveNothing() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceTicked(true);
        givenTheWebInterfacePasswords("long enough", "long enough");
        givenTheWebInterfaceProxies("proxy.example");

        whenTheSettingsAreApplied();

        thenTheUserIsToldSomethingAbout("Trusted proxies");
        thenNoWebInterfaceSettingIsSaved();
    }

    @Test
    void shouldRefuseAWebInterfaceHostNameWithAPortAndSaveNothing() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceTicked(true);
        givenTheWebInterfacePasswords("long enough", "long enough");
        givenTheWebInterfaceHostNames("nas.local:4711");

        whenTheSettingsAreApplied();

        thenTheUserIsToldSomethingAbout("Host names");
        thenNoWebInterfaceSettingIsSaved();
    }

    @Test
    void shouldSaveTheWebInterfaceWithAMatchingPassword() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceTicked(true);
        givenTheWebInterfacePasswords("long enough", "long enough");
        givenTheWebInterfacePort("4712");

        whenTheSettingsAreApplied();

        thenTheWebInterfaceIsSavedOnPort(4712);
        thenTheWebInterfacePasswordBoxIsEmpty();
    }

    @Test
    void shouldKeepThePasswordAndShowTheSavedPortOfAWebInterfaceTurnedOff() throws Exception {
        givenTheSettingsShown();
        givenTheWebInterfaceSaved("long enough", "4712");
        givenTheWebInterfaceTurnedOff();
        givenTheWebInterfaceIsOffWithTheSamePassword();

        whenTheSettingsShowAgain();

        thenTheWebInterfacePortReads("4712");
    }

    @Test
    void shouldTakePastedLinksWithSpacesInTheirNames() throws Exception {
        givenTheTransfersScreen();

        whenTheLinksAreAddedAsShown("ed2k://|file|my holiday video.mkv|5000|"
                + "00112233445566778899AABBCCDDEEFF|/\n"
                + "  ed2k://|file|notes.txt|10|"
                + "FFEEDDCCBBAA99887766554433221100|/");

        thenTheDownloadRowsBecome(2);
        thenTheUserIsToldNothing();
        thenTheEngineDownloads("my holiday video.mkv", "notes.txt");
    }

    @Test
    void shouldNameEachLanguageInItselfInTheLanguageBox() throws Exception {
        givenTheSettingsScreen();

        whenTheLanguagesOfTheLanguageBoxAreRead();

        thenTheSystemLanguageComesFirst();
        thenEveryLanguageFollowsByItsOwnName();
    }

    private void givenTheGui() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void givenTheTransfersScreen() throws Exception {
        givenTheGui();
        this.transfers = new TransfersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.transfers::view));
    }

    private void givenTheDownloadOf(String link) throws Exception {
        whenTheLinksAreAdded(link);
        thenTheDownloadRowsBecome(1);
    }

    private void givenTheDownloadChosen() throws Exception {
        FxTesting.onFx(() -> this.transfers.downloads().getSelectionModel().select(0));
    }

    private void givenTheDownloadPaused() throws Exception {
        FxTesting.onFx(this.transfers::pauseSelected);
        thenTheDownloadBecomes(DownloadInfo.Status.PAUSED);
    }

    private void givenTheDownloadStopped() throws Exception {
        FxTesting.onFx(this.transfers::stopSelected);
        thenTheDownloadBecomes(DownloadInfo.Status.STOPPED);
    }

    private void givenTheCategoriesFilmsAndTests() throws Exception {
        FxTesting.onFx(() -> this.test
                .gui
                .model()
                .setCategories(
                        List.of(
                                new Category(
                                        1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "*.mkv", false),
                                new Category(
                                        2, "Tests", Optional.empty(), "", 4, DownloadPriority.HIGH, "test", false)),
                        () -> {}));
        FxTesting.await(
                "the categories", () -> this.test.gui.model().categories().size() == 2);
    }

    private void givenTheCategoryShown(int category) throws Exception {
        whenTheCategoryIsShown(category);
    }

    private void givenTheSettingsScreen() throws Exception {
        givenTheGui();
        this.settings = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.settings::view));
    }

    private void givenTheSettingsShown() throws Exception {
        givenTheSettingsScreen();
        FxTesting.onFx(this.settings::shown);
    }

    private void givenTheNick(String nick) throws Exception {
        FxTesting.onFx(() -> this.settings.nickField().setText(nick));
    }

    private void givenTheMode(GuiSettings.Mode mode) throws Exception {
        FxTesting.onFx(() -> this.settings.modeBox().setValue(mode));
    }

    private void givenTheProxy(ProxySettings.Type type, String host) throws Exception {
        FxTesting.onFx(() -> {
            this.settings.proxyTypeBox().setValue(type);
            this.settings.proxyHostField().setText(host);
        });
    }

    private void givenTheProxySaved(ProxySettings.Type type, String host) throws Exception {
        givenTheProxy(type, host);
        whenTheSettingsAreApplied();
        thenTheProxyHostBecomes(host);
    }

    private void givenTheProxyType(ProxySettings.Type type) throws Exception {
        FxTesting.onFx(() -> this.settings.proxyTypeBox().setValue(type));
    }

    private void givenTheTcpPort(String text) throws Exception {
        FxTesting.onFx(() -> this.settings.tcpPortField().setText(text));
    }

    private void givenTheSettingsLoadedFromTheEngine() throws Exception {
        FxTesting.onFx(() -> this.settings.load(this.test.engine.preferences()));
    }

    private void givenTheLanguage(Locale language) throws Exception {
        FxTesting.onFx(() -> this.settings.languageBox().setValue(Optional.of(language)));
    }

    private void givenTheWebInterfaceTicked(boolean on) throws Exception {
        FxTesting.onFx(() -> this.settings.webUiBox().setSelected(on));
    }

    private void givenTheWebInterfacePasswords(String password, String again) throws Exception {
        FxTesting.onFx(() -> {
            this.settings.webUiPasswordField().setText(password);
            this.settings.webUiAgainField().setText(again);
        });
    }

    private void givenTheWebInterfacePort(String port) throws Exception {
        FxTesting.onFx(() -> this.settings.webUiPortField().setText(port));
    }

    private void givenTheWebInterfaceProxies(String proxies) throws Exception {
        FxTesting.onFx(() -> this.settings.webUiProxiesField().setText(proxies));
    }

    private void givenTheWebInterfaceHostNames(String names) throws Exception {
        FxTesting.onFx(() -> this.settings.webUiHostsField().setText(names));
    }

    private void givenTheWebInterfaceSaved(String password, String port) throws Exception {
        givenTheWebInterfaceTicked(true);
        givenTheWebInterfacePasswords(password, password);
        givenTheWebInterfacePort(port);
        whenTheSettingsAreApplied();
        this.savedWebUi = WebUiSettings.load(config());
        assertThat(this.savedWebUi.usable()).isTrue();
    }

    private void givenTheWebInterfaceTurnedOff() throws Exception {
        givenTheWebInterfaceTicked(false);
        whenTheSettingsAreApplied();
    }

    private void givenTheWebInterfaceIsOffWithTheSamePassword() {
        WebUiSettings off = WebUiSettings.load(config());
        assertThat(off.enabled()).isFalse();
        assertThat(off.passwordHash()).isEqualTo(this.savedWebUi.passwordHash());
    }

    private void givenTheServersScreen() throws Exception {
        givenTheGui();
        this.servers = new ServersScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.servers::view));
    }

    // Two public servers: version byte, count, then ip, port and no tags each.
    private void givenAServerMetWithTwoServers() throws Exception {
        this.serverMet = this.dir.resolve("server.met");
        Files.write(this.serverMet, new byte[] {
            (byte) 0xE0, 2, 0, 0, 0, 81, 2, 3, 4, 0x35, 0x12, 0, 0, 0, 0, 81, 2, 3, 5, 0x36, 0x12, 0, 0, 0, 0
        });
    }

    private void givenTheServersAddedFromTheFile() throws Exception {
        whenTheServersAreAddedFromTheFile();
        thenTheServerRowsBecome(2);
    }

    private void givenTheServersFilteredBy(String text) throws Exception {
        whenTheServersAreFilteredBy(text);
    }

    private void givenTheServerChosen(int index) throws Exception {
        whenTheServerIsChosen(index);
    }

    private void givenTheOnlyServerChosen(int index) throws Exception {
        FxTesting.onFx(() -> {
            this.servers.table().getSelectionModel().clearSelection();
            this.servers.table().getSelectionModel().select(index);
        });
    }

    private void givenTheChosenServerMadeStatic() throws Exception {
        FxTesting.onFx(() -> this.servers.setStatic(true));
    }

    private void givenTheClipboardHolds(String text) throws Exception {
        FxTesting.onFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        });
    }

    private void givenAllServersRemoved() throws Exception {
        whenAllServersAreRemoved();
        thenTheServerRowsBecome(0);
    }

    private void givenConnectedToTheFirstServer() throws Exception {
        FxTesting.onFx(() -> {
            ServerInfo first = this.servers.table().getItems().getFirst().get();
            this.test.model.handle(new EngineEvent.ServerConnected(first, 99, true, false));
        });
    }

    private void givenDisconnected() throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(new EngineEvent.ServerDisconnected(Optional.empty(), "test")));
    }

    private void givenTheSearchScreen() throws Exception {
        givenTheGui();
        whenTheSearchScreenShows();
    }

    private void givenTheSearchMethod(SearchScreen.Method method) throws Exception {
        FxTesting.onFx(() -> this.search.methodBox().setValue(method));
    }

    private void givenTheSearchType(SearchScreen.FileType type) throws Exception {
        FxTesting.onFx(() -> this.search.typeChips().setValue(type));
    }

    private void givenTheSearchMinimumSize(String text) throws Exception {
        FxTesting.onFx(() -> this.search.minSizeField().setText(text));
    }

    private void givenMoreResultsAStopAndADownloadAskedForWithoutASearch() throws Exception {
        FxTesting.onFx(() -> {
            this.search.more();
            this.search.stop();
            this.search.downloadSelected();
        });
    }

    private void givenAKeptServerSearchFor(String expression) throws Exception {
        SearchResult film = new SearchResult(
                Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF"), "film.mkv", 700_000_000, 4, 1, Optional.empty());
        this.test
                .engine
                .saveSearches(List.of(
                        new SavedSearch("", new SearchRequest(expression, SearchRequest.Scope.SERVER), List.of(film))))
                .get(10, TimeUnit.SECONDS);
    }

    private void givenTheSearchScreenShownWithItsTab() throws Exception {
        whenTheSearchScreenShows();
        thenTheSearchTabsBecome(1);
    }

    private void givenTheSearchTabsClosed() throws Exception {
        FxTesting.onFx(() -> this.search.tabs().getTabs().clear());
    }

    private void givenTheSharedScreen() throws Exception {
        givenTheGui();
        this.shared = new SharedScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.shared::view));
    }

    private void givenAFolderWithAFile(String folder, String file, int size) throws Exception {
        this.sharedFolder = Files.createDirectories(this.dir.resolve(folder));
        Files.write(this.sharedFolder.resolve(file), new byte[size]);
    }

    private void givenTheFolderSharedAndListed() throws Exception {
        FxTesting.onFx(() -> this.shared.share(this.sharedFolder, true));
        for (int i = 0;
                i < 40 && FxTesting.onFx(() -> this.shared.table().getItems().isEmpty());
                i++) {
            Thread.sleep(250);
            FxTesting.onFx(this.test.model::tick);
        }
    }

    private void givenTheFirstSharedFileIs(String name) throws Exception {
        assertThat(FxTesting.onFx(
                        () -> this.shared.table().getItems().getFirst().get().name()))
                .isEqualTo(name);
    }

    private void givenTheLinksOfTheChosenSharedFilesCopied() throws Exception {
        FxTesting.onFx(this.shared::copyLinks);
    }

    private void givenTheClipboardStartsWith(String text) throws Exception {
        thenTheClipboardStartsWith(text);
    }

    private void givenTheFirstSharedFileChosen() throws Exception {
        FxTesting.onFx(() -> this.shared.table().getSelectionModel().select(0));
    }

    private void givenTheKadScreen() throws Exception {
        givenTheGui();
        whenTheKadScreenShows();
    }

    private void givenKadConnectedByHand() throws Exception {
        whenKadIsConnectedByHand();
    }

    private void givenTheFirewallRechecked() throws Exception {
        FxTesting.onFx(() -> this.test.model.recheckKadFirewall());
    }

    private void givenABootstrapFrom(String node) throws Exception {
        whenKadIsBootstrappedFrom(node);
    }

    private void givenTheStatisticsScreen() throws Exception {
        givenTheGui();
        this.statistics = new StatisticsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.statistics::view));
    }

    private void givenTheModelTicked() throws Exception {
        whenTheModelTicks();
        thenTheDownloadedTotalReads("0 B");
    }

    private void givenTheFirstStatisticsSectionClosed() throws Exception {
        this.closedSection = FxTesting.onFx(
                () -> this.statistics.tree().view().getRoot().getChildren().getFirst());
        FxTesting.onFx(() -> this.closedSection.setExpanded(false));
    }

    private void givenTheSchedulerPane() throws Exception {
        givenTheSettingsScreen();
        this.scheduler = FxTesting.onFx(this.settings::schedulerPane);
    }

    private void givenARuleAddedForEveryDay(String name, String upload) throws Exception {
        FxTesting.onFx(() -> {
            this.scheduler.nameField().setText(name);
            this.scheduler.dayBoxes().values().forEach(b -> b.setSelected(true));
            this.scheduler.uploadField().setText(upload);
            this.scheduler.add();
        });
    }

    private void givenTheRuleChosen() throws Exception {
        FxTesting.onFx(() -> this.scheduler.rulesTable().getSelectionModel().select(0));
    }

    private void givenTheRuleChanged(String name, String start) throws Exception {
        FxTesting.onFx(() -> {
            this.scheduler.nameField().setText(name);
            this.scheduler.startField().setText(start);
        });
    }

    private void givenTheRuleReplaced() throws Exception {
        FxTesting.onFx(this.scheduler::replace);
    }

    private void givenTheRuleSavedAs(String name, String start) throws Exception {
        givenTheRuleChosen();
        givenTheRuleChanged(name, start);
        givenTheRuleReplaced();
        whenTheRulesAreSaved();
        thenARuleApplies();
    }

    private void givenTheRuleRemovedAndSaved() throws Exception {
        FxTesting.onFx(() -> {
            this.scheduler.rulesTable().getSelectionModel().select(0);
            this.scheduler.remove();
            this.scheduler.save();
        });
        FxTesting.await("no rule", () -> this.test.engine.activeRule().isEmpty());
    }

    // Several links are shown first: they are added as the dialog shows them.
    private void whenTheLinksAreAddedAsShown(String text) throws Exception {
        FxTesting.onFx(() -> {
            this.transfers.answerAdds(dialog -> true);
            this.transfers.addLinks(text);
        });
    }

    private void whenTheLinksAreAdded(String text) throws Exception {
        FxTesting.onFx(() -> {
            this.transfers.answerAdds(dialog -> true);
            this.transfers.addLinks(text);
        });
    }

    private void whenTheDownloadIsResumed() throws Exception {
        FxTesting.onFx(this.transfers::resumeSelected);
    }

    private void whenTheDownloadIsPrioritized(DownloadPriority priority) throws Exception {
        FxTesting.onFx(() -> this.transfers.prioritize(priority));
    }

    private void whenTheLinksOfTheChosenDownloadsAreCopied() throws Exception {
        FxTesting.onFx(this.transfers::copyLinks);
    }

    private void whenTheChosenDownloadsAreCancelled() throws Exception {
        List<DownloadInfo> selected =
                FxTesting.onFx(() -> this.transfers.downloads().getSelectionModel().getSelectedItems().stream()
                        .map(r -> r.get())
                        .toList());
        FxTesting.onFx(() -> this.transfers.cancel(selected));
    }

    private void whenTheTimeLeftIsReckonedFor(long size, long completed, long bytesPerSecond) {
        this.timeLeft = TransfersScreen.remaining(new DownloadInfo(
                Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF"),
                "x",
                size,
                completed,
                DownloadInfo.Status.DOWNLOADING,
                DownloadPriority.AUTO,
                0,
                1,
                1,
                bytesPerSecond,
                PartMap.EMPTY));
    }

    private void whenTheCategoryIsShown(int category) throws Exception {
        FxTesting.onFx(() -> this.transfers.showCategory(category));
    }

    private void whenTheDownloadIsMovedTo(int category) throws Exception {
        FxTesting.onFx(() -> this.transfers.categorize(category));
    }

    private void whenTheIpFilterIsUpdatedFrom(String address) throws Exception {
        FxTesting.onFx(() -> {
            this.settings.ipFilterListsArea().setText(address);
            this.settings.updateIpFilter();
        });
    }

    private void whenTheSettingsAreApplied() throws Exception {
        FxTesting.onFx(this.settings::apply);
    }

    private void whenTheSettingsShowAgain() throws Exception {
        FxTesting.onFx(this.settings::shown);
    }

    private void whenTheServerListIsUpdatedFrom(String address) throws Exception {
        FxTesting.onFx(() -> {
            this.servers.listBox().node().getEditor().setText(address);
            this.servers.updateFromUrl();
        });
    }

    private void whenTheServerListAddressWorked(String address) throws Exception {
        FxTesting.onFx(() -> this.servers.listBox().worked(URI.create(address)));
    }

    private void whenTheServersAreAddedFromTheFile() throws Exception {
        FxTesting.onFx(() -> this.servers.addServerMet(this.serverMet));
    }

    private void whenTheServersAreFilteredBy(String text) throws Exception {
        FxTesting.onFx(() -> this.servers.filterField().setText(text));
    }

    private void whenTheServerFilterIsCleared() throws Exception {
        FxTesting.onFx(() -> this.servers.filterField().clear());
    }

    private void whenTheChosenServerIsPrioritized(ServerInfo.Priority priority) throws Exception {
        FxTesting.onFx(() -> this.servers.setPriority(priority));
    }

    private void whenTheLinksOfTheChosenServersAreCopied() throws Exception {
        FxTesting.onFx(this.servers::copyLinks);
    }

    private void whenTheServerLinksAreFoundIn(String text) {
        this.serverLinks = ServersScreen.serverLinks(text);
    }

    private void whenTheServerLinksArePasted() throws Exception {
        FxTesting.onFx(this.servers::pasteLinks);
    }

    private void whenAllServersAreRemoved() throws Exception {
        FxTesting.onFx(this.servers::removeAll);
    }

    private void whenTheServerIsChosen(int index) throws Exception {
        FxTesting.onFx(() -> this.servers.table().getSelectionModel().select(index));
    }

    private void whenTheChosenServerIsRemoved() throws Exception {
        FxTesting.onFx(this.servers::removeSelected);
    }

    private void whenConnectingToTheAddress(String address) throws Exception {
        FxTesting.onFx(() -> {
            this.servers.addressField().setText(address);
            this.servers.connectToAddress();
        });
    }

    private void whenTheSearchScreenShows() throws Exception {
        this.search = new SearchScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.search::view));
    }

    private void whenTheSearchIsStartedFor(String words) {
        this.exception = catchThrowable(() -> FxTesting.onFx(() -> {
            this.search.queryField().setText(words);
            this.search.start();
        }));
    }

    private void whenTheSearchScreenCloses() throws Exception {
        FxTesting.onFx(this.search::closing);
    }

    private void whenTheChosenSharedFilesArePrioritized(UploadPriority priority) throws Exception {
        FxTesting.onFx(() -> this.shared.prioritize(priority));
    }

    private void whenTheKadScreenShows() throws Exception {
        this.kadScreen = new KadScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.kadScreen::view));
    }

    private void whenKadIsConnectedByHand() throws Exception {
        FxTesting.onFx(this.test.model::connectKad);
        tickUntil(() -> this.test.model.statusProperty().get().kad().isPresent());
    }

    private void whenKadIsDisconnectedByHand() throws Exception {
        FxTesting.onFx(this.test.model::disconnectKad);
        tickUntil(() -> this.test.model.statusProperty().get().kad().isEmpty());
    }

    private void whenKadIsBootstrappedFrom(String node) throws Exception {
        FxTesting.onFx(() -> {
            this.kadScreen.nodeField().setText(node);
            this.kadScreen.bootstrap();
        });
    }

    private void whenKadIs(KadInfo info) {
        this.kad = info;
    }

    private void whenTheModelTicks() throws Exception {
        FxTesting.onFx(this.test.model::tick);
    }

    private void whenTheStatisticsAreRefreshed() throws Exception {
        FxTesting.onFx(() -> this.statistics.tree().refresh());
    }

    private void whenARuleIsAddedStartingAt(String start) throws Exception {
        FxTesting.onFx(() -> {
            this.scheduler.startField().setText(start);
            this.scheduler.add();
        });
    }

    private void whenTheRulesAreSaved() throws Exception {
        FxTesting.onFx(this.scheduler::save);
    }

    private void whenTheRulesAreLoaded() throws Exception {
        FxTesting.onFx(this.scheduler::load);
    }

    private void whenTheLanguagesOfTheLanguageBoxAreRead() throws Exception {
        this.languageNames = FxTesting.onFx(() -> {
            ComboBox<Optional<Locale>> box = this.settings.languageBox();
            ListView<Optional<Locale>> list = new ListView<>(box.getItems());
            List<String> names = new ArrayList<>();
            for (int i = 0; i < box.getItems().size(); i++) {
                ListCell<Optional<Locale>> cell = box.getCellFactory().call(list);
                cell.updateListView(list);
                cell.updateIndex(i);
                names.add(cell.getText());
            }
            return names;
        });
    }

    private void thenTheDownloadRowsBecome(int count) throws Exception {
        FxTesting.assertEventually(
                count + " download rows",
                () -> this.transfers.downloads().getItems().size() == count);
    }

    private void thenTheDownloadRowsAre(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.transfers.downloads().getItems().size()))
                .isEqualTo(count);
    }

    private void thenTheDownloadBecomes(DownloadInfo.Status status) throws Exception {
        FxTesting.assertEventually(
                status.toString(),
                () -> this.transfers.downloads().getItems().getFirst().get().status() == status);
    }

    private void thenTheDownloadPriorityBecomes(DownloadPriority priority) throws Exception {
        FxTesting.assertEventually(
                priority + " priority",
                () -> this.transfers.downloads().getItems().getFirst().get().priority() == priority);
    }

    private void thenTheClipboardStartsWith(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .startsWith(text);
    }

    private void thenTheSecondsLeftAre(long seconds) {
        assertThat(this.timeLeft).isEqualTo(seconds);
    }

    private void thenTheDownloadIsInTheCategory(int category) throws Exception {
        assertThat(FxTesting.onFx(() ->
                        this.transfers.downloads().getItems().getFirst().get().category()))
                .as("its name matches the second category")
                .isEqualTo(category);
    }

    private void thenTheCategoryShownHasNoDownload() throws Exception {
        FxTesting.assertEventually(
                "out of the category",
                () -> this.transfers.downloads().getItems().isEmpty());
        FxTesting.onFx(() -> {
            FxTesting.snapshot(this.transfers.view(), "transfers-categories");
            return null;
        });
    }

    private void thenTheUserIsTold(String message) {
        assertThat(this.test.told).contains(message);
    }

    private void thenTheUserIsToldSomethingAbout(String text) {
        assertThat(this.test.told).anyMatch(m -> m.contains(text));
    }

    private void thenTheUserIsToldItWasNotSaved(String start) {
        assertThat(this.test.told).anyMatch(m -> m.startsWith(start));
    }

    private void thenTheUserIsToldNothing() {
        assertThat(this.test.told).isEmpty();
    }

    private void thenTheUserIsToldWhy() throws Exception {
        FxTesting.assertEventually("a refusal", () -> !this.test.told.isEmpty());
    }

    private void thenTheUserIsToldAtLeast(int messages) throws Exception {
        FxTesting.assertEventually(messages + " messages", () -> this.test.told.size() >= messages);
    }

    private void thenTheUserIsToldTheAddressIsLocalOrPrivate() throws Exception {
        FxTesting.assertEventually(
                "refused", () -> this.test.told.stream().anyMatch(m -> m.contains("local or private")));
    }

    private void thenNoIpFilterAddressIsRemembered() {
        assertThat(this.test.gui.settings().history("ipFilter")).isEmpty();
    }

    private void thenNoServerListAddressIsRemembered() {
        assertThat(this.test.gui.settings().history("serverMet"))
                .as("a failed address is not kept")
                .isEmpty();
    }

    private void thenTheServerListBoxOffers(String... addresses) throws Exception {
        assertThat(FxTesting.onFx(() -> this.servers.listBox().node().getItems()))
                .containsExactly(addresses);
    }

    private void thenTheSavedServerListAddressesAre(String... addresses) {
        assertThat(GuiSettings.load(this.test.engine.directories().gui()).history("serverMet"))
                .containsExactly(addresses);
    }

    private void thenTheServerRowsBecome(int count) throws Exception {
        FxTesting.assertEventually(
                count + " servers", () -> this.servers.table().getItems().size() == count);
    }

    private void thenTheServerRowsAre(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.servers.table().getItems().size())).isEqualTo(count);
    }

    private void thenTheServerCountReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.servers.countLabel().getText())).isEqualTo(text);
    }

    private void thenTheHiddenServerColumnsAre(String... titles) throws Exception {
        assertThat(FxTesting.onFx(() -> this.servers.table().getColumns().stream()
                        .filter(c -> !c.isVisible())
                        .map(c -> c.getText())
                        .toList()))
                .containsExactly(titles);
    }

    private void thenTheFirstServerBecomesStaticWith(ServerInfo.Priority priority) throws Exception {
        String firstAddress = firstServerAddress();
        FxTesting.assertEventually(
                "static and " + priority,
                () -> this.servers.table().getItems().stream()
                        .map(r -> r.get())
                        .anyMatch(s -> s.address().equals(firstAddress) && s.isStatic() && s.priority() == priority));
    }

    private void thenTheStaticServersAreSaved() {
        assertThat(Files.exists(this.test.engine.directories().config().resolve("staticservers.dat")))
                .isTrue();
    }

    private void thenTheClipboardHoldsTheLinkOfTheFirstServer() throws Exception {
        String firstAddress = firstServerAddress();
        assertThat(FxTesting.onFx(() -> Clipboard.getSystemClipboard().getString()))
                .isEqualTo("ed2k://|server|" + firstAddress.replace(":", "|") + "|/");
    }

    private void thenTheServerLinksFoundAre(int count) {
        assertThat(this.serverLinks).hasSize(count);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenNoSearchTabIsOpen() throws Exception {
        assertThat(FxTesting.onFx(() -> this.search.tabs().getTabs().size())).isZero();
    }

    private void thenTheSearchTabsBecome(int count) throws Exception {
        FxTesting.assertEventually(
                "the kept tab", () -> this.search.tabs().getTabs().size() == count);
    }

    private void thenTheFirstSearchTabReads(String title) throws Exception {
        assertThat(FxTesting.onFx(() -> this.search.tabs().getTabs().getFirst().getText()))
                .isEqualTo(title);
    }

    private void thenNoSearchIsKept() throws Exception {
        assertThat(this.test.engine.savedSearches().get(10, TimeUnit.SECONDS))
                .as("the tabs closed before the exit are not kept")
                .isEmpty();
    }

    private void thenTheFirstSharedFilePriorityBecomes(UploadPriority priority) throws Exception {
        FxTesting.assertEventually(
                "the " + priority + " priority",
                () -> this.shared.table().getItems().getFirst().get().priority() == priority);
    }

    private void thenTheEngineCanRecheckTheFirewall() throws Exception {
        assertThat(this.test.engine.recheckKadFirewall().get(10, TimeUnit.SECONDS))
                .isTrue();
    }

    private void thenKadIsOff() {
        assertThat(this.test.model.statusProperty().get().kad()).isEmpty();
    }

    private void thenTheKadStateReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.kadScreen.stateLabel().getText())).isEqualTo(text);
    }

    private void thenKadReads(String udp, String buddy) {
        assertThat(KadScreen.udpState(this.test.gui, this.kad)).isEqualTo(udp);
        assertThat(KadScreen.buddyState(this.test.gui, this.kad)).isEqualTo(buddy);
    }

    private void thenTheDownloadedTotalReads(String text) throws Exception {
        FxTesting.assertEventually(
                "totals", () -> this.statistics.downloadedLabel().getText().equals(text));
        assertThat(FxTesting.onFx(() -> this.statistics.downloadedLabel().getText()))
                .isEqualTo(text);
    }

    private void thenTheStatisticsSectionsStartWith(String... sections) throws Exception {
        assertThat(statisticsSections()).startsWith(sections);
    }

    private void thenTheStatisticsSectionsInclude(String... sections) throws Exception {
        assertThat(statisticsSections()).contains(sections);
    }

    private void thenTheFirstStatisticsSectionIsOpen() throws Exception {
        TreeItem<String> transfersSection = FxTesting.onFx(
                () -> this.statistics.tree().view().getRoot().getChildren().getFirst());
        assertThat(FxTesting.onFx(transfersSection::isExpanded)).isTrue();
    }

    private void thenTheLastStatisticsSectionHoldsTempAndIncoming() throws Exception {
        assertThat(FxTesting.onFx(() -> this.statistics
                        .tree()
                        .view()
                        .getRoot()
                        .getChildren()
                        .getLast()
                        .getChildren()
                        .size()))
                .as("temp and incoming")
                .isEqualTo(2);
    }

    private void thenTheSameFirstStatisticsSectionStaysClosed() throws Exception {
        assertThat(FxTesting.onFx(() ->
                        this.statistics.tree().view().getRoot().getChildren().getFirst()))
                .as("the same item, still closed")
                .isSameAs(this.closedSection)
                .satisfies(t -> assertThat(t.isExpanded()).isFalse());
        FxTesting.onFx(() -> {
            FxTesting.snapshot(this.statistics.view(), "statistics-alone");
            return null;
        });
    }

    private void thenTheRulesListedAre(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.scheduler.rulesTable().getItems().size()))
                .isEqualTo(count);
    }

    private void thenARuleApplies() throws Exception {
        FxTesting.assertEventually(
                "the rule applies", () -> this.test.engine.activeRule().isPresent());
    }

    private void thenTheFirstRuleIsNamed(String name) {
        assertThat(this.test.engine.schedule().rules().getFirst().name()).isEqualTo(name);
    }

    private void thenTheActiveRuleShows(String text) throws Exception {
        FxTesting.assertEventually(
                "shown", () -> this.scheduler.activeLabel().getText().equals(text));
    }

    private void thenNoRuleIsListed() throws Exception {
        assertThat(FxTesting.onFx(() -> this.scheduler.rulesTable().getItems().isEmpty()))
                .isTrue();
    }

    private void thenTheSettingsAreSaved() throws Exception {
        FxTesting.assertEventually("saved", () -> this.test.told.contains("Settings saved."));
    }

    private void thenTheNickIs(String nick) {
        assertThat(this.test.engine.preferences().nick()).isEqualTo(nick);
    }

    private void thenTheThemeIsDark() throws Exception {
        assertThat(FxTesting.onFx(() -> this.test.gui.themes().darkProperty().get()))
                .isTrue();
    }

    private void thenTheSavedModeIs(GuiSettings.Mode mode) {
        assertThat(GuiSettings.load(this.test.engine.directories().gui()).mode())
                .isEqualTo(mode);
    }

    private void thenTheProxyHostBecomes(String host) throws Exception {
        FxTesting.assertEventually(
                "the proxy", () -> this.test.engine.preferences().proxy().host().equals(host));
    }

    private void thenNoProxyIsUsed() throws Exception {
        FxTesting.assertEventually(
                "no proxy", () -> !this.test.engine.preferences().proxy().isEnabled());
    }

    private void thenTheRelaunchesAre(int count) {
        assertThat(this.test.relaunches()).isEqualTo(count);
    }

    private void thenNoWebInterfaceSettingIsSaved() {
        assertThat(WebUiSettings.load(config())).isEqualTo(WebUiSettings.DEFAULTS);
    }

    private void thenTheWebInterfaceIsSavedOnPort(int port) {
        WebUiSettings saved = WebUiSettings.load(config());
        assertThat(saved.usable()).isTrue();
        assertThat(saved.port()).isEqualTo(port);
    }

    private void thenTheWebInterfacePasswordBoxIsEmpty() throws Exception {
        assertThat(FxTesting.onFx(() -> this.settings.webUiPasswordField().getText()))
                .isEmpty();
    }

    private void thenTheSavedWebInterfaceProxiesAre(String... proxies) {
        assertThat(WebUiSettings.load(config()).proxies()).containsExactly(proxies);
    }

    private void thenTheSavedWebInterfaceHostNamesAre(String... names) {
        assertThat(WebUiSettings.load(config()).hosts()).containsExactly(names);
    }

    private void thenTheWebInterfaceHostNamesReadAfterReset(String names) throws Exception {
        FxTesting.onFx(this.settings::shown);
        assertThat(FxTesting.onFx(() -> this.settings.webUiHostsField().getText()))
                .isEqualTo(names);
    }

    private void thenTheWebInterfacePortReads(String port) throws Exception {
        assertThat(FxTesting.onFx(() -> this.settings.webUiPortField().getText()))
                .isEqualTo(port);
    }

    private void thenTheEngineDownloads(String... names) throws Exception {
        assertThat(this.test.engine.downloads().get())
                .extracting(DownloadInfo::name)
                .containsExactlyInAnyOrder(names);
    }

    private void thenTheSystemLanguageComesFirst() {
        assertThat(this.languageNames).first().isEqualTo("As the system");
    }

    private void thenEveryLanguageFollowsByItsOwnName() {
        assertThat(this.languageNames.subList(1, this.languageNames.size()))
                .isEqualTo(I18n.LANGUAGES.stream().map(l -> I18n.of(l).name()).toList())
                .doesNotHaveDuplicates()
                .noneMatch(name -> name.startsWith("["));
    }

    private String firstServerAddress() throws Exception {
        return FxTesting.onFx(
                () -> this.servers.table().getItems().getFirst().get().address());
    }

    private List<String> statisticsSections() throws Exception {
        return FxTesting.onFx(() -> this.statistics.tree().view().getRoot().getChildren().stream()
                .map(TreeItem::getValue)
                .toList());
    }

    private Path config() {
        return this.test.engine.directories().config();
    }

    // The model learns the engine's state on its ticks, which these tests drive.
    private void tickUntil(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 80 && !done.getAsBoolean(); i++) {
            FxTesting.onFx(this.test.model::tick);
            Thread.sleep(250);
        }
    }
}
