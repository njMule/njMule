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

/**
 * What to take from an eMule profile.
 *
 * @param dryRun only report what would be imported
 * @param identity also take eMule's user hash, key, credits and Kad id, used from the next start;
 *     never while eMule runs, since two clients with one identity spoil each other's credits
 * @param downloads also copy eMule's downloads in progress, which must be paused or eMule closed
 */
public record ImportOptions(boolean dryRun, boolean identity, boolean downloads) {}
