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
package org.njmule.webui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.njmule.core.api.UpdateStatus.Install.ASKED;
import static org.njmule.core.api.UpdateStatus.Install.AT_EXIT;
import static org.njmule.core.api.UpdateStatus.Install.RESTART;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;

/** The shared files, Kad, log and settings pages, and the word of a new version. */
@Timeout(60)
class WebUiPagesTest {

    private static final String PASSWORD = "correct horse";
    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");
    private static final String HASH = "AB".repeat(16);

    private final FakeEngine fake = new FakeEngine();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @TempDir
    Path dir;

    private WebUi ui;
    private String cookie;
    private HttpResponse<String> answer;

    @AfterEach
    void stop() {
        if (this.ui != null) {
            this.ui.close();
        }
        this.fake.events.close();
    }

    @Test
    void shouldListTheSharedFiles() throws Exception {
        givenALoggedInUser();
        givenTheEngineShares("talk.bin", "/files");

        whenThePageIsAsked("/shared");

        thenThePageShows("talk.bin", Path.of("/files") + " (with subfolders)", "value=\"high\" selected");
    }

    @Test
    void shouldSetThePriorityOfASharedFile() throws Exception {
        givenALoggedInUser();
        givenTheEngineShares("talk.bin", "/files");

        whenPosted("/shared", Map.of("action", "priority", "hash", HASH, "priority", "release"));

        thenTheEngineWasAsked("upload priority " + HASH + " RELEASE");
    }

    @Test
    void shouldLinkItsPagesRelativelySoThatAProxyMayServeThemUnderAPath() throws Exception {
        givenALoggedInUser();
        givenKadConnectedAndFirewalled();

        whenThePageIsAsked("/kad");

        thenThePageShows(
                "href=\"style.css\"", "href=\"./\"", "href=\"transfers\"", "action=\"kad\"", "action=\"logout\"");
        thenThePageHasNoLinkFromTheTop();
    }

    @Test
    void shouldShowKad() throws Exception {
        givenALoggedInUser();
        givenKadConnectedAndFirewalled();

        whenThePageIsAsked("/kad");

        thenThePageShows("firewalled", "42 (40 verified)", ">Disconnect<", "Recheck firewall", "Buddy");
    }

    @Test
    void shouldBootstrapKad() throws Exception {
        givenALoggedInUser();
        givenKadConnectedAndFirewalled();

        whenPosted("/kad", Map.of("action", "bootstrap", "address", "10.0.0.5:4672"));

        thenTheEngineWasAsked("bootstrap 10.0.0.5:4672");
    }

    @Test
    void shouldSaveTheHostNamesOfTheSettingsPage() throws Exception {
        givenALoggedInUser();
        givenTheSettingsInTheConfigDirectory();

        whenPosted("/settings", Map.of("hosts", "NAS.local njmule.example.com"));

        thenTheSavedHostNamesAre("nas.local", "njmule.example.com");
        thenTheSettingsPageShows("value=\"nas.local, njmule.example.com\"");
    }

    @Test
    void shouldRefuseAHostNameOfTheSettingsPageThatIsNone() throws Exception {
        givenALoggedInUser();
        givenTheSettingsInTheConfigDirectory();

        whenPosted("/settings", Map.of("hosts", "nas.local https://njmule.example.com"));

        thenTheAnswerIs(400, "not a host name or an IP address");
        thenTheSavedHostNamesAre();
    }

    @Test
    void shouldTellOfANewVersionAboveTheStatus() throws Exception {
        givenALoggedInUser();
        givenANewVersion(InstallKind.MSI, Optional.empty());

        whenThePageIsAsked("/");

        thenThePageShows("njMule 0.2.0 is available", "What changed", "href=\"settings\"");
    }

    @Test
    void shouldKeepQuietAboutASkippedVersionAboveTheStatus() throws Exception {
        givenALoggedInUser();
        givenANewVersion(InstallKind.MSI, Optional.of("0.2.0"));

        whenThePageIsAsked("/");

        thenThePageDoesNotShow("is available");
    }

    @Test
    void shouldTellHowAPackageManagerUpdatesIt() throws Exception {
        givenALoggedInUser();
        givenANewVersion(InstallKind.DEB, Optional.empty());

        whenThePageIsAsked("/settings");

        thenThePageShows("sudo apt install ./njmule_0.2.0_amd64.deb", "Skip this version");
    }

