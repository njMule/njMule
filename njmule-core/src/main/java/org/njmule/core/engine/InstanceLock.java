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
package org.njmule.core.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Keeps a second engine off a configuration directory in use: two would write the same downloads and lists. The
 * operating system releases the lock when the process ends, even after a crash.
 */
final class InstanceLock implements AutoCloseable {

    static final String FILE_NAME = "njmule.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Takes the lock of {@code configDir}; fails when another engine holds it. */
    static InstanceLock acquire(Path configDir) throws IOException {
        FileChannel channel =
                FileChannel.open(configDir.resolve(FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new IOException("njMule is already running with the configuration in " + configDir);
        }
        return new InstanceLock(channel, lock);
    }

    @Override
    public void close() {
        try (this.channel) {
            this.lock.release();
        } catch (IOException e) {
            // The lock goes with the channel or the process anyway.
        }
    }
}
