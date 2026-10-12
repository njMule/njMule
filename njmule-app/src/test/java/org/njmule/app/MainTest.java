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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.logging.LoggingSetup;
import org.njmule.core.api.NjMuleInfo;

@Timeout(30)
class MainTest {

    @TempDir Path dir;

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
        assertThat(this.out.toString(UTF_8)).contains("eMule");

        assertThat(this.run("--help")).isZero();
        assertThat(this.out.toString(UTF_8)).contains("--config-dir");
    }

    @Test
    void invalidArgumentsExitWithUsage() {
        assertThat(this.run("--nope")).isEqualTo(Main.EXIT_USAGE);
        assertThat(this.err.toString(UTF_8)).contains("Unknown option", "Usage:");
    }

    @Test
    void runStartsTheEngineInTheGivenDirectoryAndStops() throws Exception {
        CompletableFuture<Void> stop = new CompletableFuture<>();
        CompletableFuture<Integer> exit =
                CompletableFuture.supplyAsync(
                        () ->
                                Main.run(
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
}
