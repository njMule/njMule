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
package org.njmule.core.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.InstallKind;

class ApplyPlanTest {

    @TempDir
    Path dir;

    private ApplyPlan plan;
    private Optional<ApplyPlan> read;
    private Exception failure;

    @Test
    void shouldReadThePlanAsItWasKept() throws IOException {
        givenThePlan(new ApplyPlan(
                "0.2.0",
                "0.1.0",
                InstallKind.TAR,
                Path.of("/home/u/.cache/njmule/update/0.2.0/njmule-0.2.0-linux-x64.tar.xz"),
                Optional.of(Path.of("/home/u/.cache/njmule/update/0.2.0/files")),
                Optional.of(Path.of("/home/u/opt/njmule")),
                Optional.of("njmule-gui"),
                true,
                false,
                Optional.of(Path.of("/home/u/opt/njmule/njmule-gui")),
                List.of("--config-dir", "/home/u/njmule data", ""),
                ApplyPlan.State.ROLLED_BACK,
                Optional.of("the new version stopped with code 3"),
                OptionalLong.of(4242),
                Instant.parse("2026-11-03T08:00:00Z")));
        givenItKept();

        whenRead();

        thenItReadsAsKept();
    }

    @Test
    void shouldReadAPlanWithoutTheFilesOfAnInstaller() throws IOException {
        givenThePlan(new ApplyPlan(
                "0.2.0",
                "0.1.0",
                InstallKind.MSI,
                Path.of("C:\\Users\\u\\AppData\\Local\\njMule\\cache\\update\\0.2.0\\njmule.msi"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                true,
                Optional.empty(),
                List.of(),
                ApplyPlan.State.HANDED_OVER,
                Optional.empty(),
                OptionalLong.empty(),
                Instant.parse("2026-11-03T08:00:00Z")));
        givenItKept();

        whenRead();

        thenItReadsAsKept();
    }

    @Test
    void shouldReadNothingWhenNoPlanIsKept() {
        whenRead();

        thenNothingWasRead();
    }

    @Test
    void shouldRefuseADamagedPlan() throws IOException {
        givenTheFileHolds(
                "version=0.2.0\nfrom=0.1.0\nkind=zip\npackage=n.zip\nstate=sleeping\ntime=2026-11-03T08:00:00Z\n");

        whenRead();

        thenItFailedWith("damaged");
    }

    @Test
    void shouldRefuseAPlanThatLacksAPart() throws IOException {
        givenTheFileHolds("version=0.2.0\n");

        whenRead();

        thenItFailedWith("lacks");
    }

    private void givenThePlan(ApplyPlan chosen) {
        this.plan = chosen;
    }

    private void givenItKept() throws IOException {
        this.plan.save(file());
    }

    private void givenTheFileHolds(String text) throws IOException {
        Files.writeString(file(), text);
    }

    private void whenRead() {
        try {
            this.read = ApplyPlan.load(file());
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void thenItReadsAsKept() {
        assertThat(this.read).contains(this.plan);
    }

    private void thenNothingWasRead() {
        assertThat(this.read).isEmpty();
    }

    private void thenItFailedWith(String words) {
        assertThat(this.failure).isInstanceOf(IOException.class).hasMessageContaining(words);
    }

    private Path file() {
        return this.dir.resolve(UpdateFiles.PLAN);
    }
}
