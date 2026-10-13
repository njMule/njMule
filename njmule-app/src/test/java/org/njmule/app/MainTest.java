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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.logging.LoggingSetup;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.webui.Passwords;
import org.njmule.webui.WebUiSettings;

@Timeout(30)
class MainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(
                args,
                new PrintStream(this.out, true, UTF_8),
                new PrintStream(this.err, true, UTF_8),
                CompletableFuture.completedFuture(null));
    }

    @Test
    void printsVersionLicenseWarrantyNoticeAndHelp() {
        assertThat(this.run("--version")).isZero();
        assertThat(this.out.toString(UTF_8)).contains(NjMuleInfo.version(), "GPL-3.0-or-later");

        assertThat(this.run("--license")).isZero();
        assertThat(this.out.toString(UTF_8)).contains("GNU GENERAL PUBLIC LICENSE");

        assertThat(this.run("--warranty")).isZero();
        assertThat(this.out.toString(UTF_8)).contains("Disclaimer of Warranty");

        assertThat(this.run("--notice")).isZero();
        assertThat(this.out.toString(UTF_8)).contains("Third-party components");

        assertThat(this.run("--help")).isZero();
        assertThat(this.out.toString(UTF_8)).contains("--config-dir");
    }

    @Test
    void invalidArgumentsExitWithUsage() {
        assertThat(this.run("--nope")).isEqualTo(Main.EXIT_USAGE);
        assertThat(this.err.toString(UTF_8)).contains("Unknown option", "Usage:");
    }

    @Test
    void hashPrintsTheLinkOfEachFile() throws Exception {
        Path file = Files.writeString(this.dir.resolve("abc.txt"), "abc", UTF_8);

        assertThat(this.run("--hash", file.toString())).isZero();
        assertThat(this.out.toString(UTF_8))
                .contains(
                        "ed2k://|file|abc.txt|3|A448017AAF21D8525FC10AE87AA6729D|"
                                + "h=VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5|/",
                        "3 bytes, 0 part hashes");

        assertThat(this.run("--hash", this.dir.resolve("missing").toString())).isEqualTo(Main.EXIT_FAILURE);
        assertThat(this.err.toString(UTF_8)).contains("Cannot hash");
    }

    @Test
    void runStartsTheEngineInTheGivenDirectoryAndStops() throws Exception {
        CompletableFuture<Void> stop = new CompletableFuture<>();
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> Main.run(
                new String[] {"--config-dir", this.dir.toString()},
                new PrintStream(this.out, true, UTF_8),
                new PrintStream(this.err, true, UTF_8),
                stop));

        Path prefs = this.dir.resolve("config/njmule.properties");
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!Files.exists(prefs) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        stop.complete(null);

        assertThat(exit.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(prefs).exists();
        assertThat(this.dir.resolve("logs").resolve(LoggingSetup.LOG_FILE_NAME)).exists();
        assertThat(this.out.toString(UTF_8)).contains("ABSOLUTELY NO WARRANTY");
    }

    @Test
    void setsTheWebInterfacePasswordAndStartsTheInterface() throws Exception {
        String config = this.dir.toString();
        assertThat(this.runWith(List.of("short", "short"), "--config-dir", config, "--webui-password"))
                .isEqualTo(Main.EXIT_USAGE);
        assertThat(this.err.toString(UTF_8)).contains("at least " + Passwords.MIN_LENGTH);
        assertThat(this.runWith(List.of("long enough", "different"), "--config-dir", config, "--webui-password"))
                .isEqualTo(Main.EXIT_USAGE);
        assertThat(this.err.toString(UTF_8)).contains("differ");
        assertThat(this.runWith(List.of(), "--config-dir", config, "--webui-password"))
                .isEqualTo(Main.EXIT_USAGE);

        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        assertThat(this.runWith(
                        List.of("long enough", "long enough"),
                        "--config-dir",
                        config,
                        "--webui-password",
                        "--webui-port",
                        String.valueOf(port)))
                .isZero();
        assertThat(this.out.toString(UTF_8)).contains("http://localhost:" + port + "/");
        Path file = this.dir.resolve("config").resolve(WebUiSettings.FILE_NAME);
        assertThat(Files.readString(file)).contains("enabled=true", "pbkdf2-sha512$");
        assertThat(Files.readString(file)).doesNotContain("long enough");

        CompletableFuture<Void> stop = new CompletableFuture<>();
        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> Main.run(
                new String[] {"--config-dir", config},
                new PrintStream(this.out, true, UTF_8),
                new PrintStream(this.err, true, UTF_8),
                stop));
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!this.out.toString(UTF_8).contains("Web interface on port") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        HttpResponse<String> login = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        stop.complete(null);
        assertThat(exit.get(20, TimeUnit.SECONDS)).isZero();
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("type=\"password\"");
    }

    private int runWith(List<String> typed, String... args) {
        Iterator<String> lines = typed.iterator();
        return Main.run(
                args,
                new PrintStream(this.out, true, UTF_8),
                new PrintStream(this.err, true, UTF_8),
                CompletableFuture.completedFuture(null),
                prompt -> lines.hasNext() ? lines.next().toCharArray() : null);
    }
}
