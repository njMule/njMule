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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

@Timeout(30)
class ServerControlTest {

    @TempDir
    Path dir;

    @Test
    void aServerTheFiltersRefuseIsNotConnectedTo() throws Exception {
        AppDirectories dirs = this.dirs("security.filterLan=true\n");
        Files.write(
                dirs.config().resolve(Network.IPFILTER_FILE),
                IpFilterDat.write(List.of(
                        new IpFilterDat.Range(Ipv4.of(127, 0, 0, 2), Ipv4.of(127, 0, 0, 2), 0, "blocked server"))));
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs))) {
            assertThatThrownBy(() -> engine.connect(Optional.of(new ServerAddress("127.0.0.2", 4661)))
                            .get(5, TimeUnit.SECONDS))
                    .as("blocked by the IP filter")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> engine.connect(Optional.of(new ServerAddress("0.1.2.3", 4661)))
                            .get(5, TimeUnit.SECONDS))
                    .as("a LAN address while LAN addresses are filtered")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void serversThatCanBeFoundAreRemovedWhenOthersCannot() throws Exception {
        AppDirectories dirs = this.dirs("");
        Path met = this.dir.resolve("other.met");
        Files.write(met, ServerMet.write(List.of(new ServerMet.Server(Ipv4.of(81, 2, 3, 4), 4661, List.of()))));
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs))) {
            engine.addServers(met).get(5, TimeUnit.SECONDS);

            // An address with no IPv4 form cannot be resolved for the list, without asking any name server.
            int removed = engine.removeServers(
                            List.of(new ServerAddress("::1", 4661), new ServerAddress("81.2.3.4", 4661)))
                    .get(5, TimeUnit.SECONDS);

            assertThat(removed).isEqualTo(1);
            assertThat(engine.servers().get(5, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void datagramsFromIpv6AddressesAreIgnored() throws Exception {
        AppDirectories dirs = this.dirs("");
        Preferences prefs = Preferences.defaults();
        try (EngineLoop loop = EngineLoop.start()) {
            Network network = loop.submit(() -> new Network(
                            loop,
                            dirs.config(),
                            () -> prefs,
                            event -> {},
                            (level, line) -> {},
                            Hash16.ZERO,
                            dirs.data().resolve("temp"),
                            () -> dirs.data().resolve("incoming"),
                            Optional.empty()))
                    .get(10, TimeUnit.SECONDS);
            InetSocketAddress ipv6 = new InetSocketAddress(InetAddress.ofLiteral("::1"), 4665);
            FileEntry file = new FileEntry(
                    Hash16.parseHex("ab".repeat(16)),
                    0,
                    0,
                    List.of(
                            new Tag.StringTag(TagName.id(FileTag.FILENAME), "x.iso"),
                            new Tag.IntTag(TagName.id(FileTag.FILESIZE), 10)));
            Packet results = ServerDatagrams.searchResults(List.of(file));
            Packet status = Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSERVSTATRES.code(), new byte[12]);

            loop.submit(() -> {
                        network.datagram(ipv6, results);
                        network.datagram(ipv6, status);
                        network.close();
                        return null;
                    })
                    .get(10, TimeUnit.SECONDS);
        }
    }

    private AppDirectories dirs(String preferences) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + freeTcpPort() + "\nnet.kad.enabled=false\n" + preferences);
        return dirs;
    }

    private static int freeTcpPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
