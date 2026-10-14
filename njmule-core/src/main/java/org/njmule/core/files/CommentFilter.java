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
package org.njmule.core.files;

import com.google.common.base.Splitter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The comment filter: what others say of a file is left out, as spam, when it holds one of the {@code words}. */
public record CommentFilter(List<String> words) {

    public CommentFilter {
        words = List.copyOf(words.stream()
                .map(w -> w.strip().toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty())
                .toList());
    }

    /** The filter of the settings, whose words are apart by {@code |}. */
    public static CommentFilter of(String settings) {
        return new CommentFilter(Splitter.on('|').splitToList(Objects.requireNonNull(settings, "settings")));
    }

    /** Whether {@code text} holds a word of the filter, in any case. */
    public boolean leavesOut(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return this.words.stream().anyMatch(lower::contains);
    }
}
