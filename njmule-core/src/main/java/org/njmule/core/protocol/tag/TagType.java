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

/** Wire values of the tag types. */
public final class TagType {

    public static final int HASH = 0x01;
    public static final int STRING = 0x02;
    public static final int UINT32 = 0x03;
    public static final int FLOAT32 = 0x04;
    public static final int BOOL = 0x05;
    public static final int BOOLARRAY = 0x06;
    public static final int BLOB = 0x07;
    public static final int UINT16 = 0x08;
    public static final int UINT8 = 0x09;
    public static final int BSOB = 0x0A;
    public static final int UINT64 = 0x0B;

    /** STR1…STR16: a 1-16 byte string without length prefix (new eD2K encoding only). */
    public static final int STR1 = 0x11;

    public static final int STR16 = 0x20;

    /** Flag on the type byte of the new eD2K encoding: a one-byte id follows. */
    static final int NEW_ED2K_FLAG = 0x80;

    private TagType() {}
}
