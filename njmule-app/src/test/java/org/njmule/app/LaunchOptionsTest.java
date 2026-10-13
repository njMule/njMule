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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.LaunchOptions.Action;
import org.njmule.core.api.ServerAddress;

class LaunchOptionsTest {

    @TempDir Path dir;

    @Test
    void defaultsToRun() throws Exception {
        LaunchOptions cli = LaunchOptions.parse();
        assertThat(cli.action()).isEqualTo(Action.RUN);
        assertThat(cli.configDir()).isEmpty();
        assertThat(cli.logLevel()).isEmpty();
    }

    @Test
    void parsesEveryOption() throws Exception {
        LaunchOptions cli =
                LaunchOptions.parse("--config-dir", this.dir.toString(), "--log-level", "debug");
        assertThat(cli.configDir()).contains(this.dir.toAbsolutePath().normalize());
        assertThat(cli.logLevel()).contains("DEBUG");

        assertThat(LaunchOptions.parse("-h").action()).isEqualTo(Action.HELP);
        assertThat(LaunchOptions.parse("-V").action()).isEqualTo(Action.VERSION);
        assertThat(LaunchOptions.parse("--config-dir=" + this.dir).configDir())
                .contains(this.dir.toAbsolutePath().normalize());
        assertThat(LaunchOptions.parse("--license").action()).isEqualTo(Action.LICENSE);
        assertThat(LaunchOptions.parse("--warranty").action()).isEqualTo(Action.WARRANTY);
        assertThat(LaunchOptions.parse("--notice").action()).isEqualTo(Action.NOTICE);
    }

    @Test
    void collectsTheFilesToHash() throws Exception {
        LaunchOptions cli = LaunchOptions.parse("--hash", "a.iso", "b.iso", "--log-level", "info");

        assertThat(cli.action()).isEqualTo(Action.HASH);
        assertThat(cli.files()).containsExactly(Path.of("a.iso"), Path.of("b.iso"));
        assertThatThrownBy(() -> LaunchOptions.parse("--hash"))
                .isInstanceOf(LaunchOptions.UsageException.class)
                .hasMessageContaining("--hash");
    }

    @Test
    void collectsTheNetworkTasks() throws Exception {
        LaunchOptions cli =
                LaunchOptions.parse(
                        "--headless",
                        "--add-servers",
                        "server.met",
                        "--connect-server",
                        "1.2.3.4:4661",
                        "--search",
                        "ubuntu iso",
                        "--global");

        assertThat(cli.action()).isEqualTo(Action.RUN);
        assertThat(cli.headless()).isTrue();
        assertThat(cli.network().serverMet()).contains(Path.of("server.met"));
        assertThat(cli.network().server()).contains(new ServerAddress("1.2.3.4", 4661));
        assertThat(cli.network().search()).contains("ubuntu iso");
        assertThat(cli.network().global()).isTrue();
        assertThat(cli.network().isOneShot()).isTrue();

        LaunchOptions hello = LaunchOptions.parse("--hello", "localhost:61426");
        assertThat(hello.network().hello()).contains(new ServerAddress("localhost", 61426));
        assertThat(LaunchOptions.parse("--connect-server", "s.example:4242").network().isOneShot())
                .isFalse();

        String link = "ed2k://|file|a.iso|10|" + "ab".repeat(16) + "|/|sources,127.0.0.1:61426|/";
        LaunchOptions download = LaunchOptions.parse("--download", link);
        assertThat(download.network().downloads()).hasSize(1);
        assertThat(download.network().downloads().getFirst().sources()).hasSize(1);
        assertThat(download.network().isOneShot()).isTrue();
        assertThatThrownBy(() -> LaunchOptions.parse("--download", "ed2k://|server|1.2.3.4|4661|/"))
                .hasMessageContaining("Not an ed2k file link");

        assertThatThrownBy(() -> LaunchOptions.parse("--connect-server", "nowhere"))
                .hasMessageContaining("Invalid address for --connect-server");
        assertThatThrownBy(() -> LaunchOptions.parse("--global"))
                .hasMessageContaining("--global applies to --search");
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> LaunchOptions.parse("--bogus"))
                .hasMessageContaining("Unknown option")
                .hasMessageContaining("--bogus");
        assertThatThrownBy(() -> LaunchOptions.parse("--config-dir"))
                .hasMessageContaining("--config-dir");
        assertThatThrownBy(() -> LaunchOptions.parse("--log-level", "--help"))
                .hasMessageContaining("--log-level");
        assertThatThrownBy(() -> LaunchOptions.parse("--log-level", "loud"))
                .hasMessage("Invalid log level: loud");
    }
}
