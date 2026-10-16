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

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.app.logging.LoggingSetup;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.webui.Passwords;
import org.njmule.webui.WebUiSettings;
import org.slf4j.LoggerFactory;

@Timeout(30)
class MainTest {

    private static final String HANDOFF_FILE = "config/" + LinkHandoff.FILE_NAME;
    private static final String PASSWORD_FILE = "webui-password";
    // Lists are never fetched from this computer, and ::1 has no IPv4 address: no request leaves it.
    private static final String SERVER_LIST = "ed2k://|serverlist|http://127.0.0.1:9/server.met|/";
    private static final String NODES_LIST = "ed2k://|nodeslist|http://127.0.0.1:9/nodes.dat|/";
    private static final String SEARCH = "ed2k://|search|linux|/";

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final ByteArrayOutputStream secondOut = new ByteArrayOutputStream();
    private List<String> typed;
    private int exitCode;
    // An instance running in the background until its stop completes.
    private CompletableFuture<Void> stop;
    private CompletableFuture<Integer> exit;
    private long deadline;
    private NjMuleEngine engine;
    private AppDirectories directories;
    private Logged logged;
    private ServerSocket busy;
    private ServerSocket silentPeer;
    private Socket accepted;
    private Thread command;
    private final AtomicInteger commandCode = new AtomicInteger(-1);
    private Thread running;
    private final AtomicInteger runCode = new AtomicInteger(-1);
    private InputStream originalIn;
    private int freePort;
    private HttpResponse<String> login;
    private WebUiSettings settings;
    private String printed;

