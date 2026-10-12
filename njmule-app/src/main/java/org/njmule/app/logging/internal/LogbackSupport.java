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
package org.njmule.app.logging.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.LoggerFactory;

/** Logback specifics, kept out of the exported API so that clients do not depend on Logback. */
public final class LogbackSupport {

    // %replace neutralises CR/LF in messages, so that data from peers cannot forge log lines.
    private static final String SAFE_MSG = "%replace(%msg){'[\\r\\n]+', ' '}";
    private static final String CONSOLE_PATTERN =
            "%d{HH:mm:ss.SSS} %-5level %logger{20} - " + SAFE_MSG + "%n";
    private static final String FILE_PATTERN =
            "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} - " + SAFE_MSG + "%n";
    private static final String FILE_APPENDER = "file";
    private static final Object FILE_LOCK = new Object();

    private LogbackSupport() {}

    static void configureDefaults(LoggerContext context) {
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
        root.addAppender(consoleAppender(context));
    }

    /** Adds a size- and time-based rolling log file (10 MB per file, 14 days, 200 MB total). */
    public static void enableFileLog(Path logsDir, String fileName) {
        synchronized (FILE_LOCK) {
            disableFileLog();
            LoggerContext context = context();
            RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
            appender.setContext(context);
            appender.setName(FILE_APPENDER);
            appender.setFile(logsDir.resolve(fileName).toString());

            SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy =
                    new SizeAndTimeBasedRollingPolicy<>();
            policy.setContext(context);
            policy.setParent(appender);
            policy.setFileNamePattern(
                    logsDir.resolve("njmule.%d{yyyy-MM-dd}.%i.log.gz").toString());
            policy.setMaxFileSize(FileSize.valueOf("10MB"));
            policy.setMaxHistory(14);
            policy.setTotalSizeCap(FileSize.valueOf("200MB"));
            policy.start();

            appender.setRollingPolicy(policy);
            appender.setEncoder(encoder(context, FILE_PATTERN));
            appender.start();
            root().addAppender(appender);
        }
    }

    /** Detaches and closes the log file, if any. */
    public static void disableFileLog() {
        synchronized (FILE_LOCK) {
            Appender<ILoggingEvent> appender = root().getAppender(FILE_APPENDER);
            if (appender != null) {
                root().detachAppender(appender);
                appender.stop();
            }
        }
    }

    public static void setLevel(String level) {
        root().setLevel(Level.toLevel(level.toUpperCase(Locale.ROOT), Level.INFO));
    }

    private static ConsoleAppender<ILoggingEvent> consoleAppender(LoggerContext context) {
        ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
        console.setContext(context);
        console.setName("console");
        console.setEncoder(encoder(context, CONSOLE_PATTERN));
        console.start();
        return console;
    }

    private static PatternLayoutEncoder encoder(LoggerContext context, String pattern) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(pattern);
        encoder.start();
        return encoder;
    }

    private static Logger root() {
        return context().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }
}
