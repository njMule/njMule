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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A process a test ends when it wants, with the exit code it wants. */
final class FakeProcess extends Process {

    private final CountDownLatch ended = new CountDownLatch(1);
    private volatile int code;

    /** A process that has already ended with {@code code}. */
    static FakeProcess ended(int code) {
        FakeProcess process = new FakeProcess();
        process.end(code);
        return process;
    }

    void end(int exitCode) {
        this.code = exitCode;
        this.ended.countDown();
    }

    @Override
    public OutputStream getOutputStream() {
        return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
        this.ended.await();
        return this.code;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return this.ended.await(timeout, unit);
    }

    @Override
    public int exitValue() {
        if (isAlive()) {
            throw new IllegalThreadStateException("still running");
        }
        return this.code;
    }

    @Override
    public boolean isAlive() {
        return this.ended.getCount() > 0;
    }

    @Override
    public void destroy() {
        end(1);
    }

    @Override
    public long pid() {
        return 4242;
    }
}
