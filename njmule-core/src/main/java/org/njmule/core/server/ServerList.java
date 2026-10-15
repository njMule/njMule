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

import com.google.common.base.CharMatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

/**
 * The known eD2K servers, kept in {@code server.met}. Only the engine thread uses it.
 *
 * <p>Servers without a usable address (port 0, a reserved IP) are not added; those known by a host name are kept by it
 * and offered for connecting only ({@link #connectable()}), as they are resolved then; servers the IP filter blocks are
 * neither added nor offered.
 */
public final class ServerList {

    public static final String FILE_NAME = "server.met";
    public static final String STATIC_FILE_NAME = "staticservers.dat";

    /** Values of the preference tag. */
    public static final int PRIORITY_NORMAL = 0;

    public static final int PRIORITY_HIGH = 1;
    public static final int PRIORITY_LOW = 2;

    private static final CharMatcher CONTROL = CharMatcher.javaIsoControl();

    /**
     * A server address: {@code ip} in {@link Ipv4} form, or, for a server known by host name, that name (lower case)
     * and ip 0, whatever it resolves to.
     */
    public record Address(int ip, int port, String host) {
        public Address {
            Objects.requireNonNull(host, "host");
        }

        public Address(int ip, int port) {
            this(ip, port, "");
        }

        /** The address of a server known by {@code host}; empty when it is an IPv4 address. */
        public static Optional<Address> named(String host, int port) {
            try {
                Ipv4.parse(host);
                return Optional.empty();
            } catch (IllegalArgumentException e) {
                return Optional.of(new Address(0, port, host.toLowerCase(Locale.ROOT)));
            }
        }

        @Override
        public String toString() {
            return (this.host.isEmpty() ? Ipv4.format(this.ip) : this.host) + ":" + this.port;
        }
    }

    private final Map<Address, ServerMet.Server> servers = new LinkedHashMap<>();
    private final Set<Address> statics = new LinkedHashSet<>();
    // Static servers not taken in the list (blocked, unusable): written back as they were.
    private final List<TextFiles.StaticServer> untaken = new ArrayList<>();
    private IntPredicate blocked = ip -> false;

    /** Reads {@code file} (or its backup); a missing file gives an empty list. */
    public static ServerList load(Path file) throws IOException {
        ServerList list = new ServerList();
        if (Files.exists(file) || Files.exists(AtomicFiles.bakPath(file.toAbsolutePath()))) {
            list.addAll(AtomicFiles.read(file, in -> ServerMet.read(in.readAllBytes())));
        }
        return list;
    }

    public void save(Path file) throws IOException {
        AtomicFiles.writeBytes(file, ServerMet.write(all()));
    }

    /** Adds the servers not known yet; returns how many were added. */
    public int addAll(Collection<ServerMet.Server> others) {
        int added = 0;
        for (ServerMet.Server server : others) {
            if (add(server)) {
                added++;
            }
        }
        return added;
    }

    /** Applies the IP filter: blocked servers are no longer added nor offered. */
    public void filter(IntPredicate blocks) {
        this.blocked = Objects.requireNonNull(blocks, "blocks");
    }

    /**
     * Adds a server unless its address is already known, blocked or cannot be one of a server. Its name and description
     * come from other clients' lists and are shown: they lose their control characters.
     */
    public boolean add(ServerMet.Server server) {
        if (server.port() == 0
                || !(isUsable(server.ip()) || server.dynamicIp().isPresent())
                || (server.ip() != 0 && this.blocked.test(server.ip()))) {
            return false;
        }
        return this.servers.putIfAbsent(addressOf(server), shown(server)) == null;
    }

