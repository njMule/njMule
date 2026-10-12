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
import org.njmule.app.CommandLine.Action;

class CommandLineTest {

    @TempDir Path dir;

    @Test
    void defaultsToRun() throws Exception {
        CommandLine cli = CommandLine.parse();
        assertThat(cli.action()).isEqualTo(Action.RUN);
        assertThat(cli.configDir()).isEmpty();
        assertThat(cli.logLevel()).isEmpty();
    }

    @Test
    void parsesEveryOption() throws Exception {
        CommandLine cli =
                CommandLine.parse("--config-dir", this.dir.toString(), "--log-level", "debug");
        assertThat(cli.configDir()).contains(this.dir.toAbsolutePath().normalize());
        assertThat(cli.logLevel()).contains("DEBUG");

        assertThat(CommandLine.parse("-h").action()).isEqualTo(Action.HELP);
        assertThat(CommandLine.parse("-V").action()).isEqualTo(Action.VERSION);
        assertThat(CommandLine.parse("--license").action()).isEqualTo(Action.LICENSE);
        assertThat(CommandLine.parse("--warranty").action()).isEqualTo(Action.WARRANTY);
        assertThat(CommandLine.parse("--notice").action()).isEqualTo(Action.NOTICE);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> CommandLine.parse("--bogus"))
                .hasMessage("Unknown option: --bogus");
        assertThatThrownBy(() -> CommandLine.parse("--config-dir"))
                .hasMessage("Missing value for --config-dir");
        assertThatThrownBy(() -> CommandLine.parse("--log-level", "--help"))
                .hasMessage("Missing value for --log-level");
        assertThatThrownBy(() -> CommandLine.parse("--log-level", "loud"))
                .hasMessage("Invalid log level: loud");
    }
}
