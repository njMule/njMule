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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.util.Ipv4;

/**
 * The known eD2K servers, kept in {@code server.met}. Only the engine thread uses it.
 *
 * <p>Servers without a usable address (port 0, a reserved IP, a host name not yet resolved) are
 * kept in the file but never offered as {@link #candidates()}.
 */
public final class ServerList {

    public static final String FILE_NAME = "server.met";

    // Values of the preference tag.
    static final int PRIORITY_NORMAL = 0;
    static final int PRIORITY_HIGH = 1;
    static final int PRIORITY_LOW = 2;

    /** A server address; {@code ip} is in {@link Ipv4} form. */
    public record Address(int ip, int port) {
        @Override
        public String toString() {
            return Ipv4.format(this.ip) + ":" + this.port;
        }
    }

    private final Map<Address, ServerMet.Server> servers = new LinkedHashMap<>();

    /** Reads {@code file} (or its backup); a missing file gives an empty list. */
    public static ServerList load(Path file) throws IOException {
        ServerList list = new ServerList();
        if (Files.exists(file) || Files.exists(AtomicFiles.bakPath(file.toAbsolutePath()))) {
            list.addAll(AtomicFiles.read(file, in -> ServerMet.read(in.readAllBytes())));
        }
        return list;
    }

    public void save(Path file) throws IOException {
        AtomicFiles.writeBytes(file, ServerMet.write(this.all()));
    }

    /** Adds the servers not known yet; returns how many were added. */
    public int addAll(Collection<ServerMet.Server> others) {
        int added = 0;
        for (ServerMet.Server server : others) {
            if (this.add(server)) {
                added++;
            }
        }
        return added;
    }

    /** Adds a server unless its address is already known or cannot be one of a server. */
    public boolean add(ServerMet.Server server) {
        if (server.port() == 0 || !(isUsable(server.ip()) || server.dynamicIp().isPresent())) {
            return false;
        }
        return this.servers.putIfAbsent(addressOf(server), server) == null;
    }

    public Optional<ServerMet.Server> get(Address address) {
        return Optional.ofNullable(this.servers.get(address));
    }

    public void update(Address address, UnaryOperator<ServerMet.Server> change) {
        this.servers.computeIfPresent(address, (key, server) -> change.apply(server));
    }

    public boolean remove(Address address) {
        return this.servers.remove(address) != null;
    }

    public List<ServerMet.Server> all() {
        return List.copyOf(this.servers.values());
    }

    public int size() {
        return this.servers.size();
    }

    /** The servers to try, high priority first, then those that failed least. */
    public List<ServerMet.Server> candidates() {
        List<ServerMet.Server> usable = new ArrayList<>();
        for (ServerMet.Server server : this.servers.values()) {
            if (isUsable(server.ip())) {
                usable.add(server);
            }
        }
        usable.sort(
                Comparator.comparingInt(ServerList::rank)
                        .thenComparingLong(s -> s.failedCount().orElse(0)));
        return usable;
    }

    public static Address addressOf(ServerMet.Server server) {
        return new Address(server.ip(), server.port());
    }

    public static ServerInfo.Priority priority(ServerMet.Server server) {
        return switch ((int) server.preference().orElse(PRIORITY_NORMAL)) {
            case PRIORITY_HIGH -> ServerInfo.Priority.HIGH;
            case PRIORITY_LOW -> ServerInfo.Priority.LOW;
            default -> ServerInfo.Priority.NORMAL;
        };
    }

    private static int rank(ServerMet.Server server) {
        return switch ((int) server.preference().orElse(PRIORITY_NORMAL)) {
            case PRIORITY_HIGH -> 0;
            case PRIORITY_LOW -> 2;
            default -> 1;
        };
    }

    private static boolean isUsable(int ip) {
        return Ipv4.isUnicastPublicCandidate(ip);
    }
}