    @Test
    void shouldNotOfferToSkipAVersionWithSecurityFixes() throws Exception {
        givenALoggedInUser();
        givenACriticalNewVersion();

        whenThePageIsAsked("/settings");

        thenThePageShows("with security fixes");
        thenThePageDoesNotShow("Skip this version");
    }

    @Test
    void shouldSaveHowItLooksForNewVersions() throws Exception {
        givenALoggedInUser();
        givenTheEngineKeepsThePreferences();

        whenPosted("/settings", Map.of("action", "updates", "mode", "OFF", "channel", "PRERELEASE"));

        thenTheEngineWasAsked("updates OFF PRERELEASE EXIT 03:00-05:00");
    }

    @Test
    void shouldSaveWhenAndInWhichHoursItInstalls() throws Exception {
        givenALoggedInUser();
        givenTheEngineKeepsThePreferences();

        whenPosted(
                "/settings",
                Map.of(
                        "action",
                        "updates",
                        "mode",
                        "INSTALL",
                        "channel",
                        "STABLE",
                        "installAt",
                        "EXIT_OR_NIGHT",
                        "nightHours",
                        "01:00-03:30"));

        thenTheEngineWasAsked("updates INSTALL STABLE EXIT_OR_NIGHT 01:00-03:30");
    }

    @Test
    void shouldOfferToDownloadAPackageItCannotInstall() throws Exception {
        givenALoggedInUser();
        givenANewVersion(InstallKind.DEB, Optional.empty());

        whenThePageIsAsked("/settings");

        thenThePageShows("value=\"download\"", ">Download</button>");
        thenThePageDoesNotShow("Install and restart");
    }

    @Test
    void shouldShowHowFarTheDownloadIs() throws Exception {
        givenALoggedInUser();
        givenTheUpdate(InstallKind.WINDOWS_ZIP, Optional.of(new UpdateStatus.Progress(1024, 4096)), false, ASKED);

        whenThePageIsAsked("/settings");

        thenThePageShows("Downloading: 1.00 KB of 4.00 KB (25%)");
    }

    @Test
    void shouldOfferToInstallAVersionReadyWhereItReplacesItself() throws Exception {
        givenALoggedInUser();
        givenTheUpdate(InstallKind.WINDOWS_ZIP, Optional.empty(), true, ASKED);

        whenThePageIsAsked("/settings");

        thenThePageShows("Install and restart", "Install at exit");
    }

    @Test
    void shouldTellAboveTheStatusThatAVersionIsReady() throws Exception {
        givenALoggedInUser();
        givenTheUpdate(InstallKind.WINDOWS_ZIP, Optional.empty(), true, AT_EXIT);

        whenThePageIsAsked("/");

        thenThePageShows("njMule 0.2.0 is downloaded and ready to install", "Install it");
    }

    @Test
    void shouldTellThatItRestartsToInstall() throws Exception {
        givenALoggedInUser();
        givenTheUpdate(InstallKind.WINDOWS_ZIP, Optional.empty(), true, RESTART);

        whenThePageIsAsked("/");

        thenThePageShows("is installing a new version and starting again");
    }

    @Test
    void shouldDownloadTheNewVersionWhenAsked() throws Exception {
        givenALoggedInUser();
        givenTheEngineDoesTheUpdates();

        whenPosted("/settings", Map.of("action", "download"));

        thenTheEngineWasAsked("download");
    }

    @Test
    void shouldInstallAndRestartWhenAsked() throws Exception {
        givenALoggedInUser();
        givenTheEngineDoesTheUpdates();

        whenPosted("/settings", Map.of("action", "install"));

        thenTheEngineWasAsked("install now");
    }

    @Test
    void shouldInstallAtExitWhenAsked() throws Exception {
        givenALoggedInUser();
        givenTheEngineDoesTheUpdates();

        whenPosted("/settings", Map.of("action", "install-at-exit"));

        thenTheEngineWasAsked("install at exit");
    }

    @Test
    void shouldLookForANewVersionWhenAsked() throws Exception {
        givenALoggedInUser();
        givenTheEngineLooksWhenAsked();

        whenPosted("/settings", Map.of("action", "check"));

        thenTheEngineWasAsked("check");
    }

    @Test
    void shouldSkipAVersionWhenAsked() throws Exception {
        givenALoggedInUser();
        givenTheEngineSkipsWhenAsked();

        whenPosted("/settings", Map.of("action", "skip", "version", "0.2.0"));

        thenTheEngineWasAsked("skip 0.2.0");
    }

