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

import java.io.Serial;

/** An invalid search expression; {@link #reason()} lets front ends show a translated message. */
public final class SearchSyntaxException extends IllegalArgumentException {

    @Serial private static final long serialVersionUID = 1L;

    /** Why the expression was refused. */
    public enum Reason {
        GENERAL,
        MISSING_AND_OPERAND,
        MISSING_OR_OPERAND,
        MISSING_NOT_OPERAND,
        MISSING_EXPRESSION_IN_PARENTHESES,
        MISSING_CLOSING_PARENTHESIS,
        UNKNOWN_ATTRIBUTE,
        INVALID_ATTRIBUTE_VALUE,
        OPERATOR_WITHOUT_ATTRIBUTE,
        UNTERMINATED_STRING,
        TOO_COMPLEX,
        INVALID_ED2K_LINK
    }

    private final Reason reason;
    private final int position;

    SearchSyntaxException(Reason reason, int position, String detail) {
        super(reason + " at " + position + (detail.isEmpty() ? "" : ": " + detail));
        this.reason = reason;
        this.position = position;
    }

    public Reason reason() {
        return this.reason;
    }

    /** Character offset in the expression where the problem was found. */
    public int position() {
        return this.position;
    }
}
