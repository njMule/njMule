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
package org.njmule.core.download;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.njmule.core.files.PartFile;

/**
 * Which sources of a download are kept for the next run: those that served it best now, then those kept before and not
 * seen again, until they are a week old.
 */
final class KnownSources {

    /** A source kept this long without being found good again is let go. */
    static final Duration LIFETIME = Duration.ofDays(7);

    /** A source kept less than this ago keeps the date it had: the same sources leave the download as saved. */
    static final Duration FRESH = Duration.ofDays(1);

    /** How a source stands now, best first. */
    enum Standing {
        DOWNLOADING,
        QUEUED,
        ANSWERED
    }

    /**
     * A source as it stands now: its place in the queue of the client when queued, and how many parts it has of the
     * file.
     */
    record Candidate(PartFile.KnownSource source, Standing standing, int rank, int parts) {
        Candidate {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(standing, "standing");
        }
    }

    private static final Comparator<Candidate> BEST = Comparator.comparing(Candidate::standing)
            .thenComparingInt(c -> c.standing() == Standing.QUEUED ? c.rank() : 0)
            .thenComparing(Comparator.comparingInt(Candidate::parts).reversed());

    private KnownSources() {}

    /**
     * The sources to keep: the best of those {@code now}, then those kept {@code before} that are not among them, while
     * still young at {@code nowSeconds}, up to {@link PartFile#MAX_KNOWN_SOURCES}.
     */
    static List<PartFile.KnownSource> choose(List<Candidate> now, List<PartFile.KnownSource> before, long nowSeconds) {
        Map<Long, PartFile.KnownSource> keptBefore = new HashMap<>();
        for (PartFile.KnownSource source : before) {
            keptBefore.putIfAbsent(place(source), source);
        }
        List<PartFile.KnownSource> kept = new ArrayList<>();
        Set<Long> places = new HashSet<>();
        for (Candidate candidate : now.stream().sorted(BEST).toList()) {
            PartFile.KnownSource source = candidate.source();
            if (kept.size() < PartFile.MAX_KNOWN_SOURCES && places.add(place(source))) {
                PartFile.KnownSource earlier = keptBefore.get(place(source));
                kept.add(
                        earlier != null && earlier.seen() >= nowSeconds - FRESH.toSeconds()
                                ? new PartFile.KnownSource(
                                        source.ip(),
                                        source.port(),
                                        earlier.seen(),
                                        source.userHash(),
                                        source.cryptOptions())
                                : source);
            }
        }
        for (PartFile.KnownSource source : young(before, nowSeconds)) {
            if (kept.size() < PartFile.MAX_KNOWN_SOURCES && places.add(place(source))) {
                kept.add(source);
            }
        }
        return List.copyOf(kept);
    }

    /** The sources of {@code kept} found good within {@link #LIFETIME} of {@code nowSeconds}. */
    static List<PartFile.KnownSource> young(List<PartFile.KnownSource> kept, long nowSeconds) {
        long oldest = nowSeconds - LIFETIME.toSeconds();
        return kept.stream().filter(s -> s.seen() >= oldest).toList();
    }

    private static long place(PartFile.KnownSource source) {
        return Integer.toUnsignedLong(source.ip()) << 16 | source.port();
    }
}
