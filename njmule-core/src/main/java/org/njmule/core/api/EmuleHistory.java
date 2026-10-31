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

import java.util.List;

/**
 * What was typed in the boxes of the eMule whose profile was imported, the newest first, for the interfaces to offer
 * again.
 *
 * @param searches the searches typed
 * @param serverLists the addresses of the server lists typed
 */
public record EmuleHistory(List<String> searches, List<String> serverLists) {

    /** Nothing typed, or no profile imported. */
    public static final EmuleHistory NONE = new EmuleHistory(List.of(), List.of());

    public EmuleHistory {
        searches = List.copyOf(searches);
        serverLists = List.copyOf(serverLists);
    }
}
