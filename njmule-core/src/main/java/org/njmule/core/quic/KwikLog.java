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
package org.njmule.core.quic;

import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kwik.core.log.BaseLogger;

/**
 * Kwik's messages in our log, at debug level: its errors are mostly about what other clients send. Warnings come with
 * debug, information with trace.
 */
final class KwikLog extends BaseLogger {

    private static final Logger logger = LoggerFactory.getLogger(KwikLog.class);

    KwikLog() {
        logWarning(logger.isDebugEnabled());
        logInfo(logger.isTraceEnabled());
    }

    @Override
    protected void log(String message) {
        logger.debug("{}", message);
    }

    @Override
    protected void log(String message, Throwable ex) {
        logger.debug("{}", message, ex);
    }

    @Override
    protected void logWithHexDump(String message, byte[] data, int length) {
        logger.trace("{} ({} bytes)", message, length);
    }

    @Override
    protected void logWithHexDump(String message, ByteBuffer data, int offset, int length) {
        logger.trace("{} ({} bytes)", message, length);
    }
}