    // A one-shot run lowers the console log to its errors: the tests that read logs must not depend on the order.
    @AfterEach
    void closeWhatIsStillOpenAndRestoreTheLogLevel() throws IOException, InterruptedException {
        LoggingSetup.setLevel("INFO");
        if (this.stop != null) {
            this.stop.complete(null);
        }
        if (this.running != null) {
            this.running.join(Duration.ofSeconds(20));
        }
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.logged != null) {
            this.logged.close();
        }
        if (this.accepted != null) {
            this.accepted.close();
        }
        if (this.silentPeer != null) {
            this.silentPeer.close();
        }
        if (this.busy != null) {
            this.busy.close();
        }
        if (this.originalIn != null) {
            System.setIn(this.originalIn);
        }
    }

    @Test
    void shouldPrintTheVersion() {
        whenRunWith("--version");

        thenTheExitCodeIs(0);
        thenTheOutputContains(NjMuleInfo.version(), "GPL-3.0-or-later");
    }

    @ParameterizedTest
    @CsvSource({
        "--license, GNU GENERAL PUBLIC LICENSE",
        "--warranty, Disclaimer of Warranty",
        "--notice, Third-party components",
        "--help, --config-dir"
    })
    void shouldPrintTheTextAnOptionAsksFor(String option, String text) {
        whenRunWith(option);

        thenTheExitCodeIs(0);
        thenTheOutputContains(text);
    }

    @Test
    void shouldExitWithTheUsageOnInvalidArguments() {
        whenRunWith("--nope");

        thenTheExitCodeIs(Main.EXIT_USAGE);
        thenTheErrorsContain("Unknown option", "Usage:");
    }

    @Test
    void shouldPrintTheLinkOfAFileToHash() throws IOException {
        givenTheFile("abc.txt", "abc");

        whenTheFileIsHashed("abc.txt");

        thenTheExitCodeIs(0);
        thenTheOutputContains(
                "ed2k://|file|abc.txt|3|A448017AAF21D8525FC10AE87AA6729D|h=VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5|/",
                "3 bytes, 0 part hashes");
    }

    @Test
    void shouldFailToHashAMissingFile() {
        whenTheFileIsHashed("missing");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsContain("Cannot hash");
    }

    @Test
    void shouldStartTheEngineInTheGivenDirectoryAndStop() throws Exception {
        givenAnInstanceStartedWith("--config-dir", this.dir.toString());
        givenTheInstanceHasWritten("config/njmule.properties");

        whenTheInstanceIsStopped();

        thenTheExitCodeIs(0);
        thenTheFileExists("config/njmule.properties");
        thenTheFileExists("logs/" + LoggingSetup.LOG_FILE_NAME);
        thenTheOutputContains("ABSOLUTELY NO WARRANTY");
    }

    @Test
    void shouldHandTheLinksOfASecondStartToTheRunningOne() throws Exception {
        givenAnInstanceStartedWith("--config-dir", this.dir.toString());
        givenTheInstanceHasWritten(HANDOFF_FILE);
        givenASecondStartHandedItsLinksOver(
                "--config-dir", this.dir.toString(), "ed2k://|file|handed.iso|1000|" + "cd".repeat(16) + "|/");
        givenTheInstanceHasWritten("temp/001.part.met");

        whenTheInstanceIsStopped();

        thenTheExitCodeIs(0);
        thenTheRunningOneAddedTheDownload();
        thenTheFileDoesNotExist(HANDOFF_FILE);
    }

    @Test
    void shouldStopNothingForALinkToAnUnusableListAddress() {
        whenRunWith(
                "--config-dir",
                this.dir.toString(),
                "ed2k://|serverlist|http://|/",
                "ed2k://|nodeslist|http://a b/nodes.dat|/",
                "ed2k://|file|after.iso|1000|" + "ef".repeat(16) + "|/");

        thenTheExitCodeIs(0);
        thenTheErrorsContain("Cannot follow ed2k://|serverlist|http://|/", "Cannot follow ed2k://|nodeslist|");
        thenTheLinksAfterThemAreFollowed();
    }

    @Test
    void shouldAddTheServerOfAServerLink() throws Exception {
        givenAnInstanceStartedWith("--config-dir", this.dir.toString(), "ed2k://|server|1.2.3.4|4661|/");
        givenTheInstanceHasSavedTheServerWithin(Duration.ofSeconds(10));

        whenTheInstanceIsStopped();

        thenTheExitCodeIs(0);
        thenTheServersOfTheNextStartInclude("1.2.3.4:4661");
    }

    @Test
    void shouldTellOnExitThatNoInstanceRuns() {
        whenRunWith("--config-dir", this.dir.toString(), "--exit");

        thenTheExitCodeIsNotZero();
        thenTheErrorsContain("No njMule runs");
    }

    @Test
    void shouldTakeADamagedHandoffFileForNoInstanceOnExit() throws IOException {
        givenTheFile(HANDOFF_FILE, "70000 " + "ab".repeat(32));

        whenRunWith("--config-dir", this.dir.toString(), "--exit");

        thenTheExitCodeIsNotZero();
    }

    @Test
    void shouldAskTheRunningOneToStopOnExit() throws Exception {
        givenAnInstanceStartedWith("--config-dir", this.dir.toString());
        givenTheInstanceHasWritten(HANDOFF_FILE);

        whenRunWith("--config-dir", this.dir.toString(), "--exit");

        thenTheExitCodeIs(0);
        thenAnotherInstanceCanStartAtOnce();
        thenTheOutputContains("has exited");
        thenTheInstanceEndedWith(0);
    }

    @Test
    void shouldTakeAnInstanceForGoneOnceItsEngineIsClosed() throws Exception {
        givenARunningEngine();
        givenTheInstanceHasNotGoneWithin(Duration.ofMillis(300));

        whenTheEngineIsClosed();

        thenTheInstanceHasGoneWithin(Duration.ofMillis(300));
    }

    @Test
    void shouldRefuseAWebInterfacePasswordTooShort() {
        givenTheUserTypes("short", "short");

        whenRunWith("--config-dir", this.dir.toString(), "--webui-password");

        thenTheExitCodeIs(Main.EXIT_USAGE);
        thenTheErrorsContain("at least " + Passwords.MIN_LENGTH);
    }

    @Test
    void shouldRefuseWebInterfacePasswordsThatDiffer() {
        givenTheUserTypes("long enough", "different");

        whenRunWith("--config-dir", this.dir.toString(), "--webui-password");

        thenTheExitCodeIs(Main.EXIT_USAGE);
        thenTheErrorsContain("differ");
    }

    @Test
    void shouldRefuseAWebInterfacePasswordNotTyped() {
        givenTheUserTypes();

        whenRunWith("--config-dir", this.dir.toString(), "--webui-password");

        thenTheExitCodeIs(Main.EXIT_USAGE);
    }

    @Test
    void shouldRefuseAWebInterfacePasswordTypedOnce() {
        givenTheUserTypes("long enough");

        whenRunWith("--config-dir", this.dir.toString(), "--webui-password");

        thenTheExitCodeIs(Main.EXIT_USAGE);
        thenTheErrorsContain("at least " + Passwords.MIN_LENGTH);
    }

    @Test
    void shouldSetTheWebInterfacePassword() throws IOException {
        givenAFreePort();
        givenTheUserTypes("long enough", "long enough");

        whenRunWith(
                "--config-dir", this.dir.toString(), "--webui-password", "--webui-port", String.valueOf(this.freePort));

        thenTheExitCodeIs(0);
        thenTheOutputContains("http://localhost:" + this.freePort + "/");
        thenTheWebInterfaceFileContains("enabled=true", "pbkdf2-sha512$");
        thenTheWebInterfaceFileDoesNotContain("long enough");
    }

    @Test
    void shouldSetNoWebInterfacePasswordThatCannotBeSaved() throws IOException {
        givenAFileInPlaceOfTheConfigurationDirectory();
        givenTheUserTypes("long enough", "long enough");

        whenRunWith("--config-dir", this.dir.toString(), "--webui-password");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsContain("Cannot set the web interface password");
    }

    @Test
    void shouldStartTheWebInterfaceWithThePasswordSet() throws Exception {
        givenAFreePort();
        givenTheWebInterfacePasswordWasSetForTheFreePort("long enough");
        givenAnInstanceStartedWith("--config-dir", this.dir.toString());
        givenTheInstanceHasPrinted("Web interface on port");
        givenTheLoginPageWasRequested();

        whenTheInstanceIsStopped();

        thenTheExitCodeIs(0);
        thenTheLoginPageAnswered(200);
        thenTheLoginPageContains("type=\"password\"");
    }

    @Test
    void shouldReportAnUnreadableWebInterfaceFile() throws IOException {
        givenTheFile(WebUiSettings.FILE_NAME, "enabled=true\nport=99999\n");
        givenTheLogOf(WebUiSettings.class);

        whenTheWebInterfaceSettingsAreLoaded();

        thenTheSettingsAreTheDefaults();
        thenTheOnlyLogIsAWarningAbout(WebUiSettings.FILE_NAME);
    }

    @Test
    void shouldEndMainQuietlyAfterAnActionThatSucceeded() {
        whenMainRunsWith("--version");

        thenTheStandardOutputContains(NjMuleInfo.version());
    }

    @Test
    void shouldReadThePasswordFromStandardInputWithoutATerminal() {
        givenNoTerminal();
        givenTheStandardInput("long enough\nlong enough\n");

        whenRunWith("--config-dir", this.dir.toString(), "--webui-password", "--webui-lan");

        thenTheExitCodeIs(0);
        thenTheOutputContains("Web interface password: ", "The same password again: ", "open to other computers");
        thenTheWebInterfaceIsOpenToOtherComputers();
    }

    @Test
    void shouldRefuseAPasswordWhoseInputEndsBeforeTheSecondOne() {
        givenNoTerminal();
        givenTheStandardInput("long enough\n");

        whenRunWith("--config-dir", this.dir.toString(), "--webui-password");

        thenTheExitCodeIs(Main.EXIT_USAGE);
    }

    @Test
    void shouldSetTheWebInterfacePasswordOfAFileWithoutAsking() throws IOException {
        givenAFreePort();
        givenTheFile(PASSWORD_FILE, "long enough\n");

        whenRunWith(
                "--config-dir",
                this.dir.toString(),
                "--webui-password",
                "--webui-password-file",
                this.dir.resolve(PASSWORD_FILE).toString(),
                "--webui-port",
                String.valueOf(this.freePort));

        thenTheExitCodeIs(0);
        thenTheOutputContains("http://localhost:" + this.freePort + "/");
        thenTheOutputDoesNotContain("Web interface password: ");
        thenTheWebInterfacePasswordIs("long enough");
    }

    @Test
    void shouldStartTheWebInterfaceWithThePasswordOfItsFile() throws IOException {
        givenAFreePort();
        givenTheFile(PASSWORD_FILE, "long enough\n");

        whenRunWith(
                "--config-dir",
                this.dir.toString(),
                "--webui-password-file",
                this.dir.resolve(PASSWORD_FILE).toString(),
                "--webui-port",
                String.valueOf(this.freePort));

        thenTheExitCodeIs(0);
        thenTheOutputContains("Web interface on port " + this.freePort);
        thenTheWebInterfacePasswordIs("long enough");
        thenTheWebInterfaceFileDoesNotContain("long enough");
    }

    @Test
    void shouldNotStartWithoutItsWebInterfacePasswordFile() {
        whenRunWith(
                "--config-dir",
                this.dir.toString(),
                "--webui-password-file",
                this.dir.resolve(PASSWORD_FILE).toString());

        thenTheExitCodeIs(Main.EXIT_USAGE);
        thenTheErrorsContain(PASSWORD_FILE + " does not exist");
        thenTheFileDoesNotExist("config/njmule.properties");
    }

    @Test
    void shouldReportAnEngineThatCannotStart() throws IOException {
        givenAFileInPlaceOfTheConfigurationDirectory();

        whenRunWith("--config-dir", this.dir.toString());

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsContain("Cannot start " + NjMuleInfo.NAME);
        thenTheOutputContains("ABSOLUTELY NO WARRANTY");
    }

    @Test
    void shouldStopTheEngineWhenANetworkTaskFails() {
        whenRunWith(
                "--config-dir",
                this.dir.toString(),
                "--add-servers",
                this.dir.resolve("missing.met").toString());

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsContain("Failed: ");
        thenTheEngineHasStoppedWithin(Duration.ofSeconds(5));
    }

    @Test
    void shouldLeaveTheLinksOfAOneShotRunAlone() throws IOException {
        givenTheFolder("emule");

        whenRunWith(
                "--config-dir",
                this.dir.resolve("njmule").toString(),
                "--log-level",
                "WARN",
                "--import-emule",
                this.dir.resolve("emule").toString(),
                "--dry-run",
                "ed2k://|file|left.iso|1000|" + "ab".repeat(16) + "|/");

        thenTheExitCodeIs(0);
        thenTheOutputContains("What would be imported from " + this.dir.resolve("emule"));
        thenTheOutputDoesNotContain("already running");
        thenTheFileDoesNotExist("njmule/temp/001.part.met");
    }

    @Test
    void shouldFailAnInterruptedCommandAndStillUnlockTheConfiguration() throws Exception {
        givenASilentPeer();
        givenACommandRunningWith(
                "--config-dir", this.dir.toString(), "--hello", "127.0.0.1:" + this.silentPeer.getLocalPort());
        givenThePeerTookTheConnection();

        whenTheCommandIsInterrupted();

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheConfigurationIsUnlockedWithin(Duration.ofMillis(500));
    }

    @Test
    void shouldStopAnInterruptedRunAndStillUnlockTheConfiguration() throws Exception {
        givenANjMuleRunningOnItsOwnThread();

        whenItsThreadIsInterrupted();

        thenItHasStopped();
        thenTheExitCodeIs(Main.EXIT_OK);
        // The engine was closed, its saves done, before the run returned: another may start at once.
        thenTheConfigurationIsUnlockedWithin(Duration.ofMillis(500));
    }

    @Test
    void shouldLeaveTheWebInterfaceOffOnABusyPort() throws IOException {
        givenABusyPort();
        givenTheWebInterfaceOnTheBusyPort();
        givenTheLogOf(Main.class);

        whenRunWith("--config-dir", this.dir.toString());

        thenTheExitCodeIs(0);
        thenTheOutputDoesNotContain("Web interface on port");
        thenALogMessageStartsWith("Cannot start the web interface on port " + this.busy.getLocalPort());
    }

    @Test
    void shouldRunTheInstanceAloneWithoutItsHandoffFile() throws IOException {
        givenSomethingThatCannotBeReplacedWhereTheHandoffFileGoes();
        givenTheLogOf(Main.class);

        whenRunWith("--config-dir", this.dir.toString());

        thenTheExitCodeIs(0);
        thenALogMessageStartsWith("Later starts cannot hand their links over");
        thenTheFileExists(HANDOFF_FILE + "/kept");
    }

    @Test
    void shouldReportTheLinksThatCannotBeFollowed() throws Exception {
        givenTheLogOf(Main.class);
        givenAnInstanceStartedWith(
                "--config-dir", this.dir.toString(), SERVER_LIST, NODES_LIST, "ed2k://|server|::1|4661|/", SEARCH);
        givenTheInstanceHasReported(
                "Cannot take the server list: ", "Cannot take the Kad contacts: ", "Cannot add the server: ");
        givenTheInstanceHasLogged("Not acting on " + SEARCH);

        whenTheInstanceIsStopped();

        thenTheExitCodeIs(0);
        thenTheErrorsGiveTheReasonNotTheWrappingOfTheFuture();
    }

    private void givenTheFile(String path, String content) throws IOException {
        Path file = this.dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, UTF_8);
    }

    private void givenTheFolder(String path) throws IOException {
        Files.createDirectories(this.dir.resolve(path));
    }

    private void givenAFileInPlaceOfTheConfigurationDirectory() throws IOException {
        givenTheFile("config", "a file where the directory goes");
    }

    // A directory where the file goes, with something in it: nothing can replace it.
    private void givenSomethingThatCannotBeReplacedWhereTheHandoffFileGoes() throws IOException {
        givenTheFile(HANDOFF_FILE + "/kept", "");
    }

    private void givenTheUserTypes(String... lines) {
        this.typed = List.of(lines);
    }

    private void givenNoTerminal() {
        assumeTrue(
                Optional.ofNullable(System.console())
                        .filter(Console::isTerminal)
                        .isEmpty(),
                "standard input is a terminal");
    }

    private void givenTheStandardInput(String text) {
        this.originalIn = System.in;
        System.setIn(new ByteArrayInputStream(text.getBytes(UTF_8)));
    }

    private void givenAFreePort() throws IOException {
        this.freePort = FreePorts.tcp();
    }

    private void givenTheWebInterfacePasswordWasSetForTheFreePort(String password) {
        assertThat(runWith(
                        List.of(password, password),
                        "--config-dir",
                        this.dir.toString(),
                        "--webui-password",
                        "--webui-port",
                        String.valueOf(this.freePort)))
                .isZero();
    }

    private void givenABusyPort() throws IOException {
        this.busy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }

    private void givenTheWebInterfaceOnTheBusyPort() throws IOException {
        new WebUiSettings(true, this.busy.getLocalPort(), false, "pbkdf2-sha512$1$AA$AA")
                .save(this.dir.resolve("config"));
    }

    private void givenTheLogOf(Class<?> type) {
        this.logged = new Logged(type);
    }

    private void givenAnInstanceStartedWith(String... args) {
        this.stop = new CompletableFuture<>();
        CompletableFuture<Void> stopped = this.stop;
        this.exit = CompletableFuture.supplyAsync(() -> Main.run(
                args, new PrintStream(this.out, true, UTF_8), new PrintStream(this.err, true, UTF_8), stopped));
        this.deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    }

    private void givenTheInstanceHasWritten(String path) throws InterruptedException {
        Path file = this.dir.resolve(path);
        waitUntil(() -> Files.exists(file));
    }

    private void givenTheInstanceHasPrinted(String text) throws InterruptedException {
        waitUntil(() -> this.out.toString(UTF_8).contains(text));
    }

    private void givenTheInstanceHasSavedTheServerWithin(Duration timeout) throws Exception {
        Path met = this.dir.resolve("config").resolve("server.met");
        long end = System.nanoTime() + timeout.toNanos();
        // The address as server.met holds it.
        while (!(Files.exists(met)
                        && new String(Files.readAllBytes(met), ISO_8859_1).contains("\u0001\u0002\u0003\u0004"))
                && System.nanoTime() < end) {
            Thread.sleep(20);
        }
    }

    private void givenASecondStartHandedItsLinksOver(String... args) {
        assertThat(Main.run(
                        args,
                        new PrintStream(this.secondOut, true, UTF_8),
                        new PrintStream(this.err, true, UTF_8),
                        CompletableFuture.completedFuture(null)))
                .isZero();
        assertThat(this.secondOut.toString(UTF_8)).contains("already running");
    }

    private void givenTheInstanceHasReported(String... texts) throws InterruptedException {
        await(() -> {
            String reported = this.err.toString(UTF_8);
            return List.of(texts).stream().allMatch(reported::contains);
        });
    }

    private void givenTheInstanceHasLogged(String message) throws InterruptedException {
        await(() -> this.logged.messages().contains(message));
    }

    private void givenTheLoginPageWasRequested() throws IOException, InterruptedException {
        this.login = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.freePort + "/login"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private void givenARunningEngine() throws IOException {
        this.directories = AppDirectories.resolve(Optional.of(this.dir));
        this.engine = NjMuleEngine.start(new EngineConfig(this.directories));
    }

    private void givenTheInstanceHasNotGoneWithin(Duration timeout) {
        assertThat(LinkHandoff.awaitGone(this.directories.config(), timeout)).isFalse();
    }

    private void givenASilentPeer() throws IOException {
        this.silentPeer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }

    private void givenACommandRunningWith(String... args) {
        this.command = Thread.ofPlatform()
                .start(() -> this.commandCode.set(Main.run(
                        args,
                        new PrintStream(this.out, true, UTF_8),
                        new PrintStream(this.err, true, UTF_8),
                        new CompletableFuture<>())));
    }

    // A peer that takes the connection and never answers the hello.
    private void givenThePeerTookTheConnection() throws IOException {
        this.silentPeer.setSoTimeout(20_000);
        this.accepted = this.silentPeer.accept();
    }

    private void givenANjMuleRunningOnItsOwnThread() throws InterruptedException {
        this.stop = new CompletableFuture<>();
        CompletableFuture<Void> stopped = this.stop;
        this.running = Thread.ofPlatform()
                .start(() -> this.runCode.set(Main.run(
                        new String[] {"--config-dir", this.dir.toString()},
                        new PrintStream(this.out, true, UTF_8),
                        new PrintStream(this.err, true, UTF_8),
                        stopped)));
        Path handoff = this.dir.resolve("config").resolve(LinkHandoff.FILE_NAME);
        await(() -> Files.exists(handoff));
    }

    private void whenRunWith(String... args) {
        this.exitCode = this.typed == null ? run(args) : runWith(this.typed, args);
    }

    private void whenTheFileIsHashed(String name) {
        whenRunWith("--hash", this.dir.resolve(name).toString());
    }

    private void whenTheInstanceIsStopped() throws Exception {
        this.stop.complete(null);
        this.exitCode = this.exit.get(20, TimeUnit.SECONDS);
    }

    private void whenTheEngineIsClosed() {
        this.engine.close();
        this.engine = null;
    }

    private void whenTheWebInterfaceSettingsAreLoaded() {
        this.settings = WebUiSettings.load(this.dir);
    }

    private void whenMainRunsWith(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(standardOut, true, UTF_8));
        try {
            Main.main(args);
        } finally {
            System.setOut(original);
        }
        this.printed = standardOut.toString(UTF_8);
    }

    private void whenTheCommandIsInterrupted() throws Exception {
        this.command.interrupt();
        this.command.join(Duration.ofSeconds(20));
        this.accepted.close();
        this.exitCode = this.commandCode.get();
    }

    private void whenItsThreadIsInterrupted() throws InterruptedException {
        this.running.interrupt();
        this.running.join(Duration.ofSeconds(20));
        this.exitCode = this.runCode.get();
    }

    private void thenTheExitCodeIs(int expected) {
        assertThat(this.exitCode).isEqualTo(expected);
    }

    private void thenTheExitCodeIsNotZero() {
        assertThat(this.exitCode).isNotZero();
    }

    private void thenTheOutputContains(String... texts) {
        assertThat(this.out.toString(UTF_8)).contains(texts);
    }

    private void thenTheOutputDoesNotContain(String text) {
        assertThat(this.out.toString(UTF_8)).doesNotContain(text);
    }

    private void thenTheStandardOutputContains(String text) {
        assertThat(this.printed).contains(text);
    }

    private void thenTheErrorsContain(String... texts) {
        assertThat(this.err.toString(UTF_8)).contains(texts);
    }

    private void thenTheErrorsGiveTheReasonNotTheWrappingOfTheFuture() {
        assertThat(this.err.toString(UTF_8))
                .as("the reason, not the wrapping of the future")
                .doesNotContain("CompletionException");
    }

    private void thenTheFileExists(String path) {
        assertThat(this.dir.resolve(path)).exists();
    }

    private void thenTheFileDoesNotExist(String path) {
        assertThat(this.dir.resolve(path)).doesNotExist();
    }

    private void thenTheRunningOneAddedTheDownload() {
        assertThat(this.dir.resolve("temp").resolve("001.part.met"))
                .as("the running one added the download")
                .exists();
    }

    private void thenTheLinksAfterThemAreFollowed() {
        assertThat(this.dir.resolve("temp").resolve("001.part.met"))
                .as("the links after them are followed")
                .exists();
    }

    private void thenTheServersOfTheNextStartInclude(String address) throws Exception {
        try (NjMuleEngine next = NjMuleEngine.start(new EngineConfig(AppDirectories.resolve(Optional.of(this.dir))))) {
            assertThat(next.servers().get(20, TimeUnit.SECONDS))
                    .extracting(ServerInfo::address)
                    .contains(address);
        }
    }

    // It returns once the instance has gone: another may start at once.
    private void thenAnotherInstanceCanStartAtOnce() throws Exception {
        try (NjMuleEngine next = NjMuleEngine.start(new EngineConfig(AppDirectories.resolve(Optional.of(this.dir))))) {
            assertThat(next.directories().config()).isEqualTo(this.dir.resolve("config"));
        }
    }

    private void thenTheInstanceEndedWith(int expected) throws Exception {
        assertThat(this.exit.get(20, TimeUnit.SECONDS)).isEqualTo(expected);
    }

    private void thenTheInstanceHasGoneWithin(Duration timeout) {
        assertThat(LinkHandoff.awaitGone(this.directories.config(), timeout)).isTrue();
    }

    private void thenTheEngineHasStoppedWithin(Duration timeout) {
        assertThat(LinkHandoff.awaitGone(this.dir.resolve("config"), timeout))
                .as("the engine has stopped")
                .isTrue();
    }

    // The engine was closed, its saves done, before the run returned: another may start at once.
    private void thenItHasStopped() {
        assertThat(this.running.isAlive()).as("still running").isFalse();
    }

    private void thenTheConfigurationIsUnlockedWithin(Duration timeout) {
        assertThat(LinkHandoff.awaitGone(this.dir.resolve("config"), timeout))
                .as("the configuration is unlocked")
                .isTrue();
    }

    private void thenTheWebInterfaceFileContains(String... texts) throws IOException {
        assertThat(Files.readString(this.dir.resolve("config").resolve(WebUiSettings.FILE_NAME)))
                .contains(texts);
    }

    private void thenTheWebInterfaceFileDoesNotContain(String text) throws IOException {
        assertThat(Files.readString(this.dir.resolve("config").resolve(WebUiSettings.FILE_NAME)))
                .doesNotContain(text);
    }

    private void thenTheWebInterfacePasswordIs(String password) {
        WebUiSettings saved = WebUiSettings.load(this.dir.resolve("config"));
        assertThat(saved.usable()).as("on, with a password").isTrue();
        assertThat(Passwords.matches(password.toCharArray(), saved.passwordHash()))
                .as("the password of the file")
                .isTrue();
    }

    private void thenTheWebInterfaceIsOpenToOtherComputers() {
        assertThat(WebUiSettings.load(this.dir.resolve("config")).lan()).isTrue();
    }

    private void thenTheLoginPageAnswered(int status) {
        assertThat(this.login.statusCode()).isEqualTo(status);
    }

    private void thenTheLoginPageContains(String text) {
        assertThat(this.login.body()).contains(text);
    }

    private void thenTheSettingsAreTheDefaults() {
        assertThat(this.settings).isEqualTo(WebUiSettings.DEFAULTS);
    }

    private void thenTheOnlyLogIsAWarningAbout(String text) {
        assertThat(this.logged.events())
                .singleElement()
                .satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN))
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains(text));
    }

    private void thenALogMessageStartsWith(String start) {
        assertThat(this.logged.messages()).anyMatch(m -> m.startsWith(start));
    }

    private int run(String... args) {
        return Main.run(
                args,
                new PrintStream(this.out, true, UTF_8),
                new PrintStream(this.err, true, UTF_8),
                CompletableFuture.completedFuture(null));
    }

    private int runWith(List<String> lines, String... args) {
        Iterator<String> next = lines.iterator();
        return Main.run(
                args,
                new PrintStream(this.out, true, UTF_8),
                new PrintStream(this.err, true, UTF_8),
                CompletableFuture.completedFuture(null),
                prompt -> next.hasNext() ? next.next().toCharArray() : null);
    }

    // Waits up to the deadline of the instance, without failing: the checks come after.
    private void waitUntil(BooleanSupplier condition) throws InterruptedException {
        while (!condition.getAsBoolean() && System.nanoTime() < this.deadline) {
            Thread.sleep(20);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waited too long").isLessThan(end);
            Thread.sleep(20);
        }
    }

    /** The messages a class logs while this is open. */
    private static final class Logged implements AutoCloseable {
        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Logged(Class<?> type) {
            this.logger = (Logger) LoggerFactory.getLogger(type);
            this.appender.start();
            this.logger.addAppender(this.appender);
        }

        // An appender adds under its own lock.
        List<ILoggingEvent> events() {
            synchronized (this.appender) {
                return List.copyOf(this.appender.list);
            }
        }

        List<String> messages() {
            return events().stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        @Override
        public void close() {
            this.logger.detachAppender(this.appender);
        }
    }
}
