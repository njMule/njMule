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
package org.njmule.core.search;

import java.util.Objects;

/** A boolean search expression. {@code NOT} is binary: {@code a NOT b} matches {@code a} without {@code b}. */
public sealed interface SearchExpr {

    /** Comparison operators of numeric attributes, with their wire values. */
    enum Comparison {
        EQUAL(0, "="),
        GREATER(1, ">"),
        LESS(2, "<"),
        GREATER_EQUAL(3, ">="),
        LESS_EQUAL(4, "<="),
        NOT_EQUAL(5, "<>");

        private final int code;
        private final String symbol;

        Comparison(int code, String symbol) {
            this.code = code;
            this.symbol = symbol;
        }

        public int code() {
            return this.code;
        }

        public String symbol() {
            return this.symbol;
        }

        public static Comparison of(int code) {
            for (Comparison c : values()) {
                if (c.code == code) {
                    return c;
                }
            }
            throw new IllegalArgumentException("unknown comparison " + code);
        }
    }

    record And(SearchExpr left, SearchExpr right) implements SearchExpr {
        public And {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Or(SearchExpr left, SearchExpr right) implements SearchExpr {
        public Or {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Not(SearchExpr left, SearchExpr right) implements SearchExpr {
        public Not {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    /** A word or a quoted phrase (quotes kept) matched against file names. */
    record Keyword(String text) implements SearchExpr {
        public Keyword {
            Objects.requireNonNull(text, "text");
            if (text.isEmpty()) {
                throw new IllegalArgumentException("empty keyword");
            }
        }
    }

    /** A string attribute such as the file type or extension ({@code FileTag} id). */
    record StringAttr(int tag, String value) implements SearchExpr {
        public StringAttr {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A numeric attribute such as the size or the number of sources ({@code FileTag} id). */
    record NumericAttr(int tag, Comparison comparison, long value) implements SearchExpr {
        public NumericAttr {
            Objects.requireNonNull(comparison, "comparison");
            if (value < 0) {
                throw new IllegalArgumentException("negative value " + value);
            }
        }
    }
}
