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
package org.njmule.core.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a program to its end: what njMule cannot do itself, such as mounting a disk image, or asks a new version. */
@FunctionalInterface
interface Commands {

    /** The programs of this computer, their output kept up to {@value #MAX_OUTPUT} bytes. */
    Commands SYSTEM = Commands::system;

    int MAX_OUTPUT = 64 * 1024;

    /**
     * What a program said and how it ended.
     *
     * @param exitCode its exit code
     * @param output its standard output and error, together
     */
    record Result(int exitCode, String output) {
        boolean succeeded() {
            return this.exitCode == 0;
        }
    }

    /** Runs {@code command}, the program first; fails when it does not end within {@code timeout}. */
    Result run(List<String> command, Duration timeout) throws IOException;

    /** As {@link #run}, failing also when the program ends with an error. */
    default Result require(List<String> command, Duration timeout) throws IOException {
        Result result = run(command, timeout);
        if (!result.succeeded()) {
            throw new IOException(command.getFirst() + " ended with code " + result.exitCode() + ": "
                    + result.output().strip());
        }
        return result;
    }

    private static Result system(List<String> command, Duration timeout) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // Read beside: a program blocked on a full pipe would never end, one that keeps it open never let a read end.
        Thread reader = Thread.ofVirtual().name("njmule-command-output").start(() -> keep(process, output));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.getFirst() + " did not end within " + timeout.toSeconds() + " s");
            }
            reader.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while " + command.getFirst() + " ran");
        }
        synchronized (output) {
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        }
    }

    private static void keep(Process process, ByteArrayOutputStream output) {
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                synchronized (output) {
                    output.write(buffer, 0, Math.min(n, Math.max(0, MAX_OUTPUT - output.size())));
                }
            }
        } catch (IOException e) {
            // The program ended or was stopped: what it said so far is kept.
        }
    }
}
