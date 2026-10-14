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
package org.njmule.core.persist;

import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps a file from being opened for reading, as a program holding it does, until closed. */
final class HeldFile implements AutoCloseable {

    @FunctionalInterface
    private interface Release {
        void run() throws IOException;
    }

    private final Release release;
    private final AtomicBoolean held = new AtomicBoolean(true);

    private HeldFile(Release release) {
        this.release = release;
    }

    static HeldFile hold(Path file) throws IOException {
        HeldFile holding;
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (acl != null) {
            List<AclEntry> before = acl.getAcl();
            List<AclEntry> denied = new ArrayList<>();
            denied.add(AclEntry.newBuilder()
                    .setType(AclEntryType.DENY)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(AclEntryPermission.READ_DATA)
                    .build());
            denied.addAll(before);
            acl.setAcl(denied);
            holding = new HeldFile(() -> acl.setAcl(before));
        } else {
            assumeTrue(posix != null, "no way to hold a file here");
            Set<PosixFilePermission> before = posix.readAttributes().permissions();
            posix.setPermissions(Set.of());
            holding = new HeldFile(() -> posix.setPermissions(before));
        }
        if (Files.isReadable(file)) {
            holding.close();
            assumeFalse(true, "this user reads any file");
        }
        return holding;
    }

    /** Lets the file be read again, from any thread; closing does the same. */
    void release() throws IOException {
        if (this.held.compareAndSet(true, false)) {
            this.release.run();
        }
    }

    @Override
    public void close() throws IOException {
        this.release();
    }
}
