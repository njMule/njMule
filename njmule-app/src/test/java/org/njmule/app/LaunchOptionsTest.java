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
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ServerAddress;

class LaunchOptionsTest {

    @TempDir
    Path dir;

    @Test
    void defaultsToRun() throws Exception {
        LaunchOptions cli = LaunchOptions.parse();
        assertThat(cli.action()).isEqualTo(Action.RUN);
        assertThat(cli.configDir()).isEmpty();
        assertThat(cli.logLevel()).isEmpty();
    }

    @Test
    void parsesEveryOption() throws Exception {
        LaunchOptions cli = LaunchOptions.parse("--config-dir", this.dir.toString(), "--log-level", "debug");
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
        LaunchOptions cli = LaunchOptions.parse(
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
        assertThat(LaunchOptions.parse("--connect-server", "s.example:4242")
                        .network()
                        .isOneShot())
                .isFalse();

        String link = "ed2k://|file|a.iso|10|" + "ab".repeat(16) + "|/|sources,127.0.0.1:61426|/";
        LaunchOptions download = LaunchOptions.parse("--download", link);
        assertThat(download.network().downloads()).hasSize(1);
        assertThat(download.network().downloads().getFirst().sources()).hasSize(1);
        assertThat(download.network().isOneShot()).isTrue();
        LaunchOptions kad = LaunchOptions.parse("--kad-bootstrap", "127.0.0.1:14897", "--search", "x", "--kad");
        assertThat(kad.network().kadBootstrap()).contains(new ServerAddress("127.0.0.1", 14897));
        assertThat(kad.network().kad()).isTrue();
        assertThatThrownBy(() -> LaunchOptions.parse("--kad")).hasMessageContaining("--kad applies to --search");

        LaunchOptions share = LaunchOptions.parse("--share", "videos", "music");
        assertThat(share.network().shares())
                .containsExactly(
                        Path.of("videos").toAbsolutePath().normalize(),
                        Path.of("music").toAbsolutePath().normalize());
        assertThat(share.network().isOneShot()).isFalse();
        assertThatThrownBy(() -> LaunchOptions.parse("--download", "ed2k://|server|1.2.3.4|4661|/"))
                .hasMessageContaining("Not an ed2k file link");

        assertThatThrownBy(() -> LaunchOptions.parse("--connect-server", "nowhere"))
                .hasMessageContaining("Invalid address for --connect-server");
        assertThatThrownBy(() -> LaunchOptions.parse("--global")).hasMessageContaining("--global applies to --search");
    }

    @Test
    void takesLinksForTheRunningInstanceAsArguments() throws Exception {
        String link = "ed2k://|file|a.iso|10|" + "ab".repeat(16) + "|/";

        LaunchOptions cli = LaunchOptions.parse(link, "--log-level", "info");

        assertThat(cli.action()).isEqualTo(Action.RUN);
        assertThat(cli.links()).extracting(l -> l.name()).containsExactly("a.iso");
        assertThat(cli.network().isOneShot())
                .as("the links are not a download that ends the run")
                .isFalse();
        assertThatThrownBy(() -> LaunchOptions.parse("ed2k://|server|1.2.3.4|4661|/"))
                .isInstanceOf(LaunchOptions.UsageException.class);
    }

    @Test
    void collectsAnEmuleImport() throws Exception {
        LaunchOptions cli =
                LaunchOptions.parse("--import-emule", this.dir.toString(), "--dry-run", "--import-downloads");

        LaunchOptions.EmuleImport task = cli.network().emuleImport().orElseThrow();
        assertThat(task.dir()).isEqualTo(this.dir.toAbsolutePath().normalize());
        assertThat(task.options()).isEqualTo(new ImportOptions(true, false, true));
        assertThat(cli.network().isOneShot()).isTrue();
        assertThat(LaunchOptions.parse("--import-emule", "x", "--adopt-emule-identity")
                        .network()
                        .emuleImport()
                        .orElseThrow()
                        .options())
                .isEqualTo(new ImportOptions(false, true, false));
        assertThatThrownBy(() -> LaunchOptions.parse("--dry-run")).hasMessageContaining("apply to --import-emule");
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> LaunchOptions.parse("--bogus"))
                .hasMessageContaining("Unknown option")
                .hasMessageContaining("--bogus");
        assertThatThrownBy(() -> LaunchOptions.parse("--config-dir")).hasMessageContaining("--config-dir");
        assertThatThrownBy(() -> LaunchOptions.parse("--log-level", "--help")).hasMessageContaining("--log-level");
        assertThatThrownBy(() -> LaunchOptions.parse("--log-level", "loud")).hasMessage("Invalid log level: loud");
    }

    @Test
    void parsesTheWebInterfacePassword() throws Exception {
        LaunchOptions cli = LaunchOptions.parse("--webui-password", "--webui-port", "8080");
        assertThat(cli.action()).isEqualTo(Action.WEBUI_PASSWORD);
        assertThat(cli.webUi()).contains(new LaunchOptions.WebUiSetup(java.util.Optional.of(8080), false));
        assertThat(LaunchOptions.parse("--webui-password", "--webui-lan")
                        .webUi()
                        .orElseThrow())
                .isEqualTo(new LaunchOptions.WebUiSetup(java.util.Optional.empty(), true));
        assertThat(LaunchOptions.parse().webUi()).isEmpty();
        assertThatThrownBy(() -> LaunchOptions.parse("--webui-lan")).hasMessageContaining("apply to --webui-password");
        assertThatThrownBy(() -> LaunchOptions.parse("--webui-password", "--webui-port", "0"))
                .hasMessage("Invalid port for --webui-port: 0");
        assertThatThrownBy(() -> LaunchOptions.parse("--webui-password", "--webui-port", "x"))
                .hasMessage("Invalid port for --webui-port: x");
    }
}
