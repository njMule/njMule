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
import java.util.EnumSet;
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

    /**
     * Keeps a file from being deleted: on Windows by denying its deletion and that of its folder's entries, elsewhere
     * by making its folder read-only. A probe beside it, held the same way, tells whether the hold works for this user.
     */
    static HeldFile holdFromDeletion(Path file) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Path probe = Files.write(folder.resolve(file.getFileName() + ".probe"), new byte[] {1});
        Release unhold = holdingFromDeletion(folder, List.of(file, probe));
        HeldFile holding = new HeldFile(() -> {
            unhold.run();
            Files.deleteIfExists(probe);
        });
        boolean held;
        try {
            Files.delete(probe);
            held = false;
        } catch (IOException e) {
            held = true;
        }
        if (!held) {
            holding.close();
            assumeFalse(true, "this user deletes any file");
        }
        return holding;
    }

    private static Release holdingFromDeletion(Path folder, List<Path> files) throws IOException {
        AclFileAttributeView folderAcl = Files.getFileAttributeView(folder, AclFileAttributeView.class);
        if (folderAcl != null) {
            List<Release> releases = new ArrayList<>();
            for (Path file : files) {
                AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
                List<AclEntry> before = acl.getAcl();
                acl.setAcl(denying(acl, AclEntryPermission.DELETE, before));
                releases.add(() -> acl.setAcl(before));
            }
            List<AclEntry> folderBefore = folderAcl.getAcl();
            folderAcl.setAcl(denying(folderAcl, AclEntryPermission.DELETE_CHILD, folderBefore));
            releases.addFirst(() -> folderAcl.setAcl(folderBefore));
            return () -> {
                for (Release release : releases) {
                    release.run();
                }
            };
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(folder, PosixFileAttributeView.class);
        assumeTrue(posix != null, "no way to hold a file here");
        Set<PosixFilePermission> before = posix.readAttributes().permissions();
        Set<PosixFilePermission> readOnly = EnumSet.copyOf(before);
        readOnly.removeAll(Set.of(
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE));
        posix.setPermissions(readOnly);
        return () -> posix.setPermissions(before);
    }

    private static List<AclEntry> denying(
            AclFileAttributeView acl, AclEntryPermission permission, List<AclEntry> before) throws IOException {
        List<AclEntry> denied = new ArrayList<>();
        denied.add(AclEntry.newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(acl.getOwner())
                .setPermissions(permission)
                .build());
        denied.addAll(before);
        return denied;
    }

    /** Lets the file be read again, from any thread; closing does the same. */
    void release() throws IOException {
        if (this.held.compareAndSet(true, false)) {
            this.release.run();
        }
    }

    @Override
    public void close() throws IOException {
        release();
    }
}
