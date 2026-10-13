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

/** How early a download asks for sources and starts transfers, before the others; AUTO follows its rarity. */
public enum DownloadPriority {
    LOW,
    NORMAL,
    HIGH,
    AUTO;

    private static final int FEW_SOURCES = 20;
    private static final int MANY_SOURCES = 100;

    /** The priority it acts with, for a file with {@code sources} known sources: AUTO puts rare files first. */
    public DownloadPriority effective(int sources) {
        if (this != AUTO) {
            return this;
        }
        if (sources > MANY_SOURCES) {
            return LOW;
        }
        return sources > FEW_SOURCES ? NORMAL : HIGH;
    }
}
