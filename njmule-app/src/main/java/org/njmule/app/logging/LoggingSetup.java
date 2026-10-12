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
package org.njmule.app.logging;

import java.nio.file.Path;
import org.njmule.app.logging.internal.LogbackSupport;

/** Runtime adjustments of logging, shared by the front ends. */
public final class LoggingSetup {

    public static final String LOG_FILE_NAME = "njmule.log";

    private LoggingSetup() {}

    /** Adds a rolling log file in {@code logsDir} (10 MB per file, 14 days, 200 MB total). */
    public static void enableFileLog(Path logsDir) {
        LogbackSupport.enableFileLog(logsDir, LOG_FILE_NAME);
    }

    /** Flushes and closes the log file. */
    public static void disableFileLog() {
        LogbackSupport.disableFileLog();
    }

    /** Sets the root level; unknown names fall back to INFO. */
    public static void setLevel(String level) {
        LogbackSupport.setLevel(level);
    }
}
