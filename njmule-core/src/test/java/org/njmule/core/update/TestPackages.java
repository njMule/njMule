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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

/** Packages as the release builds them, for tests: a zip or a tar.xz of named files. */
final class TestPackages {

    /** A file of a package: its text and Unix permissions, or a link to {@code link}. */
    record Entry(String text, int mode, String link) {
        static Entry file(String text) {
            return new Entry(text, 0644, null);
        }

        static Entry program(String text) {
            return new Entry(text, 0755, null);
        }

        static Entry linkTo(String target) {
            return new Entry("", 0777, target);
        }
    }

    private TestPackages() {}

    /** Writes the zip {@code file} with {@code entries} by their names, in their order. */
    static Path zip(Path file, Map<String, Entry> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().text().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    /** Writes the tar.xz {@code file} with {@code entries} by their names, in their order. */
    static Path tarXz(Path file, Map<String, Entry> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
                XZOutputStream xz = new XZOutputStream(out, new LZMA2Options());
                TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                Entry value = entry.getValue();
                TarArchiveEntry header;
                byte[] data = value.text().getBytes(StandardCharsets.UTF_8);
                if (value.link() != null) {
                    header = new TarArchiveEntry(entry.getKey(), TarConstants.LF_SYMLINK);
                    header.setLinkName(value.link());
                    data = new byte[0];
                } else {
                    header = new TarArchiveEntry(entry.getKey());
                    header.setSize(data.length);
                }
                header.setMode(value.mode());
                tar.putArchiveEntry(header);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        }
        return file;
    }
}