    @Test
    void shouldShowTheLatestLogLinesNewestFirst() throws Exception {
        givenALoggedInUser();
        givenTheEngineLogged("first line", "second <line>");

        whenTheLogShows("second &lt;line&gt;");

        thenTheNewestLineComesFirst("second &lt;line&gt;", "first line");
    }

    // Given

    private void givenALoggedInUser() throws Exception {
        String hash = Passwords.hash(PASSWORD.toCharArray(), new byte[16], 1000);
        this.ui = WebUi.start(this.fake.engine(), new WebUiSettings(true, FreePorts.tcp(), false, hash));
        HttpResponse<String> in = post("/login", Map.of("password", PASSWORD));
        assertThat(in.statusCode()).isEqualTo(303);
        String set = in.headers().firstValue("Set-Cookie").orElseThrow();
        this.cookie = set.substring(0, set.indexOf(';'));
    }

    private void givenTheSettingsInTheConfigDirectory() throws Exception {
        AppDirectories dirs = AppDirectories.resolve(Optional.of(this.dir));
        this.fake.value("directories", dirs);
        new WebUiSettings(true, 4711, false, "hash").save(dirs.config());
    }

    private void givenTheEngineShares(String name, String folder) {
        this.fake.answer(
                "sharedFiles",
                args -> CompletableFuture.completedFuture(List.of(new SharedFileInfo(
                        Hash16.parseHex(HASH), name, 5000, Path.of(folder, name), UploadPriority.HIGH))));
        this.fake.answer(
                "sharedDirectories",
                args -> CompletableFuture.completedFuture(List.of(new SharedDirectory(Path.of(folder), true))));
        this.fake.answer("setUploadPriority", args -> {
            this.calls.add("upload priority " + ((Hash16) args[0]).toHex() + " " + args[1]);
            return CompletableFuture.completedFuture(true);
        });
    }

    private void givenKadConnectedAndFirewalled() {
        this.fake.answer(
                "kad",
                args -> CompletableFuture.completedFuture(
                        Optional.of(new KadInfo(true, 42, 40, true, "00".repeat(16), "1.2.3.4", 4672))));
        this.fake.answer("kadBootstrap", args -> {
            ServerAddress node = (ServerAddress) args[0];
            this.calls.add("bootstrap " + node.host() + ":" + node.port());
            return CompletableFuture.completedFuture(null);
        });
    }

    private void givenANewVersion(InstallKind installation, Optional<String> skipped) {
        givenANewVersion(installation, skipped, false);
    }

    private void givenACriticalNewVersion() {
        givenANewVersion(InstallKind.MSI, Optional.empty(), true);
    }

    private void givenTheUpdate(
            InstallKind installation,
            Optional<UpdateStatus.Progress> download,
            boolean ready,
            UpdateStatus.Install install) {
        givenANewVersion(installation, Optional.empty(), false);
        UpdateStatus found = (UpdateStatus) this.fake.engine().updateStatus();
        this.fake.value(
                "updateStatus",
                new UpdateStatus(
                        found.current(),
                        found.installation(),
                        UpdateStatus.Installer.SELF,
                        found.checking(),
                        found.lastCheck(),
                        found.failure(),
                        found.available(),
                        found.skipped(),
                        download,
                        ready ? Optional.of(Path.of("cache", "update", "0.2.0", "package.zip")) : Optional.empty(),
                        install));
    }

    private void givenTheEngineDoesTheUpdates() {
        this.fake.answer("downloadUpdate", args -> {
            this.calls.add("download");
            return new CompletableFuture<>();
        });
        this.fake.answer("installUpdate", args -> {
            this.calls.add((Boolean) args[0] ? "install now" : "install at exit");
            return CompletableFuture.completedFuture(this.fake.engine().updateStatus());
        });
    }

