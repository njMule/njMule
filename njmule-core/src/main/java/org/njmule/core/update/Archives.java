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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

/**
 * Unpacks the zip and tar.xz packages of a new version, with what they hold below their one top folder (the package's
 * name) put straight into the target. Names that would leave the target (absolute, {@code ..}, a drive), links,
 * devices, more than {@value #MAX_ENTRIES} entries or {@value #MAX_BYTES_TEXT} in all are refused: the package was
 * checked against its signed hash, this guards against a mistake in it. The Unix permissions of a tar entry are kept.
 */
final class Archives {

    static final int MAX_ENTRIES = 20_000;
    static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;

    private static final String MAX_BYTES_TEXT = "4 GiB";
    private static final int BUFFER = 64 * 1024;
    private static final String LIFTED = ".njmule-unpacked";

    private Archives() {}

    /** The kind of package {@code name} is, by its ending. */
    enum Format {
        ZIP,
        TAR_XZ;

        static Format of(String name) throws IOException {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip")) {
                return ZIP;
            }
            if (lower.endsWith(".tar.xz")) {
                return TAR_XZ;
            }
            throw new IOException("not a zip or tar.xz package: " + name);
        }
    }

    /** Unpacks {@code archive} into {@code target}, which must be empty or missing. */
    static void unpack(Path archive, Path target) throws IOException {
        Files.createDirectories(target);
        try (var entries = Files.list(target)) {
            if (entries.findAny().isPresent()) {
                throw new IOException("not an empty folder: " + target);
            }
        }
        List<Unpacked> files = switch (Format.of(String.valueOf(archive.getFileName()))) {
            case ZIP -> unzip(archive, target);
            case TAR_XZ -> untar(archive, target);
        };
        lift(files, target);
    }

    private record Unpacked(String name, boolean directory) {}

    private static List<Unpacked> unzip(Path archive, Path target) throws IOException {
        List<Unpacked> unpacked = new ArrayList<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                count(unpacked);
                String name = checked(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target.resolve(name));
                    unpacked.add(new Unpacked(name, true));
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    total += write(in, target.resolve(name), total);
                }
                unpacked.add(new Unpacked(name, false));
            }
        }
        return unpacked;
    }

    private static List<Unpacked> untar(Path archive, Path target) throws IOException {
        List<Unpacked> unpacked = new ArrayList<>();
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        long total = 0;
        try (InputStream file = new BufferedInputStream(Files.newInputStream(archive), BUFFER);
                InputStream xz = new XZInputStream(file);
                TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                count(unpacked);
                String name = checked(entry.getName());
                if (entry.isSymbolicLink() || entry.isLink() || !(entry.isDirectory() || entry.isFile())) {
                    throw new IOException("the package holds a link or a special file: " + name);
                }
                Path to = target.resolve(name);
                if (entry.isDirectory()) {
                    Files.createDirectories(to);
                    unpacked.add(new Unpacked(name, true));
                    continue;
                }
                total += write(tar, to, total);
                if (posix) {
                    Files.setPosixFilePermissions(to, permissions(entry.getMode()));
                }
                unpacked.add(new Unpacked(name, false));
            }
        }
        return unpacked;
    }

    private static void count(List<Unpacked> unpacked) throws IOException {
        if (unpacked.size() >= MAX_ENTRIES) {
            throw new IOException("the package holds more than " + MAX_ENTRIES + " files");
        }
    }

    // A relative name with forward slashes and no step out of the target.
    static String checked(String raw) throws IOException {
        String name = raw.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty() || name.startsWith("/") || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("a name the package may not hold: " + raw);
        }
        for (String step : name.split("/", -1)) {
            if (step.isEmpty() || step.equals(".") || step.equals("..")) {
                throw new IOException("a name the package may not hold: " + raw);
            }
        }
        return name;
    }

    private static long write(InputStream in, Path to, long before) throws IOException {
        Path parent = to.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long written = 0;
        try (OutputStream out = Files.newOutputStream(to)) {
            byte[] buffer = new byte[BUFFER];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                written += n;
                if (before + written > MAX_BYTES) {
                    throw new IOException("the package unpacks to more than " + MAX_BYTES_TEXT);
                }
                out.write(buffer, 0, n);
            }
        }
        return written;
    }

    private static Set<PosixFilePermission> permissions(int mode) {
        Set<PosixFilePermission> set = EnumSet.noneOf(PosixFilePermission.class);
        PosixFilePermission[] order = {
            PosixFilePermission.OTHERS_EXECUTE,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_READ
        };
        for (int bit = 0; bit < order.length; bit++) {
            if ((mode & (1 << bit)) != 0) {
                set.add(order[bit]);
            }
        }
        // The owner must at least read and replace what it unpacked.
        set.add(PosixFilePermission.OWNER_READ);
        set.add(PosixFilePermission.OWNER_WRITE);
        return set;
    }

    // The package's own folder (njmule-0.2.0-linux-x64/) goes: its content is what replaces the installation's.
    private static void lift(List<Unpacked> files, Path target) throws IOException {
        if (files.isEmpty()) {
            throw new IOException("an empty package");
        }
        String top = files.getFirst().name().split("/", -1)[0];
        boolean oneFolder = files.stream()
                .allMatch(f -> f.name().equals(top) ? f.directory() : f.name().startsWith(top + "/"));
        if (!oneFolder) {
            return;
        }
        if (!Files.isDirectory(target.resolve(top))) {
            return;
        }
        // Moved aside first: it may hold a file of its own name.
        Path folder = Files.move(target.resolve(top), target.resolve(LIFTED));
        try (var children = Files.list(folder)) {
            for (Path child : children.toList()) {
                Files.move(child, target.resolve(String.valueOf(child.getFileName())));
            }
        }
        Files.delete(folder);
    }
}
