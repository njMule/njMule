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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.UpdateChannel;
import org.semver4j.Semver;

class UpdateStateTest {

    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");

    @TempDir
    Path dir;

    private UpdateState state = UpdateState.NONE;
    private UpdateState loaded;

    @Test
    void shouldReadBackWhatItKept() throws IOException {
        givenChecked(UpdateChannel.STABLE, "0.2.0");
        givenChecked(UpdateChannel.PRERELEASE, "0.3.0-beta.1");
        givenSkipped("0.2.0");

        whenKeptAndReadBack();

        thenItIsTheSame();
    }

    @Test
    void shouldKeepTheHighestVersionAChannelOffered() {
        givenChecked(UpdateChannel.STABLE, "0.3.0");

        whenChecked(UpdateChannel.STABLE, "0.2.0");

        thenTheHighestOfferedIs(UpdateChannel.STABLE, "0.3.0");
    }

    @Test
    void shouldStartFromNothingWithoutItsFile() {
        whenRead();

        thenNothingIsKnown();
    }

    @Test
    void shouldTakeWhatItCanOfADamagedFile() throws IOException {
        givenTheFile("lastCheck=yesterday\nhighest.stable=0.2.0\nhighest.prerelease=soon\nskipped=../x\n");

        whenRead();

        thenTheLastCheckIsUnknown();
        thenTheHighestOfferedIs(UpdateChannel.STABLE, "0.2.0");
        thenNoVersionIsSkipped();
    }

    private void givenChecked(UpdateChannel channel, String version) {
        this.state = this.state.checked(NOW, channel, Semver.parse(version));
    }

    private void givenSkipped(String version) {
        this.state = this.state.skipping(version);
    }

    private void givenTheFile(String text) throws IOException {
        Files.writeString(file(), text);
    }

    private void whenKeptAndReadBack() throws IOException {
        this.state.save(file());
        this.loaded = UpdateState.load(file());
    }

    private void whenChecked(UpdateChannel channel, String version) {
        this.loaded = this.state.checked(NOW, channel, Semver.parse(version));
    }

    private void whenRead() {
        this.loaded = UpdateState.load(file());
    }

    private void thenItIsTheSame() {
        assertThat(this.loaded).isEqualTo(this.state);
    }

    private void thenTheHighestOfferedIs(UpdateChannel channel, String version) {
        assertThat(this.loaded.highest().get(channel)).hasToString(version);
    }

    private void thenNothingIsKnown() {
        assertThat(this.loaded).isEqualTo(UpdateState.NONE);
    }

    private void thenTheLastCheckIsUnknown() {
        assertThat(this.loaded.lastCheck()).isEmpty();
    }

    private void thenNoVersionIsSkipped() {
        assertThat(this.loaded.skipped()).isEmpty();
    }

    private Path file() {
        return this.dir.resolve(UpdateState.FILE_NAME);
    }
}
