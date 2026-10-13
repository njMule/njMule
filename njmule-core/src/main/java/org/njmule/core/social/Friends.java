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
package org.njmule.core.social;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.FriendsMet;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The friends, kept in {@code emfriends.met}; what njMule does not change (Kad id, times) is kept as read. Engine
 * thread only.
 */
public final class Friends {

    public static final String FILE_NAME = "emfriends.met";

    private static final Logger logger = LoggerFactory.getLogger(Friends.class);

    private final Path file;
    private final Map<Hash16, FriendsMet.Friend> friends = new LinkedHashMap<>();

    private Friends(Path file) {
        this.file = file;
    }

    /** Reads the friends of {@code configDir}; a broken file counts as none. */
    public static Friends load(Path configDir) {
        Friends store = new Friends(configDir.resolve(FILE_NAME));
        if (Files.exists(store.file) || Files.exists(AtomicFiles.bakPath(store.file.toAbsolutePath()))) {
            try {
                for (FriendsMet.Friend f : AtomicFiles.read(store.file, in -> FriendsMet.read(in.readAllBytes()))) {
                    store.friends.put(f.userHash(), f);
                }
            } catch (IOException e) {
                logger.warn("Cannot read {}", store.file, e);
            }
        }
        return store;
    }

    public boolean contains(Hash16 userHash) {
        return this.friends.containsKey(userHash);
    }

    public List<Friend> all() {
        List<Friend> all = new ArrayList<>();
        for (FriendsMet.Friend f : this.friends.values()) {
            Optional<ServerAddress> address = f.lastIp() == 0 || f.lastPort() == 0
                    ? Optional.empty()
                    : Optional.of(new ServerAddress(Ipv4.format(f.lastIp()), f.lastPort()));
            all.add(new Friend(f.userHash(), f.name().orElse(""), address));
        }
        return all;
    }

    /** Adds {@code friend}, or updates its name and address. */
    public void add(Friend friend) throws IOException {
        FriendsMet.Friend known = this.friends.get(friend.userHash());
        int ip = 0;
        int port = 0;
        if (friend.address().isPresent()) {
            try {
                ip = Ipv4.parse(friend.address().get().host());
                port = friend.address().get().port();
            } catch (IllegalArgumentException e) {
                // The file holds IPv4 addresses only: names are not kept.
            }
        }
        List<Tag> tags = new ArrayList<>();
        if (!friend.name().isEmpty()) {
            tags.add(new Tag.StringTag(TagName.id(FriendsMet.NAME), friend.name()));
        }
        if (known != null) {
            known.tags().stream()
                    .filter(t -> !t.name().equals(TagName.id(FriendsMet.NAME)))
                    .forEach(tags::add);
        }
        this.friends.put(
                friend.userHash(),
                new FriendsMet.Friend(
                        friend.userHash(),
                        ip,
                        port,
                        known == null ? 0 : known.lastSeen(),
                        known == null ? 0 : known.lastChatted(),
                        tags));
        this.save();
    }

    public boolean remove(Hash16 userHash) throws IOException {
        boolean removed = this.friends.remove(userHash) != null;
        if (removed) {
            this.save();
        }
        return removed;
    }

    private void save() throws IOException {
        AtomicFiles.writeBytes(this.file, FriendsMet.write(List.copyOf(this.friends.values())));
    }
}
