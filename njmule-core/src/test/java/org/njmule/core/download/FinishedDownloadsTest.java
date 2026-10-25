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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;

class FinishedDownloadsTest {

    private static final Hash16 FILM = Hash16.parseHex("a1".repeat(16));
    private static final Hash16 TALK = Hash16.parseHex("b2".repeat(16));

    @TempDir
    Path dir;

    private FinishedDownloads finished;
    private List<Hash16> cleared = List.of();
    private boolean removed;

    @Test
    void shouldShowADownloadThatCompletedAsCompleteWithEveryByte() {
        givenNoneCompleted();

        whenCompleted(FILM, "film.mkv", 3);

        thenTheDownloadsAre(new DownloadInfo(
                FILM,
                "film.mkv",
                5000,
                5000,
                DownloadInfo.Status.COMPLETE,
                DownloadPriority.HIGH,
                3,
                0,
                0,
                0,
                new PartMap(5000, List.of(), List.of(), List.of())));
    }

    @Test
    void shouldKeepTheCompletedDownloadsAcrossARestart() {
        givenNoneCompleted();
        givenCompleted(FILM, "film.mkv", 0);
        givenCompleted(TALK, "talk.mp3", 1);

        whenLoadedAgain();

        thenTheNamesAre("film.mkv", "talk.mp3");
    }

    @Test
    void shouldForgetEveryCompletedDownloadWhenCleared() {
        givenNoneCompleted();
        givenCompleted(FILM, "film.mkv", 0);
        givenCompleted(TALK, "talk.mp3", 0);

        whenCleared();

        thenTheClearedAre(FILM, TALK);
        thenNoneIsLeftAfterARestart();
    }

    @Test
    void shouldForgetACompletedDownloadDownloadedAgain() {
        givenNoneCompleted();
        givenCompleted(FILM, "film.mkv", 0);
        givenCompleted(TALK, "talk.mp3", 0);

        whenRemoved(FILM);

        thenItWasThere();
        thenTheNamesAre("talk.mp3");
    }

    @Test
    void shouldStartWithNoneFromAFileItCannotRead() throws Exception {
        givenTheFileHolds("{\"format\":1,\"downloads\":[{\"hash\":\"nothex\"}]}");

        whenLoadedAgain();

        thenTheNamesAre();
    }

    private void givenNoneCompleted() {
        this.finished = FinishedDownloads.load(this.dir);
    }

    private void givenCompleted(Hash16 hash, String name, int category) {
        whenCompleted(hash, name, category);
    }

    private void givenTheFileHolds(String json) throws Exception {
        Files.writeString(this.dir.resolve(FinishedDownloads.FILE_NAME), json, StandardCharsets.UTF_8);
    }

    // As the download manager reports it the moment it completes, sources and rate included.
    private void whenCompleted(Hash16 hash, String name, int category) {
        this.finished.add(new DownloadInfo(
                hash,
                name,
                5000,
                5000,
                DownloadInfo.Status.COMPLETE,
                DownloadPriority.HIGH,
                category,
                4,
                1,
                2048,
                new PartMap(5000, List.of(), List.of(), List.of(2))));
    }

    private void whenLoadedAgain() {
        this.finished = FinishedDownloads.load(this.dir);
    }

    private void whenCleared() {
        this.cleared = this.finished.clear();
    }

    private void whenRemoved(Hash16 hash) {
        this.removed = this.finished.remove(hash);
    }

    private void thenTheDownloadsAre(DownloadInfo... downloads) {
        assertThat(this.finished.downloads()).containsExactly(downloads);
    }

    private void thenTheNamesAre(String... names) {
        assertThat(this.finished.downloads()).extracting(DownloadInfo::name).containsExactly(names);
    }

    private void thenTheClearedAre(Hash16... hashes) {
        assertThat(this.cleared).containsExactly(hashes);
    }

    private void thenNoneIsLeftAfterARestart() {
        assertThat(FinishedDownloads.load(this.dir).downloads()).isEmpty();
    }

    private void thenItWasThere() {
        assertThat(this.removed).isTrue();
    }
}
