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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Schedule;

/** Which paused download starts when another one leaves. */
@Timeout(30)
class StartNextDownloadTest {

    @TempDir
    Path root;

    private NjMuleEngine engine;
    private final Map<String, Ed2kLink.FileLink> links = new HashMap<>();
    private boolean cancelled;

    @AfterEach
    void stopTheEngine() throws Exception {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldStartAPausedDownloadWhenARunningOneIsCancelledButNotWhenOneThatWaitedIs() throws Exception {
        givenAnEngineThatStartsTheNextDownload();
        givenADownload("waiting.bin", "b1");
        givenADownload("paused.bin", "b2");
        givenTheDownloadIsPaused("waiting.bin");
        givenTheDownloadIsPaused("paused.bin");
        givenTheDownloadIsCancelled("waiting.bin");
        givenTheDownloadIsStillPaused("paused.bin");
        givenADownload("running.bin", "b3");

        whenTheDownloadIsCancelled("running.bin");

        thenTheCancelIsAccepted();
        thenTheStatusOfIs("paused.bin", DownloadInfo.Status.DOWNLOADING);
    }

    @Test
    void shouldNotStartADownloadTheSchedulerStoppedInstead() throws Exception {
        givenAnEngineThatStartsTheNextDownload();
        givenTheCategoriesFilmsOfHighAndMusicOfLowPriority();
        givenADownload("film.mkv", "c1");
        givenADownload("song.mp3", "c2");
        givenADownload("other.mp3", "c3");
        givenTheDownloadIsInCategory("film.mkv", 1);
        givenTheDownloadIsInCategory("song.mp3", 2);
        givenTheDownloadIsInCategory("other.mp3", 2);
        givenTheDownloadIsPaused("song.mp3");
        givenAScheduleRuleStoppingAllDayTheCategory(1);
        givenTheScheduleStoppedTheDownload("film.mkv");

        whenTheDownloadIsCancelled("other.mp3");

        thenTheRuleKeepsItStoppedThoughOfHigherPriority("film.mkv");
        thenTheStatusOfIs("song.mp3", DownloadInfo.Status.DOWNLOADING);
    }

    private void givenAnEngineThatStartsTheNextDownload() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.root, true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\ndownload.startNext=ANY\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
    }

    private void givenTheCategoriesFilmsOfHighAndMusicOfLowPriority() throws Exception {
        this.engine
                .setCategories(List.of(
                        new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.HIGH, "", false),
                        new Category(2, "Music", Optional.empty(), "", 2, DownloadPriority.LOW, "", false)))
                .get(5, TimeUnit.SECONDS);
    }

    private void givenADownload(String name, String hashByte) throws Exception {
        Ed2kLink.FileLink link = Ed2kLink.FileLink.of(name, 5000, Hash16.parseHex(hashByte.repeat(16)));
        this.links.put(name, link);
        this.engine.addDownload(link).get(5, TimeUnit.SECONDS);
    }

    private void givenTheDownloadIsInCategory(String name, int category) throws Exception {
        this.engine.setDownloadCategory(hash(name), category).get(5, TimeUnit.SECONDS);
    }

    private void givenTheDownloadIsPaused(String name) throws Exception {
        this.engine.pauseDownload(hash(name)).get(5, TimeUnit.SECONDS);
    }

    private void givenTheDownloadIsCancelled(String name) throws Exception {
        assertThat(this.engine.cancelDownload(hash(name)).get(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private void givenTheDownloadIsStillPaused(String name) throws Exception {
        assertThat(status(name)).as("nothing was running in its place").isEqualTo(DownloadInfo.Status.PAUSED);
    }

    private void givenAScheduleRuleStoppingAllDayTheCategory(int category) throws Exception {
        Schedule.Rule night = new Schedule.Rule(
                "night",
                true,
                EnumSet.allOf(DayOfWeek.class),
                LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT,
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.of(category),
                OptionalInt.empty());
        this.engine.updateSchedule(new Schedule(List.of(night))).get(5, TimeUnit.SECONDS);
    }

    private void givenTheScheduleStoppedTheDownload(String name) throws Exception {
        assertThat(status(name)).isEqualTo(DownloadInfo.Status.STOPPED);
    }

    private void whenTheDownloadIsCancelled(String name) throws Exception {
        this.cancelled = this.engine.cancelDownload(hash(name)).get(5, TimeUnit.SECONDS);
    }

    private void thenTheCancelIsAccepted() {
        assertThat(this.cancelled).isTrue();
    }

    private void thenTheStatusOfIs(String name, DownloadInfo.Status expected) throws Exception {
        assertThat(status(name)).isEqualTo(expected);
    }

    private void thenTheRuleKeepsItStoppedThoughOfHigherPriority(String name) throws Exception {
        assertThat(status(name))
                .as("the rule keeps it stopped, though of higher priority")
                .isEqualTo(DownloadInfo.Status.STOPPED);
    }

    private Hash16 hash(String name) {
        return this.links.get(name).hash();
    }

    private DownloadInfo.Status status(String name) throws Exception {
        Hash16 hash = hash(name);
        return this.engine.downloads().get(5, TimeUnit.SECONDS).stream()
                .filter(d -> d.hash().equals(hash))
                .findFirst()
                .orElseThrow()
                .status();
    }
}
