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
package org.njmule.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.format.CancelledMet;

class CancelledFilesTest {

    private static final Hash16 FILM = hash(1);
    private static final Hash16 SONG = hash(2);

    @TempDir
    Path dir;

    private Path file;
    private CancelledFiles cancelled;
    private Predicate<Hash16> snapshot;

    @BeforeEach
    void nameTheFile() {
        this.file = this.dir.resolve(CancelledFiles.FILE_NAME);
    }

    @Test
    void shouldRememberCancelledFilesAcrossLoadsWithoutListingThem() throws Exception {
        givenANewListWithout(FILM);

        whenTheFileIsCancelled(FILM);

        thenAReloadedListContains(FILM);
        thenAReloadedListDoesNotContain(SONG);
        thenTheSavedListHoldsOneKeyOtherThan(FILM);
    }

    @Test
    void shouldReadTheOldFormat() throws Exception {
        givenAListInTheOldFormatWith(FILM);

        whenTheListIsLoaded();

        thenTheListContains(FILM);
    }

    @Test
    void shouldGiveTheOldFormatKeysOfItsOwn() throws Exception {
        givenAListInTheOldFormatWith(FILM);
        givenTheListIsLoaded();

        whenTheFileIsCancelled(SONG);

        thenTheSavedListIsNotInTheOldFormat();
        thenAReloadedListContains(FILM);
    }

    @Test
    void shouldReadADamagedListFromItsBackup() throws Exception {
        givenTheListIsLoaded();
        givenTheFileIsCancelled(FILM);
        givenTheFileIsCancelled(SONG);
        givenTheSavedListIsDamaged();

        whenTheListIsLoaded();

        thenTheListContainsAsBeforeTheLastChange(FILM);
    }

    @Test
    void shouldKeepTheListAsItWasInASnapshot() throws Exception {
        givenTheListIsLoaded();
        givenTheFileIsCancelled(FILM);
        givenASnapshotIsTaken();
        givenTheFileIsCancelled(SONG);

        whenTheListIsCleared();

        thenAmongTheFilesTheSnapshotCancelsOnly(List.of(FILM, SONG), FILM);
        thenTheListDoesNotContain(FILM);
        thenTheListIsEmpty();
    }

    private void givenANewListWithout(Hash16 hash) throws IOException {
        givenTheListIsLoaded();
        assertThat(this.cancelled.contains(hash)).isFalse();
    }

    private void givenTheListIsLoaded() throws IOException {
        this.cancelled = CancelledFiles.load(this.file, new SecureRandom());
    }

    private void givenAListInTheOldFormatWith(Hash16 hash) throws IOException {
        // The old format: header 0x0E, then the plain hashes.
        byte[] old = new byte[1 + 4 + 17];
        old[0] = 0x0E;
        old[1] = 1;
        hash.copyTo(old, 5);
        Files.write(this.file, old);
    }

    private void givenTheFileIsCancelled(Hash16 hash) throws IOException {
        this.cancelled.add(hash);
    }

    private void givenTheSavedListIsDamaged() throws IOException {
        Files.write(this.file, new byte[] {0x0F, 1, 2});
    }

    private void givenASnapshotIsTaken() {
        this.snapshot = this.cancelled.snapshot();
    }

    private void whenTheListIsLoaded() throws IOException {
        this.cancelled = CancelledFiles.load(this.file, new SecureRandom());
    }

    private void whenTheFileIsCancelled(Hash16 hash) throws IOException {
        this.cancelled.add(hash);
    }

    private void whenTheListIsCleared() throws IOException {
        this.cancelled.clear();
    }

    private void thenTheListContains(Hash16 hash) {
        assertThat(this.cancelled.contains(hash)).isTrue();
    }

    private void thenTheListContainsAsBeforeTheLastChange(Hash16 hash) {
        assertThat(this.cancelled.contains(hash))
                .as("as it was before the last change")
                .isTrue();
    }

    private void thenTheListDoesNotContain(Hash16 hash) {
        assertThat(this.cancelled.contains(hash)).isFalse();
    }

    private void thenTheListIsEmpty() {
        assertThat(this.cancelled.size()).isZero();
    }

    private void thenAReloadedListContains(Hash16 hash) throws IOException {
        assertThat(CancelledFiles.load(this.file, new SecureRandom()).contains(hash))
                .isTrue();
    }

    private void thenAReloadedListDoesNotContain(Hash16 hash) throws IOException {
        assertThat(CancelledFiles.load(this.file, new SecureRandom()).contains(hash))
                .isFalse();
    }

    private void thenTheSavedListHoldsOneKeyOtherThan(Hash16 hash) throws IOException {
        CancelledMet.Contents contents = CancelledMet.read(Files.readAllBytes(this.file));
        assertThat(contents.keys())
                .as("keys, not file hashes")
                .doesNotContain(hash)
                .hasSize(1);
    }

    private void thenTheSavedListIsNotInTheOldFormat() throws IOException {
        assertThat(CancelledMet.read(Files.readAllBytes(this.file)).legacy()).isFalse();
    }

    private void thenAmongTheFilesTheSnapshotCancelsOnly(List<Hash16> files, Hash16 expected) {
        assertThat(files.stream().filter(this.snapshot).toList()).containsExactly(expected);
    }

    private static Hash16 hash(int seed) {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) seed;
        return Hash16.of(bytes);
    }
}
