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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A file found by a search; the source counts add up over the servers that reported it.
 *
 * @param completeSources the sources with the whole file, {@link #UNKNOWN} when the network does not tell (Kad, or a
 *     client's list of shared files)
 * @param media what the file's publishers told of its content, {@link Media#NONE} when nothing
 * @param origin where the result came from, as its spam rating weighs it
 * @param spamRating how much it looks like spam: {@value #SPAM_THRESHOLD} and more is spam
 * @param aichHash the root of the file's AICH tree, when publishers agree on one: its download can check and repair
 *     parts with it
 * @param names the names the file was found under, with the sources that gave each, most sources first; empty while it
 *     was found under one name only. The result has the first.
 */
public record SearchResult(
        Hash16 hash,
        String name,
        long size,
        int sources,
        int completeSources,
        Optional<String> type,
        Media media,
        Origin origin,
        int spamRating,
        Optional<Hash20> aichHash,
        List<Name> names) {

    /** The spam rating from which a result counts as spam. */
    public static final int SPAM_THRESHOLD = 60;

    /** A count the network does not tell. */
    public static final int UNKNOWN = -1;

    public SearchResult {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(media, "media");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(aichHash, "aichHash");
        names = List.copyOf(names);
    }

    /** A result found under one name. */
    public SearchResult(
            Hash16 hash,
            String name,
            long size,
            int sources,
            int completeSources,
            Optional<String> type,
            Media media,
            Origin origin,
            int spamRating,
            Optional<Hash20> aichHash) {
        this(hash, name, size, sources, completeSources, type, media, origin, spamRating, aichHash, List.of());
    }

    /** A result without media details. */
    public SearchResult(Hash16 hash, String name, long size, int sources, int completeSources, Optional<String> type) {
        this(hash, name, size, sources, completeSources, type, Media.NONE);
    }

    /** A result of no known origin, not rated for spam. */
    public SearchResult(
            Hash16 hash, String name, long size, int sources, int completeSources, Optional<String> type, Media media) {
        this(hash, name, size, sources, completeSources, type, media, Origin.NONE, 0, Optional.empty());
    }

    /** Whether the result counts as spam. */
    public boolean isSpam() {
        return this.spamRating >= SPAM_THRESHOLD;
    }

    public SearchResult withOrigin(Origin other) {
        return new SearchResult(
                this.hash,
                this.name,
                this.size,
                this.sources,
                this.completeSources,
                this.type,
                this.media,
                other,
                this.spamRating,
                this.aichHash,
                this.names);
    }

    public SearchResult withSpamRating(int rating) {
        return new SearchResult(
                this.hash,
                this.name,
                this.size,
                this.sources,
                this.completeSources,
                this.type,
                this.media,
                this.origin,
                rating,
                this.aichHash,
                this.names);
    }

    public SearchResult withAichHash(Optional<Hash20> root) {
        return new SearchResult(
                this.hash,
                this.name,
                this.size,
                this.sources,
                this.completeSources,
                this.type,
                this.media,
                this.origin,
                this.spamRating,
                root,
                this.names);
    }

    /** The file as found under {@code other} alone, with the sources that gave that name. */
    public SearchResult named(Name other) {
        return new SearchResult(
                this.hash,
                other.name(),
                this.size,
                other.sources(),
                other.completeSources(),
                this.type,
                this.media,
                this.origin,
                this.spamRating,
                this.aichHash);
    }

    /**
     * One name a file was found under.
     *
     * @param completeSources as for the result, {@link #UNKNOWN} when the network does not tell
     */
    public record Name(String name, int sources, int completeSources) {
        public Name {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Where a result came from: the servers that gave it over UDP, whether it also came from the connected server or
     * from Kad, the addresses ({@code Ipv4} form) of the sources the answers named, and the name without the words
     * searched for, in lower case with single spaces.
     */
    public record Origin(Set<Integer> udpServers, boolean direct, Set<Integer> sourceIps, String bareName) {

        public static final Origin NONE = new Origin(Set.of(), false, Set.of(), "");

        /** Servers and sources kept for one result: what a spammer adds beyond them changes nothing. */
        public static final int MAX_ADDRESSES = 64;

        public Origin {
            udpServers = Set.copyOf(capped(udpServers));
            sourceIps = Set.copyOf(capped(sourceIps));
            Objects.requireNonNull(bareName, "bareName");
        }

        /** Both origins of a result, as its answers came. */
        public Origin and(Origin other) {
            Set<Integer> servers = new HashSet<>(this.udpServers);
            servers.addAll(other.udpServers);
            Set<Integer> ips = new HashSet<>(this.sourceIps);
            ips.addAll(other.sourceIps);
            return new Origin(
                    servers,
                    this.direct || other.direct,
                    ips,
                    this.bareName.isEmpty() ? other.bareName : this.bareName);
        }

        private static Collection<Integer> capped(Set<Integer> addresses) {
            return addresses.size() <= MAX_ADDRESSES
                    ? addresses
                    : addresses.stream().sorted().limit(MAX_ADDRESSES).toList();
        }
    }

    /** Whether {@code name} and {@code size} make a file that can be downloaded, as {@link #link()} needs. */
    public static boolean isDownloadable(String name, long size) {
        return !name.isBlank() && size > 0 && size <= Ed2kLink.MAX_FILE_SIZE;
    }

    /** The ed2k link of the file; only for a {@linkplain #isDownloadable downloadable} name and size. */
    public Ed2kLink.FileLink link() {
        return new Ed2kLink.FileLink(this.name, this.size, this.hash, this.aichHash, List.of(), List.of(), List.of());
    }

    /**
     * Media details of a file: empty texts and zeros where they are unknown. Texts come from other users, so they lose
     * their control characters and are cut at {@value #MAX_TEXT} characters.
     *
     * @param length the playing time in seconds
     * @param bitrate in kbit/s
     */
    public record Media(String artist, String album, String title, int length, int bitrate, String codec) {

        public static final Media NONE = new Media("", "", "", 0, 0, "");

        public static final int MAX_TEXT = 256;

        public Media {
            artist = clean(artist);
            album = clean(album);
            title = clean(title);
            codec = clean(codec);
            if (length < 0 || bitrate < 0) {
                throw new IllegalArgumentException("negative length or bitrate");
            }
        }

        /** These details, the unknown ones taken from {@code other}. */
        public Media or(Media other) {
            return new Media(
                    this.artist.isEmpty() ? other.artist : this.artist,
                    this.album.isEmpty() ? other.album : this.album,
                    this.title.isEmpty() ? other.title : this.title,
                    this.length == 0 ? other.length : this.length,
                    this.bitrate == 0 ? other.bitrate : this.bitrate,
                    this.codec.isEmpty() ? other.codec : this.codec);
        }

        private static String clean(String text) {
            Objects.requireNonNull(text, "text");
            StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_TEXT));
            text.strip().codePoints().forEach(c -> {
                if (out.length() + Character.charCount(c) <= MAX_TEXT) {
                    out.appendCodePoint(Character.isISOControl(c) ? ' ' : c);
                }
            });
            return out.toString().strip();
        }
    }
}
