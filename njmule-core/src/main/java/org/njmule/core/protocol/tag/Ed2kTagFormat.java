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

/** The two eD2K tag encodings; readers accept both. */
public enum Ed2kTagFormat {
    /**
     * {@code <type><u16 name length><name><value>} with u32/u64 integers: .met files and the Hello
     * packet.
     */
    OLD,
    /**
     * {@code <type|0x80><id><value>} with the smallest integer type and STR1-16 strings: search
     * results and peers or servers that support new tags.
     */
    NEW
}
