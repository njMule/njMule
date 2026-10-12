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

/**
 * Largest payload accepted for a TCP frame, decided from its header before any allocation; negative
 * means the opcode is not acceptable at all in the current connection state.
 */
@FunctionalInterface
public interface FrameLimit {

    int REJECT = -1;

    int maxPayload(int protocol, int opcode);

    /** The same cap for every frame. */
    static FrameLimit fixed(int maxPayload) {
        return (protocol, opcode) -> maxPayload;
    }
}
