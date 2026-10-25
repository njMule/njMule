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
package org.njmule.core.net;

import java.util.concurrent.atomic.LongAdder;

/**
 * The bytes of the eD2K and Kad packets this process sent and received, over TCP and UDP, with those of the file data
 * it sent apart: what the statistics tell as overhead, besides the files themselves.
 */
public final class Traffic {

    private static final LongAdder SENT_BYTES = new LongAdder();
    private static final LongAdder FILE_DATA_BYTES = new LongAdder();
    private static final LongAdder RECEIVED_BYTES = new LongAdder();

    private Traffic() {}

    /** Counts a packet sent, of file data or not. */
    public static void sent(int bytes, boolean fileData) {
        SENT_BYTES.add(bytes);
        if (fileData) {
            FILE_DATA_BYTES.add(bytes);
        }
    }

    /** Counts a packet received. */
    public static void received(int bytes) {
        RECEIVED_BYTES.add(bytes);
    }

    /** The bytes sent besides file data. */
    public static long overheadSent() {
        return SENT_BYTES.sum() - FILE_DATA_BYTES.sum();
    }

    /** All the bytes received, file data included. */
    public static long received() {
        return RECEIVED_BYTES.sum();
    }
}
