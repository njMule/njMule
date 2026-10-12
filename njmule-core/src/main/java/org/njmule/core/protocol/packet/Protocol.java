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
package org.njmule.core.protocol.packet;

/** The protocol byte that starts every eD2K and Kad packet. */
public final class Protocol {

    public static final int EDONKEY = 0xE3;
    public static final int EMULE = 0xC5;

    /** zlib-packed {@link #EMULE} packet. */
    public static final int PACKED = 0xD4;

    public static final int KAD = 0xE4;

    /** zlib-packed {@link #KAD} packet. */
    public static final int KAD_PACKED = 0xE5;

    /** Reserved so that obfuscated UDP packets can be told apart from plain ones. */
    public static final int UDP_RESERVED_1 = 0xA3;

    public static final int UDP_RESERVED_2 = 0xB2;

    private Protocol() {}

    /** The protocols that may start a plain TCP stream. */
    public static boolean isTcp(int protocol) {
        return protocol == EDONKEY || protocol == EMULE || protocol == PACKED;
    }

    public static boolean isPacked(int protocol) {
        return protocol == PACKED || protocol == KAD_PACKED;
    }
}
