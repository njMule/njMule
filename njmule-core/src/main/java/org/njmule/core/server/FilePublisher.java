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

import com.google.common.base.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.files.FileTypes;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

/**
 * Tells the connected server which files we share, so that it gives us as a source to the clients
 * that look for them: the files not offered yet, in batches, at most one batch a minute. Engine
 * thread only.
 */
public final class FilePublisher {

    /** A file to offer; {@code complete} is false for the shared parts of a download. */
    public record Offer(Hash16 hash, String name, long size, boolean complete) {
        public Offer {
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(name, "name");
        }
    }

    /** Files per batch. */
    public static final int BATCH = 200;

    /** Time between batches. */
    public static final Duration INTERVAL = Duration.ofSeconds(60);

    // Ids and ports that servers with compression read as "this client, complete or not".
    private static final long COMPLETE_ID = 0xFBFB_FBFBL;
    private static final int COMPLETE_PORT = 0xFBFB;
    private static final long PARTIAL_ID = 0xFCFC_FCFCL;
    private static final int PARTIAL_PORT = 0xFCFC;
    private static final int FILESIZE_HI = 0x3A;

    private final ServerSession session;
    private final Supplier<List<Offer>> files;
    private final IntSupplier listenPort;
    private final Ticker ticker;
    private final Set<Hash16> offered = new HashSet<>();
    private boolean needed;
    private long next;

    public FilePublisher(
            ServerSession session,
            Supplier<List<Offer>> files,
            IntSupplier listenPort,
            Ticker ticker) {
        this.session = Objects.requireNonNull(session, "session");
        this.files = Objects.requireNonNull(files, "files");
        this.listenPort = Objects.requireNonNull(listenPort, "listenPort");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** Logged in: everything is offered again, the first batch at once. */
    public void connected() {
        this.offered.clear();
        this.needed = true;
        this.next = this.ticker.read();
    }

    public void disconnected() {
        this.offered.clear();
        this.needed = false;
    }

    /** A file was shared or completed. */
    public void changed(Optional<Hash16> completed) {
        completed.ifPresent(this.offered::remove);
        this.needed = true;
    }

    /** Sends the next batch when one is due; call it every second or so. */
    public void tick() {
        long now = this.ticker.read();
        Optional<ServerMessages.IdChange> id = this.session.id();
        if (!this.needed || now - this.next < 0 || !this.session.isConnected() || id.isEmpty()) {
            return;
        }
        int flags = id.get().serverFlags();
        boolean large = (flags & ServerMessages.TCP_LARGEFILES) != 0;
        List<FileEntry> batch = new ArrayList<>();
        List<Hash16> hashes = new ArrayList<>();
        for (Offer offer : this.files.get()) {
            if (batch.size() >= BATCH) {
                break;
            }
            if (this.offered.contains(offer.hash())
                    || offer.size() > Ed2k.OLD_MAX_FILE_SIZE && !large) {
                continue;
            }
            batch.add(this.entry(offer, id.get(), flags));
            hashes.add(offer.hash());
        }
        if (batch.isEmpty()) {
            this.needed = false;
            return;
        }
        Packet packet = ServerMessages.offerFiles(batch, (flags & ServerMessages.TCP_NEWTAGS) != 0);
        if ((flags & ServerMessages.TCP_COMPRESSION) != 0) {
            packet = Packing.pack(packet);
        }
        if (this.session.send(packet)) {
            this.offered.addAll(hashes);
            this.next = now + INTERVAL.toNanos();
        }
    }

    private FileEntry entry(Offer offer, ServerMessages.IdChange id, int flags) {
        long clientId;
        int port;
        if ((flags & ServerMessages.TCP_COMPRESSION) != 0) {
            clientId = offer.complete() ? COMPLETE_ID : PARTIAL_ID;
            port = offer.complete() ? COMPLETE_PORT : PARTIAL_PORT;
        } else if (id.isLowId()) {
            clientId = 0;
            port = 0;
        } else {
            clientId = id.clientId();
            port = this.listenPort.getAsInt();
        }
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), offer.name()));
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), offer.size() & 0xFFFF_FFFFL));
        if (offer.size() > 0xFFFF_FFFFL) {
            tags.add(new Tag.IntTag(TagName.id(FILESIZE_HI), offer.size() >>> 32));
        }
        FileTypes.of(offer.name())
                .ifPresent(
                        type ->
                                tags.add(
                                        (flags & ServerMessages.TCP_TYPE_TAG_INTEGER) != 0
                                                ? new Tag.IntTag(
                                                        TagName.id(FileTag.FILETYPE),
                                                        FileTypes.code(type))
                                                : new Tag.StringTag(
                                                        TagName.id(FileTag.FILETYPE), type)));
        return new FileEntry(offer.hash(), clientId, port, tags);
    }
}
