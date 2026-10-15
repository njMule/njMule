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
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.ImportOptions;
import org.njmule.core.api.ImportReport;
import org.njmule.core.config.Preferences;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.NodesDat;

/**
 * A dry run of the import on a real eMule profile from {@code NJMULE_EMULE_CONFIG}, which is only read; it prints the
 * outcome of each file, never names, hashes or other contents.
 */
@Tag("private-fixtures")
@EnabledIfEnvironmentVariable(named = "NJMULE_EMULE_CONFIG", matches = ".+")
class EmuleImportDryRunTest {

    @TempDir
    Path ours;

    private Path config;
    private EmuleImporter.Target target;
    private ImportReport report;
    private Map<ImportReport.Outcome, Long> counts;

    @Test
    void shouldReadEveryFileOfTheProfile() throws Exception {
        givenTheEmuleProfileOfTheEnvironment();
        givenATargetThatRefusesEveryChange();

        whenADryRunImportsEverything();

        thenNoItemFailed();
        thenSomeItemsWouldBeImported();
        thenNothingIsWrittenIntoOurConfiguration();
    }

    private void givenTheEmuleProfileOfTheEnvironment() {
        this.config = Path.of(System.getenv("NJMULE_EMULE_CONFIG"));
    }

    private void givenATargetThatRefusesEveryChange() {
        this.target = new EmuleImporter.Target() {
            @Override
            public Path configDir() {
                return EmuleImportDryRunTest.this.ours;
            }

            @Override
            public void updatePreferences(UnaryOperator<Preferences> change) {
                fail("a dry run changes nothing");
            }

            @Override
            public int addServers(Path serverMet) {
                return fail("a dry run changes nothing");
            }

            @Override
            public int addKadContacts(NodesDat.Contents contacts) {
                return fail("a dry run changes nothing");
            }

            @Override
            public int addCategories(List<Category> categories) {
                return fail("a dry run changes nothing");
            }

            @Override
            public int addKnownFiles(List<KnownMet.KnownFile> files, List<Known2Met.Entry> trees) {
                return fail("a dry run changes nothing");
            }

            @Override
            public void shareDirectory(Path directory) {
                fail("a dry run changes nothing");
            }

            @Override
            public String adoptDownload(Path met, Path data, int category) {
                return fail("a dry run changes nothing");
            }
        };
    }

    private void whenADryRunImportsEverything() throws Exception {
        this.report = new EmuleImporter(
                        this.config, new ImportOptions(true, true, true), this.target, EmuleImporter::isEmuleRunning)
                .run();
        this.counts = this.report.items().stream()
                .collect(Collectors.groupingBy(ImportReport.Item::outcome, Collectors.counting()));
        System.out.println("[private-fixtures] import dry run: " + this.counts);
    }

    private void thenNoItemFailed() {
        assertThat(this.report.items())
                .filteredOn(i -> i.outcome() == ImportReport.Outcome.FAILED)
                .isEmpty();
    }

    private void thenSomeItemsWouldBeImported() {
        assertThat(this.counts).containsKey(ImportReport.Outcome.WOULD_IMPORT);
    }

    private void thenNothingIsWrittenIntoOurConfiguration() throws Exception {
        try (var files = Files.list(this.ours)) {
            assertThat(files.count()).isZero();
        }
    }
}
