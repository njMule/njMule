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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.util.Ipv4;

/** The shipped server list, with both networks off: the servers are listed, never reached. */
@Timeout(30)
class DefaultListsEngineTest {

    @TempDir
    Path dir;

    private AppDirectories dirs;
    private List<ServerInfo> servers;
    private String disabled;

    @BeforeEach
    void allowTheShippedLists() {
        this.disabled = System.clearProperty(DefaultLists.DISABLED_PROPERTY);
    }

    @AfterEach
    void restoreTheTestSetting() {
        if (this.disabled != null) {
            System.setProperty(DefaultLists.DISABLED_PROPERTY, this.disabled);
        }
    }

    @Test
    void shouldStartANewProfileWithTheShippedServers() throws Exception {
        givenANewProfileWithoutNetworks();
        whenTheEngineStarts();
        thenItKnowsTheShippedServers();
    }

    @Test
    void shouldKeepEmptyTheServerListAProfileEmptied() throws Exception {
        givenANewProfileWithoutNetworks();
        givenAnEmptyServerList();
        whenTheEngineStarts();
        thenItKnowsNoServer();
    }

    private void givenANewProfileWithoutNetworks() throws Exception {
        this.dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(this.dirs.config());
        int port = FreePorts.tcp();
        Files.writeString(
                this.dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nnet.ed2k.enabled=false\nnet.kad.enabled=false\n");
    }

    private void givenAnEmptyServerList() throws Exception {
        Files.write(this.dirs.config().resolve("server.met"), ServerMet.write(List.of()));
    }

    private void whenTheEngineStarts() throws Exception {
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(this.dirs))) {
            this.servers = engine.servers().get(5, TimeUnit.SECONDS);
        }
    }

    private void thenItKnowsTheShippedServers() {
        assertThat(this.servers)
                .extracting(ServerInfo::address)
                .containsExactlyInAnyOrderElementsOf(DefaultLists.servers().stream()
                        .map(s -> Ipv4.format(s.ip()) + ":" + s.port())
                        .toList());
    }

    private void thenItKnowsNoServer() {
        assertThat(this.servers).isEmpty();
    }
}
