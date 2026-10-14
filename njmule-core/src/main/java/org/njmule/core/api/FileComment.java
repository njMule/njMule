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

/**
 * A comment on a file and its rating, from 0 (none) to {@value #MAX_RATING} (excellent); the text is one line of at
 * most {@value #MAX_LENGTH} characters.
 */
public record FileComment(String text, int rating) {

    public static final int MAX_RATING = 5;
    public static final int MAX_LENGTH = 128;

    public static final FileComment NONE = new FileComment("", 0);

    public FileComment {
        Objects.requireNonNull(text, "text");
        if (rating < 0 || rating > MAX_RATING) {
            throw new IllegalArgumentException("a rating goes from 0 to " + MAX_RATING + ": " + rating);
        }
        if (text.length() > MAX_LENGTH || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("a comment is one line of at most " + MAX_LENGTH + " characters");
        }
    }

    /** A comment made fit: control characters become spaces, the text is cut to its longest length. */
    public static FileComment of(String text, int rating) {
        StringBuilder line = new StringBuilder();
        text.strip().codePoints().forEach(c -> line.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        String fit = line.length() > MAX_LENGTH ? line.substring(0, MAX_LENGTH) : line.toString();
        if (!fit.isEmpty() && Character.isHighSurrogate(fit.charAt(fit.length() - 1))) {
            fit = fit.substring(0, fit.length() - 1);
        }
        return new FileComment(fit.strip(), Math.clamp(rating, 0, MAX_RATING));
    }

    public boolean isEmpty() {
        return this.text.isEmpty() && this.rating == 0;
    }
}
