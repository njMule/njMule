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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.njmule.core.api.EngineEvent;

/**
 * What a subscriber that comes late is told first: the latest log lines, and the server connection as it stands, with
 * the messages of the server since, such as those of a start that connected before the window opened. Not thread-safe.
 */
final class EventReplay {

    static final int LOG_LINES = 100;
    static final int SERVER_MESSAGES = 50;

    private final ArrayDeque<EngineEvent> log = new ArrayDeque<>();
    // The latest connecting or connected event, then the server's messages; empty while disconnected.
    private final List<EngineEvent> server = new ArrayList<>();

    void record(EngineEvent event) {
        switch (event) {
            case EngineEvent.Log line -> {
                if (this.log.size() >= LOG_LINES) {
                    this.log.removeFirst();
                }
                this.log.addLast(line);
            }
            case EngineEvent.ServerConnecting connecting -> {
                this.server.clear();
                this.server.add(connecting);
            }
            case EngineEvent.ServerConnected connected -> {
                // What the server said while we logged in stays.
                String address = connected.server().address();
                this.server.removeIf(e -> !(e instanceof EngineEvent.ServerMessage m
                        && m.server().address().equals(address)));
                this.server.addFirst(connected);
            }
            case EngineEvent.ServerDisconnected disconnected -> this.server.clear();
            case EngineEvent.ServerMessage message -> {
                if (!this.server.isEmpty() && this.server.size() <= SERVER_MESSAGES) {
                    this.server.add(message);
                }
            }
            default -> {
                // Other states are polled.
            }
        }
    }

    /** The events to tell first, in their order. */
    ArrayDeque<EngineEvent> missed() {
        ArrayDeque<EngineEvent> missed = new ArrayDeque<>(this.log);
        missed.addAll(this.server);
        return missed;
    }
}
