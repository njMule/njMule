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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

/** The conversations and the friends in one list, narrowed to the friends or to the words written. */
@Timeout(60)
class ConversationListTest {

    private static final Hash16 ANN = Hash16.parseHex("00112233445566778899AABBCCDDEEFF");
    private static final Hash16 BOB = Hash16.parseHex("FFEEDDCCBBAA99887766554433221100");
    private static final Hash16 CAROL = Hash16.parseHex("0123456789ABCDEF0123456789ABCDEF");

    @TempDir
    Path dir;

    private TestGui test;
    private MessagesScreen screen;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldListTheFriendsWithoutAConversationAfterTheConversations() throws Exception {
        givenTheMessages();
        givenAMessageFrom("Ann", ANN, "10.0.0.1:4662");
        givenAMessageFrom("Bob", BOB, "10.0.0.2:4662");

        whenAFriendIsAdded("Carol", CAROL);

        thenTheListShows("Ann", "Bob", "Carol");
        thenTheLastRowIs("Carol");
    }

    @Test
    void shouldShowOnlyTheFriendsWhenAsked() throws Exception {
        givenTheMessages();
        givenAMessageFrom("Ann", ANN, "10.0.0.1:4662");
        givenAMessageFrom("Bob", BOB, "10.0.0.2:4662");
        givenAFriend("Ann", ANN);
        givenAFriend("Carol", CAROL);

        whenTheListShows(MessagesScreen.Show.FRIENDS);

        thenTheListShows("Ann", "Carol");
    }

    @Test
    void shouldNarrowTheListToTheWordsWritten() throws Exception {
        givenTheMessages();
        givenAMessageFrom("Ann", ANN, "10.0.0.1:4662");
        givenAMessageFrom("Bob", BOB, "10.0.0.2:4662");
        givenAFriend("Carol", CAROL);

        whenTheFindBoxReads("bo");

        thenTheListShows("Bob");
    }

    private void givenTheMessages() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new MessagesScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void givenAMessageFrom(String nick, Hash16 hash, String address) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(
                new EngineEvent.MessageReceived(address, hash, Optional.of(nick), "hello from " + nick)));
        FxTesting.assertEventually("the conversation with " + nick, () -> names().contains(nick));
    }

    private void givenAFriend(String name, Hash16 hash) throws Exception {
        whenAFriendIsAdded(name, hash);
        FxTesting.assertEventually(
                "the friend " + name,
                () -> this.test.model.friends().rows().stream()
                        .anyMatch(r -> r.get().name().equals(name)));
    }

    private void whenAFriendIsAdded(String name, Hash16 hash) throws Exception {
        FxTesting.onFx(() -> this.test.model.addFriend(new Friend(hash, name, Optional.empty())));
    }

    private void whenTheListShows(MessagesScreen.Show show) throws Exception {
        FxTesting.onFx(() -> this.screen.showSegments().setValue(show));
    }

    private void whenTheFindBoxReads(String text) throws Exception {
        FxTesting.onFx(() -> this.screen.findField().setText(text));
    }

    private void thenTheListShows(String... names) throws Exception {
        FxTesting.assertEventually(
                "the list " + List.of(names),
                () -> names().size() == names.length && names().containsAll(List.of(names)));
        assertThat(FxTesting.onFx(this::names)).containsExactlyInAnyOrder(names);
    }

    private void thenTheLastRowIs(String name) throws Exception {
        assertThat(FxTesting.onFx(this::names)).last().isEqualTo(name);
    }

    private List<String> names() {
        return this.screen.conversations().getItems().stream()
                .map(this.screen::nameOf)
                .toList();
    }
}
