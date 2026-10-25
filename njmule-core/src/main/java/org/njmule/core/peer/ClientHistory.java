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
package org.njmule.core.peer;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The clients met, by user hash: the name and program they gave last, when they were first and last met and, when the
 * countries are shown, where from; never their address. A client not met for {@link #KEPT} is forgotten, and at most
 * {@value #MAX_CLIENTS} are kept, those met longest ago going first. Engine thread only; kept in {@value #FILE_NAME}.
 */
public final class ClientHistory {

    public static final String FILE_NAME = "clienthistory.json";

    /** A client not met for this long is forgotten. */
    public static final Duration KEPT = Duration.ofDays(90);

    static final int MAX_CLIENTS = 20_000;

    private static final Logger logger = LoggerFactory.getLogger(ClientHistory.class);
    private static final int FORMAT = 1;

    /** One client met, its times in seconds since the epoch. */
    public record Entry(
            Hash16 userHash, String nick, String software, long firstSeen, long lastSeen, Optional<String> country) {}

    private final Path file;
    // By last meeting, oldest first.
    private final LinkedHashMap<Hash16, Entry> clients = new LinkedHashMap<>();
    private boolean changed;

    private ClientHistory(Path file) {
        this.file = file;
    }

    /** The history kept in {@code configDir}, without the clients not met for {@link #KEPT}; empty when unreadable. */
    public static ClientHistory load(Path configDir, Instant now) {
        ClientHistory history = new ClientHistory(configDir.resolve(FILE_NAME));
        try {
            JsonNode root =
                    AtomicFiles.read(history.file, in -> JsonMapper.shared().readTree(in));
            if (root.path("format").asInt() != FORMAT) {
                return history;
            }
            List<Entry> read = new ArrayList<>();
            for (JsonNode n : root.path("clients")) {
                read.add(new Entry(
                        Hash16.parseHex(n.path("userHash").asString()),
                        n.path("nick").asString(""),
                        n.path("software").asString(""),
                        n.path("firstSeen").asLong(),
                        n.path("lastSeen").asLong(),
                        n.hasNonNull("country") ? Optional.of(n.path("country").asString()) : Optional.empty()));
            }
            read.sort(Comparator.comparingLong(Entry::lastSeen));
            read.forEach(e -> history.clients.put(e.userHash(), e));
            history.forgetOld(now);
        } catch (NoSuchFileException e) {
            // Nobody met yet.
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            logger.warn("Starting a new client history: cannot read {}: {}", history.file, e.toString());
            history.clients.clear();
        }
        return history;
    }

    /** Notes that {@code userHash} was met now, with what it said of itself. */
    public void seen(Hash16 userHash, String nick, String software, Optional<String> country, Instant now) {
        long seconds = now.getEpochSecond();
        Entry before = this.clients.remove(userHash);
        this.clients.put(
                userHash,
                new Entry(
                        userHash,
                        nick,
                        software,
                        before == null ? seconds : before.firstSeen(),
                        seconds,
                        country.isPresent() || before == null ? country : before.country()));
        while (this.clients.size() > MAX_CLIENTS) {
            this.clients.pollFirstEntry();
        }
        this.changed = true;
    }

    /** The clients met within {@link #KEPT}, those met last first. */
    public List<Entry> clients(Instant now) {
        forgetOld(now);
        return List.copyOf(this.clients.sequencedValues().reversed());
    }

    /** Writes the history when it changed. */
    public void save() {
        if (!this.changed) {
            return;
        }
        ObjectNode root = JsonMapper.shared().createObjectNode();
        root.put("format", FORMAT);
        ArrayNode list = root.putArray("clients");
        for (Entry e : this.clients.values()) {
            ObjectNode n = list.addObject();
            n.put("userHash", e.userHash().toHex());
            n.put("nick", e.nick());
            n.put("software", e.software());
            n.put("firstSeen", e.firstSeen());
            n.put("lastSeen", e.lastSeen());
            e.country().ifPresent(c -> n.put("country", c));
        }
        try {
            AtomicFiles.writeBytes(this.file, JsonMapper.shared().writeValueAsBytes(root));
            this.changed = false;
        } catch (IOException | JacksonException e) {
            logger.warn("Cannot save the client history to {}: {}", this.file, e.toString());
        }
    }

    private void forgetOld(Instant now) {
        long oldest = now.minus(KEPT).getEpochSecond();
        Map.Entry<Hash16, Entry> first;
        while ((first = this.clients.firstEntry()) != null && first.getValue().lastSeen() < oldest) {
            this.clients.pollFirstEntry();
            this.changed = true;
        }
    }
}
