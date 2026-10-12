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

/** server.met tag ids; "users" and "files" are string names. */
public final class ServerTag {

    public static final int SERVERNAME = 0x01;
    public static final int DESCRIPTION = 0x0B;
    public static final int PING = 0x0C;
    public static final int FAIL = 0x0D;
    public static final int PREFERENCE = 0x0E;
    public static final int PORT = 0x0F;
    public static final int IP = 0x10;
    public static final int DYNIP = 0x85;
    public static final int MAXUSERS = 0x87;
    public static final int SOFTFILES = 0x88;
    public static final int HARDFILES = 0x89;
    public static final int LASTPING = 0x90;
    public static final int VERSION = 0x91;
    public static final int UDPFLAGS = 0x92;
    public static final int AUXPORTSLIST = 0x93;
    public static final int LOWIDUSERS = 0x94;
    public static final int UDPKEY = 0x95;
    public static final int UDPKEYIP = 0x96;
    public static final int TCPPORTOBFUSCATION = 0x97;
    public static final int UDPPORTOBFUSCATION = 0x98;

    public static final String USERS = "users";
    public static final String FILES = "files";

    private ServerTag() {}
}
