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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.FreePorts;
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

    private AppDirectories dirs;
    private Path otherList;
    private NjMuleEngine engine;
    private EngineLoop loop;
    private Network network;
    private int removed;
    private Throwable exception;

    @AfterEach
    void stopEverything() throws Exception {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.loop != null) {
            this.loop.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"127.0.0.2, blocked by the IP filter", "0.1.2.3,   a LAN address while LAN addresses are filtered"})
    void shouldNotConnectToAServerTheFiltersRefuse(String server, String why) throws Exception {
        givenAProfileWithThePreferences("security.filterLan=true\n");
        givenAnIpFilterBlocking("127.0.0.2");
        givenTheEngineIsStarted();

        whenTheEngineConnectsTo(server, 4661);

        thenExceptionOccurred(ExecutionException.class, IllegalArgumentException.class, why);
    }

    @Test
    void shouldRemoveTheServersThatCanBeFoundWhenOthersCannot() throws Exception {
        givenAProfileWithThePreferences("");
        givenAnotherServerListWith("81.2.3.4", 4661);
        givenTheEngineIsStarted();
        givenTheServersOfTheOtherListAreAdded();

        // An address with no IPv4 form cannot be resolved for the list, without asking any name server.
        whenTheServersAtPortAreRemoved(4661, "::1", "81.2.3.4");

        thenTheNumberOfServersRemovedIs(1);
        thenNoServerIsLeft();
    }

    @Test
    void shouldIgnoreDatagramsFromIpv6Addresses() throws Exception {
        givenAProfileWithThePreferences("");
        givenTheNetworkOnAnEngineLoop();

        whenASearchResultAndAServerStatusArriveFrom("::1", 4665);

        thenNoExceptionOccurred();
    }

    private void givenAProfileWithThePreferences(String preferences) throws IOException {
        this.dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(this.dirs.config());
        Files.writeString(
                this.dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.kad.enabled=false\n" + preferences);
    }

    private void givenAnIpFilterBlocking(String address) throws IOException {
        int ip = Ipv4.parse(address);
        Files.write(
                this.dirs.config().resolve(Network.IPFILTER_FILE),
                IpFilterDat.write(List.of(new IpFilterDat.Range(ip, ip, 0, "blocked server"))));
    }

    private void givenAnotherServerListWith(String address, int port) throws IOException {
        this.otherList = this.dir.resolve("other.met");
        Files.write(
                this.otherList, ServerMet.write(List.of(new ServerMet.Server(Ipv4.parse(address), port, List.of()))));
    }

    private void givenTheEngineIsStarted() throws IOException {
        this.engine = NjMuleEngine.start(new EngineConfig(this.dirs));
    }

    private void givenTheServersOfTheOtherListAreAdded() throws Exception {
        this.engine.addServers(this.otherList).get(5, TimeUnit.SECONDS);
    }

    private void givenTheNetworkOnAnEngineLoop() throws Exception {
        Preferences prefs = Preferences.defaults();
        this.loop = EngineLoop.start();
        this.network = this.loop
                .submit(() -> new Network(
                        this.loop,
                        this.dirs.config(),
                        () -> prefs,
                        event -> {},
                        (level, line) -> {},
                        Hash16.ZERO,
                        this.dirs.data().resolve("temp"),
                        () -> this.dirs.data().resolve("incoming"),
                        Optional.empty(),
                        false))
                .get(10, TimeUnit.SECONDS);
    }

    private void whenTheEngineConnectsTo(String host, int port) {
        this.exception = catchThrowable(() ->
                this.engine.connect(Optional.of(new ServerAddress(host, port))).get(5, TimeUnit.SECONDS));
    }

    private void whenTheServersAtPortAreRemoved(int port, String... hosts) throws Exception {
        List<ServerAddress> servers =
                Arrays.stream(hosts).map(host -> new ServerAddress(host, port)).toList();
        this.removed = this.engine.removeServers(servers).get(5, TimeUnit.SECONDS);
    }

    private void whenASearchResultAndAServerStatusArriveFrom(String address, int port) {
        InetSocketAddress from = new InetSocketAddress(InetAddress.ofLiteral(address), port);
        FileEntry file = new FileEntry(
                Hash16.parseHex("ab".repeat(16)),
                0,
                0,
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), "x.iso"),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), 10)));
        Packet results = ServerDatagrams.searchResults(List.of(file));
        Packet status = Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSERVSTATRES.code(), new byte[12]);
        this.exception = catchThrowable(() -> this.loop
                .submit(() -> {
                    this.network.datagram(from, results);
                    this.network.datagram(from, status);
                    this.network.close();
                    return null;
                })
                .get(10, TimeUnit.SECONDS));
    }

    private void thenExceptionOccurred(
            Class<? extends Throwable> type, Class<? extends Throwable> cause, String description) {
        assertThat(this.exception).as(description).isInstanceOf(type).hasCauseInstanceOf(cause);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheNumberOfServersRemovedIs(int expected) {
        assertThat(this.removed).isEqualTo(expected);
    }

    private void thenNoServerIsLeft() throws Exception {
        assertThat(this.engine.servers().get(5, TimeUnit.SECONDS)).isEmpty();
    }
}
