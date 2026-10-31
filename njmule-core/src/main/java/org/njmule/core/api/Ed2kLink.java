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

import com.google.common.net.HostAndPort;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * eD2K links ({@code ed2k://|file|…|/}, {@code |server|}, {@code |serverlist|}, {@code |nodeslist|}, {@code |search|})
 * and the eD2K form of magnet links. Parsing is strict about the essentials (name, size, hash) and lenient about
 * optional parts, which are dropped when invalid.
 */
public sealed interface Ed2kLink {

    /** Largest file size a link may announce (2^38 bytes). */
    long MAX_FILE_SIZE = 1L << 38;

    /** Most sources taken from one link. */
    int MAX_SOURCES = 32_767;

    String toUri();

    /** Parses a link; throws {@link IllegalArgumentException} with a reason when invalid. */
    static Ed2kLink parse(String text) {
        return Ed2kLinkCodec.parse(text);
    }

    static Optional<Ed2kLink> tryParse(String text) {
        try {
            return Optional.of(parse(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A source given in a link: an IPv4 address or a host name, and a TCP port. */
    record Source(String host, int port) {
        public Source {
            Objects.requireNonNull(host, "host");
            if (host.isEmpty() || port < 1 || port > 0xFFFF) {
                throw new IllegalArgumentException("invalid source " + host + ":" + port);
            }
        }
    }

    record FileLink(
            String name,
            long size,
            Hash16 hash,
            Optional<Hash20> aichHash,
            List<Hash16> partHashes,
            List<Source> sources,
            List<String> httpSources)
            implements Ed2kLink {

        public FileLink {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(aichHash, "aichHash");
            if (name.isBlank()) {
                throw new IllegalArgumentException("a file link needs a name");
            }
            if (size <= 0 || size > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("file size out of range: " + size);
            }
            partHashes = List.copyOf(partHashes);
            sources = List.copyOf(sources);
            httpSources = List.copyOf(httpSources);
        }

        /** The same link under another name. */
        public FileLink withName(String other) {
            return new FileLink(
                    other, this.size, this.hash, this.aichHash, this.partHashes, this.sources, this.httpSources);
        }

        public static FileLink of(String name, long size, Hash16 hash) {
            return new FileLink(name, size, hash, Optional.empty(), List.of(), List.of(), List.of());
        }

        @Override
        public String toUri() {
            StringBuilder out = new StringBuilder("ed2k://|file|");
            out.append(Ed2kLinkCodec.encode(this.name))
                    .append('|')
                    .append(this.size)
                    .append('|');
            out.append(this.hash.toHex()).append('|');
            this.aichHash.ifPresent(h -> out.append("h=").append(h.toBase32()).append('|'));
            if (!this.partHashes.isEmpty()) {
                out.append("p=");
                for (int i = 0; i < this.partHashes.size(); i++) {
                    out.append(i == 0 ? "" : ":").append(this.partHashes.get(i).toHex());
                }
                out.append('|');
            }
            out.append('/');
            if (!this.sources.isEmpty()) {
                out.append("|sources");
                for (Source s : this.sources) {
                    out.append(',').append(HostAndPort.fromParts(s.host(), s.port()));
                }
                out.append("|/");
            }
            return out.toString();
        }
    }

    record ServerLink(String host, int port) implements Ed2kLink {
        public ServerLink {
            Objects.requireNonNull(host, "host");
            if (host.isEmpty() || port < 1 || port > 0xFFFF) {
                throw new IllegalArgumentException("invalid server " + host + ":" + port);
            }
        }

        @Override
        public String toUri() {
            return "ed2k://|server|" + this.host + "|" + this.port + "|/";
        }
    }

    /** A link to a server.met file to download. */
    record ServerListLink(String url) implements Ed2kLink {
        public ServerListLink {
            Ed2kLinkCodec.checkUrl(url);
        }

        @Override
        public String toUri() {
            return "ed2k://|serverlist|" + this.url + "|/";
        }
    }

    /** A link to a Kad nodes.dat file to download. */
    record NodesListLink(String url) implements Ed2kLink {
        public NodesListLink {
            Ed2kLinkCodec.checkUrl(url);
        }

        @Override
        public String toUri() {
            return "ed2k://|nodeslist|" + this.url + "|/";
        }
    }

    record SearchLink(String term) implements Ed2kLink {
        public SearchLink {
            Objects.requireNonNull(term, "term");
            if (term.isBlank()) {
                throw new IllegalArgumentException("empty search term");
            }
        }

        @Override
        public String toUri() {
            return "ed2k://|search|" + Ed2kLinkCodec.encode(this.term) + "|/";
        }
    }
}
