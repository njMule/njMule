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
