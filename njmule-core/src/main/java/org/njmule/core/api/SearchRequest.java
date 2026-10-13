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
import org.njmule.core.search.SearchParser;

/** A search: an expression in the eMule syntax ({@code ubuntu iso @size>700m}) and how far it goes. */
public record SearchRequest(String expression, Scope scope) {

    /** Where to search. */
    public enum Scope {
        /** The connected server only. */
        SERVER,
        /** The connected server, then the other known servers over UDP. */
        GLOBAL,
        /** The Kad network, without servers; the first word of the expression is looked up. */
        KAD
    }

    /** The longest expression a search takes. */
    public static final int MAX_EXPRESSION_LENGTH = SearchParser.MAX_LENGTH;

    /** Checks the syntax of {@code expression}; the exception says what is wrong. */
    public static void checkExpression(String expression) {
        SearchParser.parse(expression);
    }

    public SearchRequest {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(scope, "scope");
    }
}
