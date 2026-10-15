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
import java.util.Optional;
import javafx.scene.control.Tab;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class ChatNewsTest {

    private static final Hash16 PEER = Hash16.parseHex("00112233445566778899AABBCCDDEEFF");

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
    void aConversationWithNewsIsMarkedUntilLookedAt() throws Exception {
        givenTheMessages();
        whenAMessageComesFrom("Ann", "10.0.0.1:4662");
        whenAMessageComesFrom("Bob", "10.0.0.2:4662");
        whenAMessageComesFrom("Ann", "10.0.0.1:4662");
        thenTheConversationIsMarked("Ann", true);
        thenTheConversationIsMarked("Bob", false);
        whenTheConversationIsChosen("Ann");
        thenTheConversationIsMarked("Ann", false);
    }

    private void givenTheMessages() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new MessagesScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
    }

    private void whenAMessageComesFrom(String nick, String address) throws Exception {
        FxTesting.onFx(() -> this.test.model.handle(
                new EngineEvent.MessageReceived(address, PEER, Optional.of(nick), "hello from " + nick)));
    }

    private void whenTheConversationIsChosen(String nick) throws Exception {
        FxTesting.onFx(() -> this.screen.tabs().getSelectionModel().select(tab(nick)));
    }

    private void thenTheConversationIsMarked(String nick, boolean marked) throws Exception {
        assertThat(FxTesting.onFx(() -> MessagesScreen.isUnread(tab(nick)))).isEqualTo(marked);
    }

    private Tab tab(String nick) {
        return this.screen.tabs().getTabs().stream()
                .filter(t -> nick.equals(t.getText()))
                .findFirst()
                .orElseThrow();
    }
}
