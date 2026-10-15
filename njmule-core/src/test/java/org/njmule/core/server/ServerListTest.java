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
package org.njmule.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

class ServerListTest {

    @TempDir
    Path dir;

    private final ServerList list = new ServerList();
    private ServerList loaded;
    private List<String> staticLines;
    private boolean done;
    private List<ServerMet.Server> candidates;
    private List<ServerList.Address> dead;
    private boolean supportsObfuscation;
    private Optional<String> version;

    @Test
    void shouldDropTheControlCharactersOfTheNamesAServerListGives() {
        whenTheServersOfAServerListAreTaken(server("1.2.3.4", 4661)
                .with(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), "\u001b[31mBig\u0007 server"))
                .with(new Tag.StringTag(TagName.id(ServerTag.DESCRIPTION), "line\r\nbreak")));

        thenTheServerIsNamed("[31mBig server", "linebreak");
    }

    @ParameterizedTest
    @CsvSource({"1.2.3.4, 4661", "1.2.3.4, 0", "127.0.0.1, 4661", "0.0.0.0, 4661", "239.1.1.1, 4661"})
    void shouldKeepOneEntryPerAddressAndRejectUnusableOnes(String ip, int port) {
        givenTheServerIsAdded(server("1.2.3.4", 4661));

        whenAServerIsAdded(server(ip, port));

        thenItIsRefused();
        thenTheListHolds(1);
    }

    @Test
    void shouldNeitherAddNorOfferServersTheFilterBlocks() {
        givenTheServerIsAdded(server("1.2.3.4", 4661));
        givenTheServerIsAdded(server("5.6.7.8", 4661));
        givenTheFilterBlocks("5.6.7.8");
        givenTheServerIsAdded(server("5.6.7.9", 4661));

        whenAServerIsAdded(server("5.6.7.8", 4662));

        thenItIsRefused();
        thenTheCandidatesAreTheServersAt("1.2.3.4", "5.6.7.9");
        thenTheListHolds(3, "kept in the file");
    }

    @Test
    void shouldOfferHighPriorityAndReliableServersFirst() {
        givenTheServersAddedOneByOne(
                server("1.1.1.1", 1).with(tag(ServerTag.PREFERENCE, ServerList.PRIORITY_LOW)),
                server("2.2.2.2", 2).with(tag(ServerTag.FAIL, 3)),
                server("3.3.3.3", 3),
                server("4.4.4.4", 4).with(tag(ServerTag.PREFERENCE, ServerList.PRIORITY_HIGH)));

        whenTheCandidatesAreListed();

        thenTheCandidatesAre("4.4.4.4:4", "3.3.3.3:3", "2.2.2.2:2", "1.1.1.1:1");
    }

    @Test
    void shouldSaveUpdatesAndLoadThemAgain() throws IOException {
        givenNoServerFileYet();
        givenTheServerList(server("5.6.7.8", 4242), server("8.7.6.5", 4661));
        givenTheServerIsRenamed("5.6.7.8", 4242, "renamed");

        whenTheListIsSavedAndLoaded();

        thenTheLoadedListHolds(2);
        thenTheLoadedServerIsNamed("5.6.7.8", 4242, "renamed");
    }

    @Test
    void shouldRemoveALoadedServer() throws IOException {
        givenTheServerList(server("5.6.7.8", 4242), server("8.7.6.5", 4661));
        givenTheServerIsRenamed("5.6.7.8", 4242, "renamed");
        givenTheListIsSavedAndLoaded();

        whenTheLoadedServerIsRemoved("5.6.7.8", 4242);

        thenItIsDone();
        thenTheLoadedListHasNoServerAt("5.6.7.8", 4242);
    }

    @Test
    void shouldTakeTheStaticServersFromTheirFileWithTheirPriority() throws IOException {
        givenTheStaticServersFile(
                "# kept servers", "5.6.7.8:4242,2,Five", "8.7.6.5:4661,Eight", "host.example:4661,0,Named");
        givenTheServersAddedOneByOne(server("5.6.7.8", 4242).with(tag(ServerTag.PREFERENCE, ServerList.PRIORITY_HIGH)));

        whenTheStaticServersAreLoaded();

        thenTheListHolds(3);
        thenItIsStatic("5.6.7.8", 4242);
        thenItIsStatic("8.7.6.5", 4661);
        thenThePriorityIs("5.6.7.8", 4242, ServerInfo.Priority.LOW);
        thenThePriorityIs("8.7.6.5", 4661, ServerInfo.Priority.HIGH, "no priority written: high");
        thenTheNameIs("8.7.6.5", 4661, "Eight");
    }

    @Test
    void shouldWriteTheStaticServersBackToTheirFile() throws IOException {
        givenTheStaticServersFile(
                "# kept servers", "5.6.7.8:4242,2,Five", "8.7.6.5:4661,Eight", "host.example:4661,0,Named");
        givenTheServersAddedOneByOne(server("5.6.7.8", 4242).with(tag(ServerTag.PREFERENCE, ServerList.PRIORITY_HIGH)));
        givenTheStaticServersAreLoaded();
        givenTheServerIsNoLongerStatic("8.7.6.5", 4661);
        givenThePriorityIsSet("5.6.7.8", 4242, ServerInfo.Priority.NORMAL);
        givenThePriorityOfAnUnknownServerCannotBeSet("9.9.9.9", 1, ServerInfo.Priority.LOW);

        whenTheStaticServersAreSaved();

        thenTheStaticServersFileHolds(
                new TextFiles.StaticServer("5.6.7.8", 4242, TextFiles.PRIORITY_NORMAL, "Five"),
                new TextFiles.StaticServer("host.example", 4661, TextFiles.PRIORITY_NORMAL, "Named"));
    }

    @Test
    void shouldKeepAServerRemovedFromTheStaticServersOutOfThem() throws IOException {
        givenTheStaticServersFile(
                "# kept servers", "5.6.7.8:4242,2,Five", "8.7.6.5:4661,Eight", "host.example:4661,0,Named");
        givenTheServersAddedOneByOne(server("5.6.7.8", 4242).with(tag(ServerTag.PREFERENCE, ServerList.PRIORITY_HIGH)));
        givenTheStaticServersAreLoaded();
        givenTheServerIsNoLongerStatic("8.7.6.5", 4661);
        givenThePriorityIsSet("5.6.7.8", 4242, ServerInfo.Priority.NORMAL);
        givenThePriorityOfAnUnknownServerCannotBeSet("9.9.9.9", 1, ServerInfo.Priority.LOW);
        givenTheStaticServersAreSaved();
        givenTheServerIsRemoved("5.6.7.8", 4242);

        whenTheServerIsMadeStatic("5.6.7.8", 4242);

        thenItIsRefused("unknown servers stay out");
    }

    @Test
    void shouldKeepServersKnownByHostNameByIt() throws IOException {
        givenTheServerIsAdded(dynamic("a.example", 4661));
        givenTheServerIsAdded(dynamic("b.example", 4661));
        givenTheServerIsNotAddedAgain(dynamic("A.Example", 4661), "the same host");
        givenTheServerIsAdded(server("1.2.3.4", 4661));

        whenTheListIsSavedAndLoaded();

        thenTheLoadedServersAre("a.example:4661", "b.example:4661", "1.2.3.4:4661");
        thenTheLoadedCandidatesAreTheServersAt("1.2.3.4");
        thenTheLoadedConnectableServersAre("a.example:4661", "b.example:4661", "1.2.3.4:4661");
    }

    @Test
    void shouldWriteTheStaticServersBackEvenWhenNotTaken() throws IOException {
        givenTheStaticServersFile(
                "a.example:4661,1,A",
                "b.example:4661,1,B",
                "127.0.0.1:4661,0,Loopback",
                "5.6.7.8:4242,2,Blocked",
                "1.2.3.4:4661,1,Kept");
        givenTheFilterBlocks("5.6.7.8");
        givenTheStaticServersAreLoaded();

        whenTheStaticServersAreSaved();

        thenTheListHolds(3);
        thenTheStaticServersFileHoldsAllItsLines();
    }

    @Test
    void shouldNotLetAServerNameAddLinesToTheStaticServers() throws IOException {
        givenTheServersAddedOneByOne(server("1.2.3.4", 4661)
                .with(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), "Evil\r\n6.6.6.6:4661,0,Injected\u0007")));
        givenTheServerIsMadeStatic("1.2.3.4", 4661);

        whenTheStaticServersAreSaved();

        thenTheStaticServersFileHolds(
                new TextFiles.StaticServer("1.2.3.4", 4661, TextFiles.PRIORITY_NORMAL, "Evil6.6.6.6:4661,0,Injected"));
    }

    @Test
    void shouldTakeAnObfuscatedPortBeyondTheRangeAsNone() {
        whenTheServerIsCheckedForObfuscation(server("1.2.3.4", 4661)
                .with(tag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_TCP_OBFUSCATION))
                .with(tag(ServerTag.TCPPORTOBFUSCATION, 70_000)));

        thenItDoesNotSupportObfuscation();
    }

    @Test
    void shouldFindTheServersThatFailedOftenEnoughButNeverTheStaticOnes() {
        givenServersThatFailedAndAStaticOneThatFailedMost();

        whenTheDeadServersAreListedFor(1);

        thenTheDeadServersAre("1.1.1.1:1", "2.2.2.2:2");
    }

    @Test
    void shouldNotFindTheServersThatFailedTooFewTimes() {
        givenServersThatFailedAndAStaticOneThatFailedMost();

        whenTheDeadServersAreListedFor(2);

        thenTheDeadServersAre("1.1.1.1:1");
    }

    @Test
    void shouldNotObfuscateWithoutTheFlag() {
        whenTheServerIsCheckedForObfuscation(server("1.2.3.4", 4661));

        thenItDoesNotSupportObfuscation();
    }

    @Test
    void shouldNotObfuscateWithTheFlagButNoPort() {
        whenTheServerIsCheckedForObfuscation(
                server("1.2.3.4", 4661).with(tag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_TCP_OBFUSCATION)));

        thenItDoesNotSupportObfuscation();
    }

    @Test
    void shouldObfuscateWithTheFlagAndAPort() {
        whenTheServerIsCheckedForObfuscation(server("1.2.3.4", 4661)
                .with(tag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_TCP_OBFUSCATION))
                .with(tag(ServerTag.TCPPORTOBFUSCATION, 4662)));

        thenItSupportsObfuscation();
    }

    @Test
    void shouldHaveNoVersionWithoutTheTag() {
        whenTheVersionOfTheServerIsRead(server("1.2.3.4", 4661));

        thenThereIsNoVersion();
    }

    @Test
    void shouldReadAVersionPackedInANumber() {
        whenTheVersionOfTheServerIsRead(server("1.2.3.4", 4661).with(tag(ServerTag.VERSION, (17L << 16) | 5)));

        thenTheVersionIs("17.05");
    }

    @Test
    void shouldReadAVersionWrittenAsText() {
        whenTheVersionOfTheServerIsRead(
                server("1.2.3.4", 4661).with(new Tag.StringTag(TagName.id(ServerTag.VERSION), "17.15 (lugdunum)")));

        thenTheVersionIs("17.15 (lugdunum)");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"server version 17.15 (lugdunum)|17.15 (lugdunum)", "Server Version 17.5|17.05"})
    void shouldReadTheVersionAServerTellsInAMessageLine(String line, String expected) {
        whenTheVersionIsReadFromTheMessageLine(line);

        thenTheVersionIs(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"server version ", "Welcome to the server"})
    void shouldFindNoVersionInOtherMessageLines(String line) {
        whenTheVersionIsReadFromTheMessageLine(line);

        thenThereIsNoVersion();
    }

    private void givenNoServerFileYet() throws IOException {
        assertThat(ServerList.load(serverFile()).size()).isZero();
    }

    private void givenTheServerList(ServerMet.Server... servers) {
        this.list.addAll(List.of(servers));
    }

    private void givenTheServersAddedOneByOne(ServerMet.Server... servers) {
        for (ServerMet.Server server : servers) {
            this.list.add(server);
        }
    }

    private void givenTheServerIsAdded(ServerMet.Server server) {
        assertThat(this.list.add(server)).isTrue();
    }

    private void givenTheServerIsNotAddedAgain(ServerMet.Server server, String why) {
        assertThat(this.list.add(server)).as(why).isFalse();
    }

    private void givenTheFilterBlocks(String blocked) {
        this.list.filter(ip -> ip == Ipv4.parse(blocked));
    }

    private void givenTheServerIsRenamed(String ip, int port, String name) {
        this.list.update(address(ip, port), s -> s.with(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), name)));
    }

    private void givenTheListIsSavedAndLoaded() throws IOException {
        whenTheListIsSavedAndLoaded();
    }

    private void givenServersThatFailedAndAStaticOneThatFailedMost() {
        givenTheServersAddedOneByOne(
                server("1.1.1.1", 1).with(tag(ServerTag.FAIL, 2)),
                server("2.2.2.2", 2).with(tag(ServerTag.FAIL, 1)),
                server("3.3.3.3", 3),
                server("4.4.4.4", 4).with(tag(ServerTag.FAIL, 5)));
        givenTheServerIsMadeStatic("4.4.4.4", 4);
    }

    private void givenTheStaticServersFile(String... lines) throws IOException {
        this.staticLines = List.of(lines);
        Files.write(staticFile(), TextFiles.writeLines(this.staticLines));
    }

    private void givenTheStaticServersAreLoaded() throws IOException {
        this.list.loadStatics(staticFile());
    }

    private void givenTheStaticServersAreSaved() throws IOException {
        this.list.saveStatics(staticFile());
    }

    private void givenTheServerIsMadeStatic(String ip, int port) {
        this.list.setStatic(address(ip, port), true);
    }

    private void givenTheServerIsNoLongerStatic(String ip, int port) {
        assertThat(this.list.setStatic(address(ip, port), false)).isTrue();
    }

    private void givenThePriorityIsSet(String ip, int port, ServerInfo.Priority priority) {
        assertThat(this.list.setPriority(address(ip, port), priority)).isTrue();
    }

    private void givenThePriorityOfAnUnknownServerCannotBeSet(String ip, int port, ServerInfo.Priority priority) {
        assertThat(this.list.setPriority(address(ip, port), priority)).isFalse();
    }

    private void givenTheServerIsRemoved(String ip, int port) {
        assertThat(this.list.remove(address(ip, port))).isTrue();
        assertThat(this.list.isStatic(address(ip, port))).isFalse();
    }

    private void whenTheServersOfAServerListAreTaken(ServerMet.Server... servers) {
        this.list.addAll(List.of(servers));
    }

    private void whenAServerIsAdded(ServerMet.Server server) {
        this.done = this.list.add(server);
    }

    private void whenTheCandidatesAreListed() {
        this.candidates = this.list.candidates();
    }

    private void whenTheListIsSavedAndLoaded() throws IOException {
        this.list.save(serverFile());
        this.loaded = ServerList.load(serverFile());
    }

    private void whenTheLoadedServerIsRemoved(String ip, int port) {
        this.done = this.loaded.remove(address(ip, port));
    }

    private void whenTheStaticServersAreLoaded() throws IOException {
        this.list.loadStatics(staticFile());
    }

    private void whenTheStaticServersAreSaved() throws IOException {
        this.list.saveStatics(staticFile());
    }

    private void whenTheServerIsMadeStatic(String ip, int port) {
        this.done = this.list.setStatic(address(ip, port), true);
    }

    private void whenTheDeadServersAreListedFor(int failures) {
        this.dead = this.list.dead(failures);
    }

    private void whenTheServerIsCheckedForObfuscation(ServerMet.Server server) {
        this.supportsObfuscation = ServerList.supportsObfuscation(server);
    }

    private void whenTheVersionOfTheServerIsRead(ServerMet.Server server) {
        this.version = server.version();
    }

    private void whenTheVersionIsReadFromTheMessageLine(String line) {
        this.version = ServerSession.version(line);
    }

    private void thenTheServerIsNamed(String name, String description) {
        ServerMet.Server server = this.list.all().getFirst();
        assertThat(server.name()).contains(name);
        assertThat(server.description()).contains(description);
    }

    private void thenItIsDone() {
        assertThat(this.done).isTrue();
    }

    private void thenItIsRefused() {
        assertThat(this.done).isFalse();
    }

    private void thenItIsRefused(String why) {
        assertThat(this.done).as(why).isFalse();
    }

    private void thenTheListHolds(int size) {
        assertThat(this.list.size()).isEqualTo(size);
    }

    private void thenTheListHolds(int size, String why) {
        assertThat(this.list.size()).as(why).isEqualTo(size);
    }

    private void thenTheCandidatesAreTheServersAt(String... ips) {
        assertThat(this.list.candidates())
                .extracting(ServerMet.Server::ip)
                .containsExactly(Arrays.stream(ips).map(Ipv4::parse).toArray(Integer[]::new));
    }

    private void thenTheCandidatesAre(String... addresses) {
        assertThat(this.candidates)
                .extracting(ServerList::addressOf)
                .extracting(ServerList.Address::toString)
                .containsExactly(addresses);
    }

    private void thenTheLoadedListHolds(int size) {
        assertThat(this.loaded.size()).isEqualTo(size);
    }

    private void thenTheLoadedServerIsNamed(String ip, int port, String name) {
        assertThat(this.loaded.get(address(ip, port)).flatMap(ServerMet.Server::name))
                .contains(name);
    }

    private void thenTheLoadedListHasNoServerAt(String ip, int port) {
        assertThat(this.loaded.get(address(ip, port))).isEmpty();
    }

    private void thenTheLoadedServersAre(String... addresses) {
        assertThat(this.loaded.all())
                .extracting(ServerList::addressOf)
                .extracting(ServerList.Address::toString)
                .containsExactly(addresses);
    }

    private void thenTheLoadedCandidatesAreTheServersAt(String... ips) {
        assertThat(this.loaded.candidates())
                .as("no address to ping")
                .extracting(ServerMet.Server::ip)
                .containsExactly(Arrays.stream(ips).map(Ipv4::parse).toArray(Integer[]::new));
    }

    private void thenTheLoadedConnectableServersAre(String... addresses) {
        assertThat(this.loaded.connectable())
                .extracting(ServerList::addressOf)
                .extracting(ServerList.Address::toString)
                .containsExactly(addresses);
    }

    private void thenItIsStatic(String ip, int port) {
        assertThat(this.list.isStatic(address(ip, port))).isTrue();
    }

    private void thenThePriorityIs(String ip, int port, ServerInfo.Priority priority) {
        assertThat(this.list.get(address(ip, port)).map(ServerList::priority)).contains(priority);
    }

    private void thenThePriorityIs(String ip, int port, ServerInfo.Priority priority, String why) {
        assertThat(this.list.get(address(ip, port)).map(ServerList::priority))
                .as(why)
                .contains(priority);
    }

    private void thenTheNameIs(String ip, int port, String name) {
        assertThat(this.list.get(address(ip, port)).flatMap(ServerMet.Server::name))
                .contains(name);
    }

    private void thenTheStaticServersFileHolds(TextFiles.StaticServer... servers) throws IOException {
        assertThat(TextFiles.staticServers(Files.readAllBytes(staticFile()))).containsExactly(servers);
    }

    private void thenTheStaticServersFileHoldsAllItsLines() throws IOException {
        assertThat(TextFiles.staticServers(Files.readAllBytes(staticFile())))
                .containsExactlyInAnyOrderElementsOf(TextFiles.staticServers(TextFiles.writeLines(this.staticLines)));
    }

    private void thenTheDeadServersAre(String... addresses) {
        assertThat(this.dead).extracting(ServerList.Address::toString).containsExactly(addresses);
    }

    private void thenItSupportsObfuscation() {
        assertThat(this.supportsObfuscation).isTrue();
    }

    private void thenItDoesNotSupportObfuscation() {
        assertThat(this.supportsObfuscation).isFalse();
    }

    private void thenTheVersionIs(String expected) {
        assertThat(this.version).contains(expected);
    }

    private void thenThereIsNoVersion() {
        assertThat(this.version).isEmpty();
    }

    private Path serverFile() {
        return this.dir.resolve(ServerList.FILE_NAME);
    }

    private Path staticFile() {
        return this.dir.resolve(ServerList.STATIC_FILE_NAME);
    }

    private static ServerList.Address address(String ip, int port) {
        return new ServerList.Address(Ipv4.parse(ip), port);
    }

    private static ServerMet.Server server(String ip, int port) {
        return new ServerMet.Server(Ipv4.parse(ip), port, List.of());
    }

    private static ServerMet.Server dynamic(String host, int port) {
        return new ServerMet.Server(0, port, List.of(new Tag.StringTag(TagName.id(ServerTag.DYNIP), host)));
    }

    private static Tag tag(int id, long value) {
        return new Tag.IntTag(TagName.id(id), value);
    }
}
