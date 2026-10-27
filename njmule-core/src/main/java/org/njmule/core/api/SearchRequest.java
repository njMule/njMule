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
import org.njmule.core.search.SearchParser;

/** A search: an expression in the eMule syntax ({@code ubuntu iso @size>700m}) and how far it goes. */
public record SearchRequest(String expression, Scope scope) {

    /** Where to search. */
    public enum Scope {
        /** The connected server only. */
        SERVER,
        /** The connected server, then the other known servers over UDP. */
        GLOBAL,
        /**
         * The Kad network, without servers: the longest word every result must have is looked up, and what comes back
         * is matched against the whole expression.
         */
        KAD
    }

    /** The longest expression a search takes. */
    public static final int MAX_EXPRESSION_LENGTH = SearchParser.MAX_LENGTH;

    // A big server is worth more than Kad, but only in a short server list: a long one may be full of fake servers.
    private static final long BIG_SERVER_MIN_USERS = 40_000;
    private static final long BIG_SERVER_MAX_USERS = 2_000_000;
    private static final long BIG_SERVER_MIN_FILES = 5_000_000;
    private static final int SHORT_SERVER_LIST = 40;

    /** Checks the syntax of {@code expression}; the exception says what is wrong. */
    public static void checkExpression(String expression) {
        SearchParser.parse(expression);
    }

    /**
     * Where an automatic search goes: the network connected, and Kad when both are, unless the server is a static one
     * or a big one in a short list; empty with neither.
     *
     * @param server what is known of the server connected to
     * @param knownServers the servers of the list
     */
    public static Optional<Scope> automatic(
            boolean serverConnected, boolean kadConnected, Optional<ServerInfo> server, int knownServers) {
        if (!serverConnected) {
            return kadConnected ? Optional.of(Scope.KAD) : Optional.empty();
        }
        boolean stays = server.map(s -> s.isStatic() || isBig(s, knownServers)).orElse(false);
        return Optional.of(!kadConnected || stays ? Scope.SERVER : Scope.KAD);
    }

    private static boolean isBig(ServerInfo server, int knownServers) {
        return server.users() > BIG_SERVER_MIN_USERS
                && server.users() < BIG_SERVER_MAX_USERS
                && server.files() > BIG_SERVER_MIN_FILES
                && knownServers < SHORT_SERVER_LIST;
    }

    public SearchRequest {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(scope, "scope");
    }
}
