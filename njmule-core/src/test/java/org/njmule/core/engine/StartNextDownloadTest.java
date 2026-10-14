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
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
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

    @Test
    void cancellingADownloadThatWaitedStartsNoOther() throws Exception {
        Ed2kLink.FileLink waiting = Ed2kLink.FileLink.of("waiting.bin", 5000, Hash16.parseHex("b1".repeat(16)));
        Ed2kLink.FileLink paused = Ed2kLink.FileLink.of("paused.bin", 5000, Hash16.parseHex("b2".repeat(16)));
        Ed2kLink.FileLink running = Ed2kLink.FileLink.of("running.bin", 5000, Hash16.parseHex("b3".repeat(16)));
        try (NjMuleEngine engine = this.start()) {
            engine.addDownload(waiting).get(5, TimeUnit.SECONDS);
            engine.addDownload(paused).get(5, TimeUnit.SECONDS);
            engine.pauseDownload(waiting.hash()).get(5, TimeUnit.SECONDS);
            engine.pauseDownload(paused.hash()).get(5, TimeUnit.SECONDS);

            assertThat(engine.cancelDownload(waiting.hash()).get(5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(status(engine, paused.hash()))
                    .as("nothing was running in its place")
                    .isEqualTo(DownloadInfo.Status.PAUSED);

            engine.addDownload(running).get(5, TimeUnit.SECONDS);
            assertThat(engine.cancelDownload(running.hash()).get(5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(status(engine, paused.hash())).isEqualTo(DownloadInfo.Status.DOWNLOADING);
        }
    }

    @Test
    void aDownloadTheSchedulerStoppedIsNotStartedInstead() throws Exception {
        Ed2kLink.FileLink film = Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("c1".repeat(16)));
        Ed2kLink.FileLink song = Ed2kLink.FileLink.of("song.mp3", 5000, Hash16.parseHex("c2".repeat(16)));
        Ed2kLink.FileLink other = Ed2kLink.FileLink.of("other.mp3", 5000, Hash16.parseHex("c3".repeat(16)));
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
                OptionalInt.of(1),
                OptionalInt.empty());
        try (NjMuleEngine engine = this.start()) {
            engine.setCategories(List.of(
                            new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.HIGH, "", false),
                            new Category(2, "Music", Optional.empty(), "", 2, DownloadPriority.LOW, "", false)))
                    .get(5, TimeUnit.SECONDS);
            for (Ed2kLink.FileLink link : List.of(film, song, other)) {
                engine.addDownload(link).get(5, TimeUnit.SECONDS);
            }
            engine.setDownloadCategory(film.hash(), 1).get(5, TimeUnit.SECONDS);
            engine.setDownloadCategory(song.hash(), 2).get(5, TimeUnit.SECONDS);
            engine.setDownloadCategory(other.hash(), 2).get(5, TimeUnit.SECONDS);
            engine.pauseDownload(song.hash()).get(5, TimeUnit.SECONDS);
            engine.updateSchedule(new Schedule(List.of(night))).get(5, TimeUnit.SECONDS);
            assertThat(status(engine, film.hash())).isEqualTo(DownloadInfo.Status.STOPPED);

            engine.cancelDownload(other.hash()).get(5, TimeUnit.SECONDS);

            assertThat(status(engine, film.hash()))
                    .as("the rule keeps it stopped, though of higher priority")
                    .isEqualTo(DownloadInfo.Status.STOPPED);
            assertThat(status(engine, song.hash())).isEqualTo(DownloadInfo.Status.DOWNLOADING);
        }
    }

    private static DownloadInfo.Status status(NjMuleEngine engine, Hash16 hash) throws Exception {
        return engine.downloads().get(5, TimeUnit.SECONDS).stream()
                .filter(d -> d.hash().equals(hash))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private NjMuleEngine start() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.root, true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(
                dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\ndownload.startNext=ANY\n");
        return NjMuleEngine.start(new EngineConfig(dirs));
    }
}
