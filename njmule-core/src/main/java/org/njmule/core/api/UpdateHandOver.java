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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.njmule.core.update.UpdateHelper;

/**
 * The installation of a new version, which a stopped engine hands to its front end: njMule cannot replace its own files
 * while its engine runs, so the front end that owns the process starts the installer once everything is saved, then
 * ends the process.
 */
public interface UpdateHandOver {

    /** The version installed. */
    String version();

    /** Whether njMule starts again once the new version is in place: the user asked to restart, or the night came. */
    boolean restart();

    /**
     * Starts the installer in a process of its own, which waits for this one to end: the caller ends it next.
     *
     * @param arguments what njMule starts again with, as this run started (the program is the one installed)
     */
    void start(List<String> arguments) throws IOException;

    /**
     * Installs from this process, for the command line or a service that its supervisor restarts: the files of an
     * archive or a bundle are swapped now, while this program runs, and the new program asked its version; an installer
     * package goes to its installer, which runs once this process has ended.
     *
     * @return whether the new version is in place now
     * @throws IOException when it is not, and nothing changed
     */
    boolean installHere() throws IOException;

    /**
     * Runs the installation the plan {@code plan} describes, in the process that {@link #start} started, once the
     * processes {@code waitFor} have ended; returns the exit code of the process.
     */
    static int install(Path plan, List<Long> waitFor) {
        return UpdateHelper.install(plan, waitFor);
    }
}
