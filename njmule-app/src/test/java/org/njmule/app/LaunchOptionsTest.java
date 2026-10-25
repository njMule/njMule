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
package org.njmule.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.app.LaunchOptions.Action;
import org.njmule.app.LaunchOptions.UsageException;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ServerAddress;

class LaunchOptionsTest {

    private static final String FILE_LINK = "ed2k://|file|a.iso|10|" + "ab".repeat(16) + "|/";
    // No file system takes a NUL character in a name.
    private static final String NUL_NAME = "a\u0000b";

    @TempDir
    Path dir;

    private LaunchOptions options;
    private Throwable exception;
    private String help;

    @Test
    void shouldRunByDefault() {
        whenTheArgumentsAreParsed();

        thenTheActionIs(Action.RUN);
        thenThereIsNoConfigDir();
        thenThereIsNoLogLevel();
        thenThereIsNoWebInterfaceSetup();
    }

    @Test
    void shouldParseTheServiceInstallationWithTheWebInterfaceOptions() {
        whenTheArgumentsAreParsed("--service=install", "--webui-lan", "--webui-password");

        thenTheActionIs(Action.SERVICE);
        thenTheServiceActionIs(LaunchOptions.ServiceAction.INSTALL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--webui-lan", "--config-dir=.", "--headless"})
    void shouldRefuseOtherOptionsWithAServiceCommand(String option) {
        whenTheArgumentsAreParsed("--service=start", option);

        thenExceptionOccurred(UsageException.class, "cannot be combined");
    }

    @Test
    void shouldTellTheServicesProfileInTheHelp() {
        whenTheHelpIsRead();

        thenTheHelpTells("in %ProgramData%\\njMule as the virtual account");
    }

    @Test
    void shouldRefuseAnUnknownServiceCommand() {
        whenTheArgumentsAreParsed("--service=restart");

        thenExceptionOccurred(UsageException.class, "install, uninstall, start, stop or status");
    }

    @Test
    void shouldRefuseTheServiceOutputWithoutAServiceCommand() {
        whenTheArgumentsAreParsed("--service-output", "said.txt");

        thenExceptionOccurred(UsageException.class, "--service-output applies to --service");
    }

    @Test
    void shouldParseTheConfigDirAndTheLogLevel() {
        whenTheArgumentsAreParsed("--config-dir", this.dir.toString(), "--log-level", "debug");

        thenTheConfigDirIsTheTempFolder();
        thenTheLogLevelIs("DEBUG");
    }

    @Test
    void shouldParseAConfigDirJoinedToItsOption() {
        whenTheArgumentsAreParsed("--config-dir=" + this.dir);

        thenTheConfigDirIsTheTempFolder();
    }

    @ParameterizedTest
    @CsvSource({"-h, HELP", "-V, VERSION", "--license, LICENSE", "--warranty, WARRANTY", "--notice, NOTICE"})
    void shouldParseTheOptionOfAnAction(String option, Action action) {
        whenTheArgumentsAreParsed(option);

        thenTheActionIs(action);
    }

    @Test
    void shouldCollectTheFilesToHash() {
        whenTheArgumentsAreParsed("--hash", "a.iso", "b.iso", "--log-level", "info");

        thenTheActionIs(Action.HASH);
        thenTheFilesToHashAre(Path.of("a.iso"), Path.of("b.iso"));
    }

    @Test
    void shouldRefuseAHashWithoutFiles() {
        whenTheArgumentsAreParsed("--hash");

        thenExceptionOccurred(UsageException.class, "--hash");
    }

    @Test
    void shouldCollectTheNetworkTasks() {
        whenTheArgumentsAreParsed(
                "--headless",
                "--add-servers",
                "server.met",
                "--connect-server",
                "1.2.3.4:4661",
                "--search",
                "ubuntu iso",
                "--global");

        thenTheActionIs(Action.RUN);
        thenItRunsHeadless();
        thenTheServersToAddComeFrom(Path.of("server.met"));
        thenTheServerToConnectToIs(new ServerAddress("1.2.3.4", 4661));
        thenTheSearchIs("ubuntu iso");
        thenTheSearchIsGlobal();
        thenTheRunIsOneShot(true);
    }

    @Test
    void shouldEndTheRunAfterAHello() {
        whenTheArgumentsAreParsed("--hello", "localhost:61426");

        thenTheHelloGoesTo(new ServerAddress("localhost", 61426));
        thenTheRunIsOneShot(true);
    }

    @Test
    void shouldKeepRunningOnceConnectedToAServer() {
        whenTheArgumentsAreParsed("--connect-server", "s.example:4242");

        thenTheRunIsOneShot(false);
    }

    @Test
    void shouldCollectADownloadWithItsSources() {
        whenTheArgumentsAreParsed("--download", FILE_LINK + "|sources,127.0.0.1:61426|/");

        thenTheNumberOfDownloadsIs(1);
        thenTheNumberOfSourcesOfTheFirstDownloadIs(1);
        thenTheRunIsOneShot(true);
    }

    @Test
    void shouldCollectAKadSearchAndItsBootstrap() {
        whenTheArgumentsAreParsed("--kad-bootstrap", "127.0.0.1:14897", "--search", "x", "--kad");

        thenTheKadBootstrapIs(new ServerAddress("127.0.0.1", 14897));
        thenTheSearchGoesThroughKad();
    }

    @Test
    void shouldRefuseKadWithoutASearch() {
        whenTheArgumentsAreParsed("--kad");

        thenExceptionOccurred(UsageException.class, "--kad applies to --search");
    }

    @Test
    void shouldRefuseKadWithAGlobalSearch() {
        whenTheArgumentsAreParsed("--search", "x", "--global", "--kad");

        thenExceptionOccurred(UsageException.class);
        thenTheMessageIs("--kad applies to --search, without --global");
    }

    @Test
    void shouldShareFoldersWithoutEndingTheRun() {
        whenTheArgumentsAreParsed("--share", "videos", "music");

        thenTheSharedFoldersAre("videos", "music");
        thenTheRunIsOneShot(false);
    }

    @Test
    void shouldRefuseADownloadOfALinkThatIsNotAFile() {
        whenTheArgumentsAreParsed("--download", "ed2k://|server|1.2.3.4|4661|/");

        thenExceptionOccurred(UsageException.class, "Not an ed2k file link");
    }

    @Test
    void shouldRefuseAServerAddressThatCannotBeRead() {
        whenTheArgumentsAreParsed("--connect-server", "nowhere");

        thenExceptionOccurred(UsageException.class, "Invalid address for --connect-server");
    }

    @Test
    void shouldRefuseGlobalWithoutASearch() {
        whenTheArgumentsAreParsed("--global");

        thenExceptionOccurred(UsageException.class, "--global applies to --search");
    }

    @Test
    void shouldTakeALinkForTheRunningInstanceAsAnArgument() {
        whenTheArgumentsAreParsed(FILE_LINK, "--log-level", "info");

        thenTheActionIs(Action.RUN);
        thenTheOnlyLinkIs(FILE_LINK);
        thenTheLinksDoNotEndTheRun();
    }

    @Test
    void shouldTakeAServerListLinkForTheRunningInstanceToFollow() {
        whenTheArgumentsAreParsed("ed2k://|serverlist|https://example.org/server.met|/");

        thenTheOnlyLinkIsAServerList();
    }

    @Test
    void shouldRefuseALinkThatCannotBeRead() {
        whenTheArgumentsAreParsed("ed2k://nothing");

        thenExceptionOccurred(UsageException.class);
    }

    @Test
    void shouldCollectAnEmuleImport() {
        whenTheArgumentsAreParsed("--import-emule", this.dir.toString(), "--dry-run", "--import-downloads");

        thenTheImportIsOfTheTempFolder();
        thenTheImportOptionsAre(new ImportOptions(true, false, true));
        thenTheRunIsOneShot(true);
    }

    @Test
    void shouldCollectAnImportThatAdoptsTheEmuleIdentity() {
        whenTheArgumentsAreParsed("--import-emule", "x", "--adopt-emule-identity");

        thenTheImportOptionsAre(new ImportOptions(false, true, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--dry-run", "--adopt-emule-identity", "--import-downloads"})
    void shouldRefuseAnImportOptionWithoutTheImport(String option) {
        whenTheArgumentsAreParsed(option);

        thenExceptionOccurred(UsageException.class, "apply to --import-emule");
    }

    @Test
    void shouldRefuseAnUnknownOption() {
        whenTheArgumentsAreParsed("--bogus");

        thenExceptionOccurred(UsageException.class, "Unknown option");
        thenTheMessageContains("--bogus");
    }

    @Test
    void shouldRefuseAConfigDirWithoutItsFolder() {
        whenTheArgumentsAreParsed("--config-dir");

        thenExceptionOccurred(UsageException.class, "--config-dir");
    }

    @Test
    void shouldRefuseAnOptionInPlaceOfTheLogLevel() {
        whenTheArgumentsAreParsed("--log-level", "--help");

        thenExceptionOccurred(UsageException.class, "--log-level");
    }

    @Test
    void shouldRefuseAnUnknownLogLevel() {
        whenTheArgumentsAreParsed("--log-level", "loud");

        thenExceptionOccurred(UsageException.class);
        thenTheMessageIs("Invalid log level: loud");
    }

    @ParameterizedTest
    @CsvSource({"--config-dir, directory", "--share, directory", "--add-servers, file", "--hash, file"})
    void shouldRefuseAPathTheFileSystemCannotName(String option, String kind) {
        whenTheArgumentsAreParsed(option, NUL_NAME);

        thenExceptionOccurred(UsageException.class);
        thenTheMessageIs("Invalid " + kind + ": " + NUL_NAME);
    }

    @Test
    void shouldParseExit() {
        whenTheArgumentsAreParsed("--exit");

        thenTheActionIs(Action.EXIT);
    }

    @Test
    void shouldParseExitWithWhereAndHowToRun() {
        whenTheArgumentsAreParsed("--exit", "--config-dir", this.dir.toString(), "--log-level", "info", "--headless");

        thenTheActionIs(Action.EXIT);
    }

    @ParameterizedTest
    @CsvSource({"--help, HELP", "--version, VERSION"})
    void shouldPutHelpAndVersionBeforeExit(String option, Action action) {
        whenTheArgumentsAreParsed("--exit", option);

        thenTheActionIs(action);
    }

    @ParameterizedTest
    @MethodSource("exitWithSomethingElseToDo")
    void shouldRefuseExitWithSomethingElseToDo(String[] args) {
        whenTheArgumentsAreParsed(args);

        thenExceptionOccurred(UsageException.class, "--exit");
    }

    @Test
    void shouldParseTheCheckForANewVersion() {
        whenTheArgumentsAreParsed("--check-update", "--config-dir", this.dir.toString());

        thenTheActionIs(Action.CHECK_UPDATE);
    }

    @Test
    void shouldParseTheUpdate() {
        whenTheArgumentsAreParsed("--update", "--config-dir", this.dir.toString());

        thenTheActionIs(Action.UPDATE);
    }

    @Test
    void shouldRefuseTheUpdateWithLinks() {
        whenTheArgumentsAreParsed("--update", "ed2k://|server|203.0.113.10|4661|/");

        thenExceptionOccurred(UsageException.class, "--update cannot be combined with links");
    }

    @Test
    void shouldParseTheInstallationHandedOverWithTheProcessesToWaitFor() {
        whenTheArgumentsAreParsed("--apply-update", "apply.properties", "--wait", "4242", "--wait", "77");

        thenTheActionIs(Action.APPLY_UPDATE);
        thenThePlanIs(Path.of("apply.properties"));
        thenItWaitsFor(4242L, 77L);
    }

    @Test
    void shouldWaitForAnOldProcessBeforeARun() {
        whenTheArgumentsAreParsed("--wait", "4242", "--config-dir", this.dir.toString());

        thenTheActionIs(Action.RUN);
        thenItWaitsFor(4242L);
    }

    @Test
    void shouldRefuseAProcessToWaitForThatIsNone() {
        whenTheArgumentsAreParsed("--wait", "-3");

        thenExceptionOccurred(UsageException.class, "Invalid process for --wait: -3");
    }

    @Test
    void shouldKeepTheHiddenOptionsOutOfTheHelp() {
        whenTheHelpIsRead();

        thenTheHelpTells("--update");
        thenTheHelpDoesNotTell("--apply-update");
        thenTheHelpDoesNotTell("--wait");
    }

    @Test
    void shouldRefuseTheCheckForANewVersionWithSomethingElseToDo() {
        whenTheArgumentsAreParsed("--check-update", "--search", "linux");

        thenExceptionOccurred(UsageException.class, "--check-update");
    }

    @Test
    void shouldParseTheWebInterfacePasswordAndItsPort() {
        whenTheArgumentsAreParsed("--webui-password", "--webui-port", "8080");

        thenTheActionIs(Action.WEBUI_PASSWORD);
        thenTheWebInterfaceSetupIs(
                new LaunchOptions.WebUiSetup(Optional.of(8080), false, false, Optional.empty(), List.of()));
    }

    @Test
    void shouldParseTheWebInterfacePasswordForTheLan() {
        whenTheArgumentsAreParsed("--webui-password", "--webui-lan");

        thenTheWebInterfaceSetupIs(
                new LaunchOptions.WebUiSetup(Optional.empty(), true, false, Optional.empty(), List.of()));
    }

    @Test
    void shouldParseAWebInterfacePasswordFileForEveryStart() {
        whenTheArgumentsAreParsed("--webui-password-file", "secret", "--webui-lan", "--webui-api");

        thenTheActionIs(Action.RUN);
        thenTheWebInterfaceSetupIs(
                new LaunchOptions.WebUiSetup(Optional.empty(), true, true, Optional.of(Path.of("secret")), List.of()));
    }

    @Test
    void shouldParseAWebInterfacePasswordFileInsteadOfAsking() {
        whenTheArgumentsAreParsed("--webui-password", "--webui-password-file", "secret");

        thenTheActionIs(Action.WEBUI_PASSWORD);
        thenTheWebInterfaceSetupIs(new LaunchOptions.WebUiSetup(
                Optional.empty(), false, false, Optional.of(Path.of("secret")), List.of()));
    }

    @Test
    void shouldRefuseTheWebInterfacePortWithoutThePassword() {
        whenTheArgumentsAreParsed("--webui-port", "8080");

        thenExceptionOccurred(UsageException.class, "apply to --webui-password");
    }

    @Test
    void shouldRefuseTheWebInterfaceLanWithoutThePassword() {
        whenTheArgumentsAreParsed("--webui-lan");

        thenExceptionOccurred(UsageException.class, "apply to --webui-password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"65536", "0", "x"})
    void shouldRefuseAnInvalidWebInterfacePort(String port) {
        whenTheArgumentsAreParsed("--webui-password", "--webui-port", port);

        thenExceptionOccurred(UsageException.class);
        thenTheMessageIs("Invalid port for --webui-port: " + port);
    }

    @Test
    void shouldParseTheHostNamesOfTheWebInterfaceRepeatedOrSeparatedByCommas() {
        whenTheArgumentsAreParsed(
                "--webui-password-file",
                "secret",
                "--webui-host",
                "NAS.local",
                "--webui-host",
                "njmule.example.com,fe80::1");

        thenTheWebInterfaceSetupIs(new LaunchOptions.WebUiSetup(
                Optional.empty(),
                false,
                false,
                Optional.of(Path.of("secret")),
                List.of("nas.local", "njmule.example.com", "[fe80::1]")));
    }

    @Test
    void shouldParseTheTrustedProxiesOfTheWebInterfaceRepeatedOrSeparatedByCommas() {
        whenTheArgumentsAreParsed(
                "--webui-password-file", "secret", "--webui-proxy", "172.17.0.0/16", "--webui-proxy", "::1,10.0.0.5");

        thenTheWebInterfaceSetupIs(new LaunchOptions.WebUiSetup(
                Optional.empty(),
                false,
                false,
                Optional.of(Path.of("secret")),
                List.of(),
                List.of("172.17.0.0/16", "::1", "10.0.0.5")));
    }

    @Test
    void shouldRefuseAProxyNamedByItsHostName() {
        whenTheArgumentsAreParsed("--webui-password", "--webui-proxy", "proxy.example");

        thenExceptionOccurred(UsageException.class);
        thenTheMessageIs("Invalid proxy for --webui-proxy: proxy.example");
    }

    @Test
    void shouldRefuseTheTrustedProxiesWithoutThePassword() {
        whenTheArgumentsAreParsed("--webui-proxy", "172.17.0.1");

        thenExceptionOccurred(UsageException.class, "apply to --webui-password");
    }

    @Test
    void shouldRefuseTheWebInterfaceHostNamesWithoutThePassword() {
        whenTheArgumentsAreParsed("--webui-host", "nas.local");

        thenExceptionOccurred(UsageException.class, "apply to --webui-password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nas.local:4711", "https://njmule.example.com", "[nas.local]", "bad name"})
    void shouldRefuseAWebInterfaceHostWithAPortASchemeOrNoHostName(String name) {
        whenTheArgumentsAreParsed("--webui-password", "--webui-host", name);

        thenExceptionOccurred(UsageException.class);
        thenTheMessageIs("Invalid host name for --webui-host: " + name);
    }

    static Stream<Arguments> exitWithSomethingElseToDo() {
        return Stream.of(
                        new String[] {"--exit", FILE_LINK},
                        new String[] {"--webui-password", "--exit"},
                        new String[] {"--exit", "--webui-password-file", "secret"},
                        new String[] {"--exit", "--hash", "a.iso"},
                        new String[] {"--exit", "--license"},
                        new String[] {"--exit", "--search", "linux"},
                        new String[] {"--exit", "--connect-server", "1.2.3.4:4661"},
                        new String[] {"--exit", "--import-emule", "x"})
                .map(args -> arguments((Object) args));
    }

    private void whenTheArgumentsAreParsed(String... args) {
        this.exception = catchThrowable(() -> this.options = LaunchOptions.parse(args));
    }

    private void whenTheHelpIsRead() {
        this.help = LaunchOptions.usage();
    }

    private void thenThePlanIs(Path expected) {
        assertThat(parsed().applyUpdate()).contains(expected);
    }

    private void thenItWaitsFor(Long... pids) {
        assertThat(parsed().waitFor()).containsExactly(pids);
    }

    private void thenTheHelpTells(String option) {
        assertThat(this.help).contains(option);
    }

    private void thenTheHelpDoesNotTell(String option) {
        assertThat(this.help).doesNotContain(option);
    }

    private void thenTheActionIs(Action expected) {
        assertThat(parsed().action()).isEqualTo(expected);
    }

    private void thenTheServiceActionIs(LaunchOptions.ServiceAction expected) {
        assertThat(this.options.service())
                .map(LaunchOptions.ServiceTask::action)
                .hasValue(expected);
    }

    private void thenThereIsNoConfigDir() {
        assertThat(parsed().configDir()).isEmpty();
    }

    private void thenThereIsNoLogLevel() {
        assertThat(parsed().logLevel()).isEmpty();
    }

    private void thenThereIsNoWebInterfaceSetup() {
        assertThat(parsed().webUi()).isEmpty();
    }

    private void thenTheConfigDirIsTheTempFolder() {
        assertThat(parsed().configDir()).contains(this.dir.toAbsolutePath().normalize());
    }

    private void thenTheLogLevelIs(String expected) {
        assertThat(parsed().logLevel()).contains(expected);
    }

    private void thenTheFilesToHashAre(Path... expected) {
        assertThat(parsed().files()).containsExactly(expected);
    }

    private void thenItRunsHeadless() {
        assertThat(parsed().headless()).isTrue();
    }

    private void thenTheServersToAddComeFrom(Path serverMet) {
        assertThat(parsed().network().serverMet()).contains(serverMet);
    }

    private void thenTheServerToConnectToIs(ServerAddress expected) {
        assertThat(parsed().network().server()).contains(expected);
    }

    private void thenTheSearchIs(String expected) {
        assertThat(parsed().network().search()).contains(expected);
    }

    private void thenTheSearchIsGlobal() {
        assertThat(parsed().network().global()).isTrue();
    }

    private void thenTheRunIsOneShot(boolean expected) {
        assertThat(parsed().network().isOneShot()).isEqualTo(expected);
    }

    private void thenTheHelloGoesTo(ServerAddress expected) {
        assertThat(parsed().network().hello()).contains(expected);
    }

    private void thenTheNumberOfDownloadsIs(int expected) {
        assertThat(parsed().network().downloads()).hasSize(expected);
    }

    private void thenTheNumberOfSourcesOfTheFirstDownloadIs(int expected) {
        assertThat(parsed().network().downloads().getFirst().sources()).hasSize(expected);
    }

    private void thenTheKadBootstrapIs(ServerAddress expected) {
        assertThat(parsed().network().kadBootstrap()).contains(expected);
    }

    private void thenTheSearchGoesThroughKad() {
        assertThat(parsed().network().kad()).isTrue();
    }

    private void thenTheSharedFoldersAre(String... folders) {
        assertThat(parsed().network().shares())
                .containsExactlyElementsOf(Stream.of(folders)
                        .map(folder -> Path.of(folder).toAbsolutePath().normalize())
                        .toList());
    }

    private void thenTheOnlyLinkIs(String link) {
        assertThat(parsed().links()).containsExactly(Ed2kLink.parse(link));
    }

    private void thenTheLinksDoNotEndTheRun() {
        assertThat(parsed().network().isOneShot())
                .as("the links are not a download that ends the run")
                .isFalse();
    }

    private void thenTheOnlyLinkIsAServerList() {
        assertThat(parsed().links())
                .as("server list links too, for the instance to follow")
                .singleElement()
                .isInstanceOf(Ed2kLink.ServerListLink.class);
    }

    private void thenTheImportIsOfTheTempFolder() {
        assertThat(parsed().network().emuleImport().orElseThrow().dir())
                .isEqualTo(this.dir.toAbsolutePath().normalize());
    }

    private void thenTheImportOptionsAre(ImportOptions expected) {
        assertThat(parsed().network().emuleImport().orElseThrow().options()).isEqualTo(expected);
    }

    private void thenTheWebInterfaceSetupIs(LaunchOptions.WebUiSetup expected) {
        assertThat(parsed().webUi()).contains(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String messagePart) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(messagePart);
    }

    private void thenTheMessageIs(String expected) {
        assertThat(this.exception).hasMessage(expected);
    }

    private void thenTheMessageContains(String part) {
        assertThat(this.exception).hasMessageContaining(part);
    }

    private LaunchOptions parsed() {
        assertThat(this.exception).as("the arguments are parsed").isNull();
        return this.options;
    }
}