    private void givenANewVersion(InstallKind installation, Optional<String> skipped, boolean critical) {
        this.fake.answer(
                "status",
                args -> CompletableFuture.completedFuture(new EngineStatus(
                        Optional.empty(),
                        0,
                        false,
                        false,
                        Optional.empty(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        "",
                        4662,
                        false,
                        Optional.empty(),
                        Optional.empty())));
        URI release = URI.create("https://github.com/njMule/njMule/releases/download/v0.2.0/");
        Optional<NewVersion.Download> download = installation == InstallKind.DEB
                ? Optional.of(new NewVersion.Download(release.resolve("njmule_0.2.0_amd64.deb"), 1, "0".repeat(64)))
                : Optional.of(
                        new NewVersion.Download(release.resolve("njmule-0.2.0-windows-x64.msi"), 1, "0".repeat(64)));
        this.fake.value(
                "updateStatus",
                new UpdateStatus(
                        "0.1.0",
                        installation,
                        UpdateStatus.Installer.NONE,
                        false,
                        Optional.of(Instant.parse("2026-11-03T08:00:00Z")),
                        Optional.empty(),
                        Optional.of(new NewVersion(
                                "0.2.0",
                                Instant.parse("2026-11-02T10:00:00Z"),
                                critical,
                                "",
                                URI.create("https://github.com/njMule/njMule/releases/tag/v0.2.0"),
                                download)),
                        skipped,
                        Optional.empty(),
                        Optional.empty(),
                        UpdateStatus.Install.ASKED));
    }

    @SuppressWarnings("unchecked")
    private void givenTheEngineKeepsThePreferences() {
        this.fake.answer("updatePreferences", args -> {
            Preferences changed = ((UnaryOperator<Preferences>) args[0]).apply(Preferences.defaults());
            this.calls.add("updates " + changed.updateMode() + " " + changed.updateChannel() + " "
                    + changed.updateInstallTime() + " " + changed.nightHours());
            return CompletableFuture.completedFuture(changed);
        });
    }

    private void givenTheEngineLooksWhenAsked() {
        this.fake.answer("checkForUpdate", args -> {
            this.calls.add("check");
            return new CompletableFuture<>();
        });
    }

    private void givenTheEngineSkipsWhenAsked() {
        this.fake.answer("skipUpdate", args -> {
            this.calls.add("skip " + args[0]);
            return CompletableFuture.completedFuture(null);
        });
    }

    private void givenTheEngineLogged(String... messages) {
        for (String message : messages) {
            this.fake.events.submit(new EngineEvent.Log(new LogLine(Instant.now(), LogLine.Level.INFO, message)));
        }
    }

    // When

    private void whenThePageIsAsked(String path) throws Exception {
        this.answer = get(path);
    }

    private void whenPosted(String path, Map<String, String> fields) throws Exception {
        Map<String, String> all = new HashMap<>(fields);
        all.put("csrf", csrf());
        this.answer = post(path, all);
    }

    // Delivered on the publisher's threads.
    private void whenTheLogShows(String text) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            whenThePageIsAsked("/log");
        } while (!this.answer.body().contains(text) && System.nanoTime() < end);
    }

    // Then

    private void thenThePageShows(String... parts) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(200);
        assertThat(this.answer.body()).contains(parts);
    }

    private void thenThePageDoesNotShow(String text) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(200);
        assertThat(this.answer.body()).doesNotContain(text);
    }

    private void thenTheAnswerIs(int status, String text) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(status);
        assertThat(this.answer.body()).contains(text);
    }

    private void thenTheSavedHostNamesAre(String... names) {
        assertThat(WebUiSettings.load(
                                AppDirectories.resolve(Optional.of(this.dir)).config())
                        .hosts())
                .containsExactly(names);
    }

    private void thenTheSettingsPageShows(String text) throws Exception {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(303);
        assertThat(get("/settings").body()).contains(text);
    }

    private void thenThePageHasNoLinkFromTheTop() {
        assertThat(this.answer.body()).doesNotContain("href=\"/", "action=\"/", "src=\"/");
    }

    private void thenTheEngineWasAsked(String call) {
        assertThat(this.answer.statusCode()).as(this.answer.body()).isEqualTo(303);
        assertThat(this.calls).contains(call);
    }

    private void thenTheNewestLineComesFirst(String newer, String older) {
        String page = this.answer.body();
        assertThat(page).contains(newer, older).doesNotContain("second <line>");
        assertThat(page.indexOf(newer)).isLessThan(page.indexOf(older));
    }

    private String csrf() throws Exception {
        Matcher m = CSRF.matcher(get("/search").body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return this.http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return this.http.send(
                request(path)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.ui.port() + path))
                .timeout(Duration.ofSeconds(30));
        if (this.cookie != null) {
            b.header("Cookie", this.cookie);
        }
        return b;
    }
}
