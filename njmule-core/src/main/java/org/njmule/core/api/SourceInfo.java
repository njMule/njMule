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
package org.njmule.core.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A client we download from or may download from.
 *
 * @param address {@code ip:port}
 * @param queueRank our place in its queue, 0 when unknown or not queued
 * @param received bytes it sent us in this session
 * @param parts the parts of the file it has, of {@code partCount}; 0 when not known yet
 * @param askedFor in state {@link State#A4AF}, the download the client is asked for instead, with its name
 */
public record SourceInfo(
        Hash16 file,
        String fileName,
        String address,
        Optional<String> nick,
        String software,
        State state,
        int queueRank,
        long received,
        int parts,
        int partCount,
        Optional<Hash16> askedFor,
        String askedForName) {

    public enum State {
        /** Asked again later. */
        WAITING,
        CONNECTING,
        /** Greeted and asked for the file. */
        ASKING,
        QUEUED,
        DOWNLOADING,
        /** Asked for another file: a client is asked for one download at a time, here for {@code askedFor}. */
        A4AF
    }

    public SourceInfo {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(nick, "nick");
        Objects.requireNonNull(software, "software");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(askedFor, "askedFor");
        Objects.requireNonNull(askedForName, "askedForName");
    }

    /** A source asked for this download, or waiting to be. */
    public SourceInfo(
            Hash16 file,
            String fileName,
            String address,
            Optional<String> nick,
            String software,
            State state,
            int queueRank,
            long received,
            int parts,
            int partCount) {
        this(
                file,
                fileName,
                address,
                nick,
                software,
                state,
                queueRank,
                received,
                parts,
                partCount,
                Optional.empty(),
                "");
    }
}
