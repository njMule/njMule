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

import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadNote;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.persist.PreferencesCodec;

/** The engine searches Kad for notes on the files it knows, and only while Kad is connected. */
@Timeout(60)
class KadNotesEngineTest {

    private static final Hash16 FILM = Hash16.parseHex("a1".repeat(16));

    @TempDir
    Path dir;

    private Engine engine;
    private CompletableFuture<List<KadNote>> search;
    private List<KadNote> notes;

    @AfterEach
    void stop() {
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldNotSearchWhileKadDoesNotRun() throws Exception {
        this.givenAnEngineWithKad(false);
        this.givenADownloadOf(FILM);

        this.whenKadIsSearchedFor(FILM);

        this.thenTheSearchFailsBecause("Kad is not connected");
        this.thenTheFileHasNoNotes(FILM);
    }

    @Test
    void shouldNotSearchWhileKadKnowsNoNode() throws Exception {
        this.givenAnEngineWithKad(true);
        this.givenADownloadOf(FILM);

        this.whenKadIsSearchedFor(FILM);

        this.thenTheSearchFailsBecause("Kad is not connected");
    }

    @Test
    void shouldNotSearchForAFileItNeitherDownloadsNorShares() throws Exception {
        this.givenAnEngineWithKad(false);

        this.whenKadIsSearchedFor(FILM);

        this.thenTheSearchFailsBecause("Neither downloaded nor shared");
    }

    private void givenAnEngineWithKad(boolean kad) throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        int tcp;
        int udp;
        try (ServerSocket probe = new ServerSocket(0);
                DatagramSocket probeUdp = new DatagramSocket(0)) {
            tcp = probe.getLocalPort();
            udp = probeUdp.getLocalPort();
        }
        Files.writeString(
                dirs.config().resolve(PreferencesCodec.FILE_NAME),
                "net.tcpPort=" + tcp + "\nnet.udpPort=" + udp + "\nnet.kad.enabled=" + kad + "\n");
        this.engine = Engine.start(new EngineConfig(dirs), () -> false);
    }

    private void givenADownloadOf(Hash16 file) throws Exception {
        this.engine.addDownload(Ed2kLink.FileLink.of("film.mkv", 5000, file)).get(10, TimeUnit.SECONDS);
    }

    private void whenKadIsSearchedFor(Hash16 file) {
        this.search = this.engine.searchKadNotes(file);
    }

    private void thenTheSearchFailsBecause(String why) {
        assertThat(this.search)
                .failsWithin(10, TimeUnit.SECONDS)
                .withThrowableOfType(ExecutionException.class)
                .withMessageContaining(why);
    }

    private void thenTheFileHasNoNotes(Hash16 file) throws Exception {
        this.notes = this.engine.kadNotes(file).get(10, TimeUnit.SECONDS);
        assertThat(this.notes).isEmpty();
    }
}
