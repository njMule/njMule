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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    @Test
    void keepsOneEntryPerAddressAndRejectsUnusableOnes() {
        ServerList list = new ServerList();

        assertThat(list.add(server("1.2.3.4", 4661))).isTrue();
        assertThat(list.add(server("1.2.3.4", 4661))).isFalse();
        assertThat(list.add(server("1.2.3.4", 0))).isFalse();
        assertThat(list.add(server("127.0.0.1", 4661))).isFalse();
        assertThat(list.add(server("0.0.0.0", 4661))).isFalse();
        assertThat(list.add(server("239.1.1.1", 4661))).isFalse();
        assertThat(list.size()).isEqualTo(1);
    }

    @Test
    void neitherAddsNorOffersServersTheFilterBlocks() {
        ServerList list = new ServerList();
        assertThat(list.add(server("1.2.3.4", 4661))).isTrue();
        assertThat(list.add(server("5.6.7.8", 4661))).isTrue();
        list.filter(ip -> ip == Ipv4.parse("5.6.7.8"));
        assertThat(list.add(server("5.6.7.9", 4661))).isTrue();
        assertThat(list.add(server("5.6.7.8", 4662))).isFalse();
        assertThat(list.candidates())
                .extracting(ServerMet.Server::ip)
                .containsExactly(Ipv4.parse("1.2.3.4"), Ipv4.parse("5.6.7.9"));
        assertThat(list.size()).as("kept in the file").isEqualTo(3);
    }

    @Test
    void offersHighPriorityAndReliableServersFirst() {
        ServerList list = new ServerList();
        list.add(server("1.1.1.1", 1).with(tag(ServerTag.PREFERENCE, ServerList.PRIORITY_LOW)));
        list.add(server("2.2.2.2", 2).with(tag(ServerTag.FAIL, 3)));
        list.add(server("3.3.3.3", 3));
        list.add(server("4.4.4.4", 4).with(tag(ServerTag.PREFERENCE, ServerList.PRIORITY_HIGH)));

        assertThat(list.candidates())
                .extracting(ServerList::addressOf)
                .extracting(ServerList.Address::toString)
                .containsExactly("4.4.4.4:4", "3.3.3.3:3", "2.2.2.2:2", "1.1.1.1:1");
    }

    @Test
    void savesUpdatesAndLoadsAgain() throws Exception {
        Path file = this.dir.resolve(ServerList.FILE_NAME);
        assertThat(ServerList.load(file).size()).isZero();

        ServerList list = new ServerList();
        list.addAll(List.of(server("5.6.7.8", 4242), server("8.7.6.5", 4661)));
        ServerList.Address address = new ServerList.Address(Ipv4.parse("5.6.7.8"), 4242);
        list.update(address, s -> s.with(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), "renamed")));
        list.save(file);

        ServerList loaded = ServerList.load(file);
        assertThat(loaded.size()).isEqualTo(2);
        assertThat(loaded.get(address).flatMap(ServerMet.Server::name)).contains("renamed");
        assertThat(loaded.remove(address)).isTrue();
        assertThat(loaded.get(address)).isEmpty();
    }

    @Test
    void staticServersComeFromTheirFileWithTheirPriorityAndGoBackToIt() throws Exception {
        Path file = this.dir.resolve(ServerList.STATIC_FILE_NAME);
        Files.write(
                file,
                TextFiles.writeLines(List.of(
                        "# kept servers", "5.6.7.8:4242,2,Five", "8.7.6.5:4661,Eight", "host.example:4661,0,Named")));
        ServerList list = new ServerList();
        list.add(server("5.6.7.8", 4242).with(tag(ServerTag.PREFERENCE, ServerList.PRIORITY_HIGH)));

        list.loadStatics(file);

        ServerList.Address five = new ServerList.Address(Ipv4.parse("5.6.7.8"), 4242);
        ServerList.Address eight = new ServerList.Address(Ipv4.parse("8.7.6.5"), 4661);
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.isStatic(five)).isTrue();
        assertThat(list.isStatic(eight)).isTrue();
        assertThat(list.get(five).map(ServerList::priority)).contains(ServerInfo.Priority.LOW);
        assertThat(list.get(eight).map(ServerList::priority))
                .as("no priority written: high")
                .contains(ServerInfo.Priority.HIGH);
        assertThat(list.get(eight).flatMap(ServerMet.Server::name)).contains("Eight");

        assertThat(list.setStatic(eight, false)).isTrue();
        assertThat(list.setPriority(five, ServerInfo.Priority.NORMAL)).isTrue();
        assertThat(list.setPriority(new ServerList.Address(Ipv4.parse("9.9.9.9"), 1), ServerInfo.Priority.LOW))
                .isFalse();
        list.saveStatics(file);

        assertThat(TextFiles.staticServers(Files.readAllBytes(file)))
                .containsExactly(
                        new TextFiles.StaticServer("5.6.7.8", 4242, TextFiles.PRIORITY_NORMAL, "Five"),
                        new TextFiles.StaticServer("host.example", 4661, TextFiles.PRIORITY_NORMAL, "Named"));
        assertThat(list.remove(five)).isTrue();
        assertThat(list.isStatic(five)).isFalse();
        assertThat(list.setStatic(five, true)).as("unknown servers stay out").isFalse();
    }

    @Test
    void serversKnownByHostNameAreKeptByIt() throws Exception {
        ServerList list = new ServerList();
        assertThat(list.add(dynamic("a.example", 4661))).isTrue();
        assertThat(list.add(dynamic("b.example", 4661))).isTrue();
        assertThat(list.add(dynamic("A.Example", 4661))).as("the same host").isFalse();
        assertThat(list.add(server("1.2.3.4", 4661))).isTrue();
        Path file = this.dir.resolve(ServerList.FILE_NAME);

        list.save(file);
        ServerList loaded = ServerList.load(file);

        assertThat(loaded.all())
                .extracting(ServerList::addressOf)
                .extracting(ServerList.Address::toString)
                .containsExactly("a.example:4661", "b.example:4661", "1.2.3.4:4661");
        assertThat(loaded.candidates())
                .as("no address to ping")
                .extracting(ServerMet.Server::ip)
                .containsExactly(Ipv4.parse("1.2.3.4"));
        assertThat(loaded.connectable())
                .extracting(ServerList::addressOf)
                .extracting(ServerList.Address::toString)
                .containsExactly("a.example:4661", "b.example:4661", "1.2.3.4:4661");
    }

    @Test
    void staticServersAreWrittenBackEvenWhenNotTaken() throws Exception {
        Path file = this.dir.resolve(ServerList.STATIC_FILE_NAME);
        List<String> lines = List.of(
                "a.example:4661,1,A",
                "b.example:4661,1,B",
                "127.0.0.1:4661,0,Loopback",
                "5.6.7.8:4242,2,Blocked",
                "1.2.3.4:4661,1,Kept");
        Files.write(file, TextFiles.writeLines(lines));
        ServerList list = new ServerList();
        list.filter(ip -> ip == Ipv4.parse("5.6.7.8"));

        list.loadStatics(file);
        list.saveStatics(file);

        assertThat(list.size()).isEqualTo(3);
        assertThat(TextFiles.staticServers(Files.readAllBytes(file)))
                .containsExactlyInAnyOrderElementsOf(TextFiles.staticServers(TextFiles.writeLines(lines)));
    }

    @Test
    void aServerNameCannotAddLinesToTheStaticServers() throws Exception {
        Path file = this.dir.resolve(ServerList.STATIC_FILE_NAME);
        ServerList list = new ServerList();
        list.add(server("1.2.3.4", 4661)
                .with(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), "Evil\r\n6.6.6.6:4661,0,Injected\u0007")));
        list.setStatic(new ServerList.Address(Ipv4.parse("1.2.3.4"), 4661), true);

        list.saveStatics(file);

        assertThat(TextFiles.staticServers(Files.readAllBytes(file)))
                .containsExactly(new TextFiles.StaticServer(
                        "1.2.3.4", 4661, TextFiles.PRIORITY_NORMAL, "Evil6.6.6.6:4661,0,Injected"));
    }

    @Test
    void anObfuscatedPortBeyondTheRangeIsNone() {
        ServerMet.Server flagged = server("1.2.3.4", 4661)
                .with(tag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_TCP_OBFUSCATION))
                .with(tag(ServerTag.TCPPORTOBFUSCATION, 70_000));

        assertThat(ServerList.supportsObfuscation(flagged)).isFalse();
    }

    @Test
    void deadServersFailedOftenEnoughButStaticOnesNever() {
        ServerList list = new ServerList();
        list.add(server("1.1.1.1", 1).with(tag(ServerTag.FAIL, 2)));
        list.add(server("2.2.2.2", 2).with(tag(ServerTag.FAIL, 1)));
        list.add(server("3.3.3.3", 3));
        list.add(server("4.4.4.4", 4).with(tag(ServerTag.FAIL, 5)));
        list.setStatic(new ServerList.Address(Ipv4.parse("4.4.4.4"), 4), true);

        assertThat(list.dead(1)).extracting(ServerList.Address::toString).containsExactly("1.1.1.1:1", "2.2.2.2:2");
        assertThat(list.dead(2)).extracting(ServerList.Address::toString).containsExactly("1.1.1.1:1");
    }

    @Test
    void obfuscationNeedsTheFlagAndAPort() {
        ServerMet.Server plain = server("1.2.3.4", 4661);
        ServerMet.Server flagged = plain.with(tag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_TCP_OBFUSCATION));

        assertThat(ServerList.supportsObfuscation(plain)).isFalse();
        assertThat(ServerList.supportsObfuscation(flagged)).isFalse();
        assertThat(ServerList.supportsObfuscation(flagged.with(tag(ServerTag.TCPPORTOBFUSCATION, 4662))))
                .isTrue();
    }

    @Test
    void versionsAreTextOrPackedNumbers() {
        ServerMet.Server server = server("1.2.3.4", 4661);

        assertThat(server.version()).isEmpty();
        assertThat(server.with(tag(ServerTag.VERSION, (17L << 16) | 5)).version())
                .contains("17.05");
        assertThat(server.with(new Tag.StringTag(TagName.id(ServerTag.VERSION), "17.15 (lugdunum)"))
                        .version())
                .contains("17.15 (lugdunum)");
    }

    @Test
    void serversTellTheirVersionInAMessageLine() {
        assertThat(ServerSession.version("server version 17.15 (lugdunum)")).contains("17.15 (lugdunum)");
        assertThat(ServerSession.version("Server Version 17.5")).contains("17.05");
        assertThat(ServerSession.version("server version ")).isEmpty();
        assertThat(ServerSession.version("Welcome to the server")).isEmpty();
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
