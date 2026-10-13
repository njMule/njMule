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
package org.njmule.core.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.ClientsMet;
import org.njmule.core.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What we uploaded to and downloaded from each client, by user hash, kept in {@code clients.met}
 * with the public key a client proved it owns. Clients that gave us data get a better place in our
 * upload queue. Engine thread only.
 */
public final class Credits {

    public static final String FILE_NAME = "clients.met";

    /** Clients not seen for this long are forgotten. */
    public static final Duration EXPIRY = Duration.ofDays(150);

    private static final long MIB = 1024 * 1024;
    // Below this many bytes received the ratio grows linearly from 1.
    private static final long RAMP_END = 9_646_899;
    private static final Logger LOG = LoggerFactory.getLogger(Credits.class);

    /** The credit of one client. */
    public static final class Credit {
        private final Hash16 userHash;
        private long uploaded;
        private long downloaded;
        private long lastSeen;
        private Bytes publicKey;

        Credit(Hash16 userHash, long uploaded, long downloaded, long lastSeen, Bytes publicKey) {
            this.userHash = userHash;
            this.uploaded = uploaded;
            this.downloaded = downloaded;
            this.lastSeen = lastSeen;
            this.publicKey = publicKey;
        }

        public long uploaded() {
            return this.uploaded;
        }

        public long downloaded() {
            return this.downloaded;
        }

        /** The key the client proved it owns, empty if it never did. */
        public Bytes publicKey() {
            return this.publicKey;
        }
    }

    private final Path file;
    private final Clock clock;
    private final Map<Hash16, Credit> credits = new LinkedHashMap<>();
    private boolean dirty;

    private Credits(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Reads {@code clients.met} of {@code configDir}, dropping expired clients. */
    public static Credits load(Path configDir, Clock clock) {
        Credits store = new Credits(configDir.resolve(FILE_NAME), Objects.requireNonNull(clock));
        if (!Files.exists(store.file)
                && !Files.exists(AtomicFiles.bakPath(store.file.toAbsolutePath()))) {
            return store;
        }
        long oldest = store.now() - EXPIRY.toSeconds();
        try {
            for (ClientsMet.Credit c :
                    AtomicFiles.read(store.file, in -> ClientsMet.read(in.readAllBytes()))) {
                if (c.lastSeen() >= oldest) {
                    store.credits.put(
                            c.userHash(),
                            new Credit(
                                    c.userHash(),
                                    c.uploaded(),
                                    c.downloaded(),
                                    c.lastSeen(),
                                    c.publicKey()));
                }
            }
        } catch (IOException e) {
            LOG.warn("Cannot read {}, credits start from zero", store.file, e);
        }
        return store;
    }

    public Optional<Credit> get(Hash16 userHash) {
        return Optional.ofNullable(this.credits.get(userHash));
    }

    /** We sent {@code bytes} to the client. */
    public void uploaded(Hash16 userHash, long bytes) {
        this.credit(userHash).uploaded += bytes;
        this.dirty = true;
    }

    /** The client sent us {@code bytes}. */
    public void downloaded(Hash16 userHash, long bytes) {
        this.credit(userHash).downloaded += bytes;
        this.dirty = true;
    }

    /**
     * The client proved it owns {@code key}. The first key is pinned; credit gathered under the
     * user hash before that, by anyone, starts again from nothing.
     */
    public void identified(Hash16 userHash, Bytes key) {
        Credit credit = this.credit(userHash);
        if (credit.publicKey.length() == 0) {
            credit.publicKey = key;
            if (credit.downloaded > 0) {
                credit.downloaded = 1;
                credit.uploaded = 1;
            }
            this.dirty = true;
        }
    }

    /**
     * The factor a client's waiting time is multiplied by in our queue: from 1 to 10, growing with
     * what it gave us compared with what it got. Clients whose identity is in doubt get 1.
     */
    public double ratio(Hash16 userHash, boolean trusted) {
        Credit credit = this.credits.get(userHash);
        if (!trusted || credit == null || credit.downloaded < MIB) {
            return 1;
        }
        double given = credit.uploaded == 0 ? 10 : credit.downloaded * 2.0 / credit.uploaded;
        double bySize = Math.sqrt(credit.downloaded / (double) MIB + 2);
        double ramp =
                credit.downloaded < RAMP_END
                        ? (credit.downloaded - MIB) / (double) (RAMP_END - MIB) * 2.34 + 1
                        : 10;
        return Math.clamp(Math.min(given, Math.min(bySize, ramp)), 1.0, 10.0);
    }

    /** Writes {@code clients.met} if anything changed. */
    public void save() {
        if (!this.dirty) {
            return;
        }
        List<ClientsMet.Credit> all = new ArrayList<>(this.credits.size());
        for (Credit c : this.credits.values()) {
            if (c.uploaded == 0 && c.downloaded == 0) {
                continue;
            }
            all.add(
                    new ClientsMet.Credit(
                            c.userHash, c.uploaded, c.downloaded, c.lastSeen, c.publicKey));
        }
        try {
            AtomicFiles.writeBytes(this.file, ClientsMet.write(all));
            this.dirty = false;
        } catch (IOException e) {
            LOG.warn("Cannot save {}", this.file, e);
        }
    }

    private Credit credit(Hash16 userHash) {
        Credit credit =
                this.credits.computeIfAbsent(
                        userHash, h -> new Credit(h, 0, 0, this.now(), Bytes.EMPTY));
        credit.lastSeen = this.now();
        return credit;
    }

    private long now() {
        return this.clock.instant().getEpochSecond();
    }
}
