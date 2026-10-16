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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.ServerInfo;

/** What a subscriber that comes late is told first. */
class EventReplayTest {

    private static final ServerInfo RUST = server("85.17.116.222:6082");
    private static final ServerInfo SUNRISE = server("176.123.5.89:4725");
    private static final EngineEvent.Log STARTED = log("started");

    private final EventReplay replay = new EventReplay();
    private List<EngineEvent> missed;

    @Test
    void shouldTellTheServerConnectedToWithWhatItSaidSinceTheLogin() {
        givenTheEvents(
                STARTED,
                new EngineEvent.ServerConnecting(RUST),
                new EngineEvent.ServerConnecting(SUNRISE),
                message(RUST, "welcome"),
                message(SUNRISE, "other"),
                connected(RUST),
                message(RUST, "news"));

        whenALateSubscriberComes();

        thenItIsTold(STARTED, connected(RUST), message(RUST, "welcome"), message(RUST, "news"));
    }

    @Test
    void shouldTellTheLatestServerTriedWhileConnecting() {
        givenTheEvents(new EngineEvent.ServerConnecting(RUST), new EngineEvent.ServerConnecting(SUNRISE));

        whenALateSubscriberComes();

        thenItIsTold(new EngineEvent.ServerConnecting(SUNRISE));
    }

    @Test
    void shouldTellNoServerOnceDisconnected() {
        givenTheEvents(
                STARTED,
                connected(RUST),
                message(RUST, "welcome"),
                new EngineEvent.ServerDisconnected(Optional.of(RUST), "lost"));

        whenALateSubscriberComes();

        thenItIsTold(STARTED);
    }

    @Test
    void shouldTellNoMoreMessagesOfTheServerThanTheLimit() {
        givenTheEvents(connected(RUST));
        givenTheServerSaidLines(EventReplay.SERVER_MESSAGES + 10);

        whenALateSubscriberComes();

        thenItIsToldEvents(1 + EventReplay.SERVER_MESSAGES);
    }

    private void givenTheEvents(EngineEvent... events) {
        for (EngineEvent event : events) {
            this.replay.record(event);
        }
    }

    private void givenTheServerSaidLines(int count) {
        for (int i = 0; i < count; i++) {
            this.replay.record(message(RUST, "line " + i));
        }
    }

    private void whenALateSubscriberComes() {
        this.missed = new ArrayList<>(this.replay.missed());
    }

    private void thenItIsTold(EngineEvent... events) {
        assertThat(this.missed).containsExactly(events);
    }

    private void thenItIsToldEvents(int count) {
        assertThat(this.missed).hasSize(count);
    }

    private static EngineEvent.ServerConnected connected(ServerInfo server) {
        return new EngineEvent.ServerConnected(server, 4_220_008_785L, false, true);
    }

    private static EngineEvent.ServerMessage message(ServerInfo server, String text) {
        return new EngineEvent.ServerMessage(server, text);
    }

    private static EngineEvent.Log log(String text) {
        return new EngineEvent.Log(new LogLine(Instant.EPOCH, LogLine.Level.INFO, text));
    }

    private static ServerInfo server(String address) {
        return new ServerInfo(
                address, address, "", 0, 0, 0, 0, ServerInfo.Priority.NORMAL, 0, 0, 0, 0, "", false, false);
    }
}
