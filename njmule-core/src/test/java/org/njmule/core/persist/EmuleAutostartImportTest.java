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
package org.njmule.core.persist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.config.Preferences;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.NodesDat;

class EmuleAutostartImportTest {

    @TempDir
    Path dir;

    private ImportReport report;

    @Test
    void shouldTellThatEmuleStartsAtLogin() throws IOException {
        this.givenEmulePreferences("[eMule]\nNick=Tester\nAutoStart=1\n");

        this.whenTheProfileIsChecked();

        this.thenTheReportSaysItStartsAtLogin(true);
        this.thenThePreferencesDetailContains("start at login");
    }

    @Test
    void shouldNotTellItWhenEmuleDoesNotStartAtLogin() throws IOException {
        this.givenEmulePreferences("[eMule]\nNick=Tester\nAutoStart=0\n");

        this.whenTheProfileIsChecked();

        this.thenTheReportSaysItStartsAtLogin(false);
    }

    @Test
    void shouldNotTellItWithoutEmulePreferences() throws IOException {
        this.givenNoEmulePreferences();

        this.whenTheProfileIsChecked();

        this.thenTheReportSaysItStartsAtLogin(false);
    }

    private void givenEmulePreferences(String text) throws IOException {
        Files.createDirectories(this.emule());
        Files.writeString(this.emule().resolve("preferences.ini"), text, StandardCharsets.US_ASCII);
    }

    private void givenNoEmulePreferences() throws IOException {
        Files.createDirectories(this.emule());
    }

    private void whenTheProfileIsChecked() throws IOException {
        Path ours = Files.createDirectories(this.dir.resolve("njmule"));
        this.report = new EmuleImporter(
                        this.emule(), new ImportOptions(true, false, false), new Untouched(ours), () -> false)
                .run();
    }

    private void thenTheReportSaysItStartsAtLogin(boolean expected) {
        assertThat(this.report.startAtLogin()).isEqualTo(expected);
    }

    private void thenThePreferencesDetailContains(String text) {
        assertThat(this.report.items())
                .filteredOn(i -> i.file().endsWith("preferences.ini"))
                .singleElement()
                .satisfies(i -> assertThat(i.detail()).contains(text));
    }

    private Path emule() {
        return this.dir.resolve("emule");
    }

    /** A target a check never changes. */
    private record Untouched(Path configDir) implements EmuleImporter.Target {
        @Override
        public void updatePreferences(UnaryOperator<Preferences> change) {
            throw new UnsupportedOperationException("a check changes nothing");
        }

        @Override
        public int addServers(Path serverMet) {
            throw new UnsupportedOperationException("a check changes nothing");
        }

        @Override
        public int addKadContacts(NodesDat.Contents contacts) {
            throw new UnsupportedOperationException("a check changes nothing");
        }

        @Override
        public int addKnownFiles(List<KnownMet.KnownFile> files, List<Known2Met.Entry> trees) {
            throw new UnsupportedOperationException("a check changes nothing");
        }

        @Override
        public void shareDirectory(Path directory) {
            throw new UnsupportedOperationException("a check changes nothing");
        }

        @Override
        public int addCategories(List<Category> categories) {
            throw new UnsupportedOperationException("a check changes nothing");
        }

        @Override
        public String adoptDownload(Path met, Path data, int category) {
            throw new UnsupportedOperationException("a check changes nothing");
        }
    }
}
