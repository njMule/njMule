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
package org.njmule.core.protocol.tag;

/** Hello, login and eMule-info tag ids ({@code Opcodes.h} {@code CT_*}, {@code ET_*}). */
public final class ClientTag {

    public static final int NAME = 0x01;
    public static final int PORT = 0x0F;
    public static final int VERSION = 0x11;
    public static final int SERVER_FLAGS = 0x20;
    public static final int MOD_VERSION = 0x55;
    public static final int EMULECOMPAT_OPTIONS1 = 0xEF;
    public static final int EMULE_UDPPORTS = 0xF9;
    public static final int EMULE_MISCOPTIONS1 = 0xFA;
    public static final int EMULE_VERSION = 0xFB;
    public static final int EMULE_BUDDYIP = 0xFC;
    public static final int EMULE_BUDDYUDP = 0xFD;
    public static final int EMULE_MISCOPTIONS2 = 0xFE;
    public static final int SERVER_UDPSEARCH_FLAGS = 0x0E;

    public static final int ET_COMPRESSION = 0x20;
    public static final int ET_UDPPORT = 0x21;
    public static final int ET_UDPVER = 0x22;
    public static final int ET_SOURCEEXCHANGE = 0x23;
    public static final int ET_COMMENTS = 0x24;
    public static final int ET_EXTENDEDREQUEST = 0x25;
    public static final int ET_COMPATIBLECLIENT = 0x26;
    public static final int ET_FEATURES = 0x27;

    private ClientTag() {}
}