    private static ServerMet.Server shown(ServerMet.Server server) {
        ServerMet.Server shown = server;
        Optional<String> name = server.name();
        if (name.isPresent() && CONTROL.matchesAnyOf(name.get())) {
            shown = shown.with(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), CONTROL.removeFrom(name.get())));
        }
        Optional<String> description = server.description();
        if (description.isPresent() && CONTROL.matchesAnyOf(description.get())) {
            shown = shown.with(
                    new Tag.StringTag(TagName.id(ServerTag.DESCRIPTION), CONTROL.removeFrom(description.get())));
        }
        return shown;
    }

    public Optional<ServerMet.Server> get(Address address) {
        return Optional.ofNullable(this.servers.get(address));
    }

    public void update(Address address, UnaryOperator<ServerMet.Server> change) {
        this.servers.computeIfPresent(address, (key, server) -> change.apply(server));
    }

    public boolean remove(Address address) {
        this.statics.remove(address);
        return this.servers.remove(address) != null;
    }

    /**
     * Reads the static servers of {@code file} ({@code host:port,priority,name}): missing ones are added, and each
     * takes the priority written there.
     */
    public void loadStatics(Path file) throws IOException {
        if (!Files.exists(file) && !Files.exists(AtomicFiles.bakPath(file.toAbsolutePath()))) {
            return;
        }
        this.untaken.clear();
        for (TextFiles.StaticServer entry : AtomicFiles.read(file, in -> TextFiles.staticServers(in.readAllBytes()))) {
            ServerMet.Server server = serverOf(entry);
            Address address = addressOf(server);
            add(server);
            if (this.servers.containsKey(address)) {
                this.servers.computeIfPresent(address, (key, s) -> {
                    ServerMet.Server kept = s.with(preferenceTag(entry.priority()));
                    return kept.name().isPresent() || entry.name().isBlank()
                            ? kept
                            : kept.with(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), entry.name()));
                });
                this.statics.add(address);
            } else {
                this.untaken.add(entry);
            }
        }
    }

    // One server a line: names and hosts come from servers and lists, so line breaks and other control characters go.
    public void saveStatics(Path file) throws IOException {
        List<TextFiles.StaticServer> entries = new ArrayList<>();
        for (Address address : this.statics) {
            ServerMet.Server server = this.servers.get(address);
            if (server != null) {
                String host = CONTROL.removeFrom(server.dynamicIp().orElse(Ipv4.format(server.ip())));
                int priority = (int) server.preference().orElse(PRIORITY_NORMAL);
                entries.add(new TextFiles.StaticServer(
                        host,
                        server.port(),
                        priority,
                        CONTROL.removeFrom(server.name().orElse(host))));
            }
        }
        for (TextFiles.StaticServer entry : this.untaken) {
            if (!this.statics.contains(addressOf(serverOf(entry)))) {
                entries.add(entry);
            }
        }
        AtomicFiles.writeBytes(file, TextFiles.writeStaticServers(entries));
    }

    public boolean isStatic(Address address) {
        return this.statics.contains(address);
    }

    /** Puts a known server in the static list or takes it out; false when unknown or already so. */
    public boolean setStatic(Address address, boolean isStatic) {
        if (!this.servers.containsKey(address)) {
            return false;
        }
        return isStatic ? this.statics.add(address) : this.statics.remove(address);
    }

    /** Sets the priority a known server is tried with; false when unknown. */
    public boolean setPriority(Address address, ServerInfo.Priority priority) {
        int value = switch (priority) {
            case HIGH -> PRIORITY_HIGH;
            case NORMAL -> PRIORITY_NORMAL;
            case LOW -> PRIORITY_LOW;
        };
        return this.servers.computeIfPresent(address, (key, s) -> s.with(preferenceTag(value))) != null;
    }

    /** Whether the server announced TCP obfuscation and a port it takes it on. */
    public static boolean supportsObfuscation(ServerMet.Server server) {
        long flags = server.udpFlags().orElse(0);
        long port = server.obfuscatedTcpPort().orElse(0);
        return port > 0
                && port <= 0xFFFF
                && (flags & (ServerDatagrams.UDP_OBFUSCATION | ServerDatagrams.UDP_TCP_OBFUSCATION)) != 0;
    }

    private static ServerMet.Server serverOf(TextFiles.StaticServer entry) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(ServerTag.SERVERNAME), entry.name()));
        int ip = 0;
        try {
            ip = Ipv4.parse(entry.host());
        } catch (IllegalArgumentException e) {
            // A host name: resolved when connecting.
            tags.add(new Tag.StringTag(TagName.id(ServerTag.DYNIP), entry.host()));
        }
        return new ServerMet.Server(ip, entry.port(), tags);
    }

    private static Tag preferenceTag(int value) {
        return new Tag.IntTag(TagName.id(ServerTag.PREFERENCE), value);
    }

    public List<ServerMet.Server> all() {
        return List.copyOf(this.servers.values());
    }

    public int size() {
        return this.servers.size();
    }

    /** The servers that failed {@code retries} times in a row or more; static servers are never dead. */
    public List<Address> dead(int retries) {
        List<Address> dead = new ArrayList<>();
        for (Map.Entry<Address, ServerMet.Server> e : this.servers.entrySet()) {
            if (e.getValue().failedCount().orElse(0) >= retries && !this.statics.contains(e.getKey())) {
                dead.add(e.getKey());
            }
        }
        return dead;
    }

    /** The servers at a usable address, to ask over UDP: high priority first, then those that failed least. */
    public List<ServerMet.Server> candidates() {
        return sorted(s -> accepts(s.ip()));
    }

    /** As {@link #candidates()}, with the servers known by host name too, resolved when connecting. */
    public List<ServerMet.Server> connectable() {
        return sorted(s -> accepts(s.ip()) || s.dynamicIp().isPresent());
    }

    /** Whether a server may be at {@code ip}: a usable address the filters let through. */
    public boolean accepts(int ip) {
        return isUsable(ip) && !this.blocked.test(ip);
    }

    private List<ServerMet.Server> sorted(Predicate<ServerMet.Server> wanted) {
        List<ServerMet.Server> usable = new ArrayList<>();
        for (ServerMet.Server server : this.servers.values()) {
            if (wanted.test(server)) {
                usable.add(server);
            }
        }
        usable.sort(Comparator.comparingInt(ServerList::rank)
                .thenComparingLong(s -> s.failedCount().orElse(0)));
        return usable;
    }

    /** A server known by host name keeps its address whatever the name resolves to. */
    public static Address addressOf(ServerMet.Server server) {
        return server.dynamicIp()
                .map(host -> new Address(0, server.port(), host.toLowerCase(Locale.ROOT)))
                .orElseGet(() -> new Address(server.ip(), server.port()));
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
