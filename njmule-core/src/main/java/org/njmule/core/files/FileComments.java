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
package org.njmule.core.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.IniFile;

/**
 * Our own comments and ratings of files, in {@code fileinfo.ini}: a section per file hash with {@code Comment} and
 * {@code Rate}, as eMule writes it, so that an imported profile keeps them.
 */
public final class FileComments {

    public static final String FILE_NAME = "fileinfo.ini";

    private final Path file;
    private final Map<Hash16, FileComment> comments = new TreeMap<>();

    private FileComments(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** No comments, kept in {@code file} once there are. */
    public static FileComments empty(Path file) {
        return new FileComments(file);
    }

    /** Reads {@code file}, or its backup when it is missing or unreadable; none holds no comments. */
    public static FileComments load(Path file) throws IOException {
        FileComments store = new FileComments(file);
        AtomicFiles.cleanupInterruptedWrite(file);
        IniFile ini;
        try {
            ini = AtomicFiles.read(file, in -> IniFile.parse(in.readAllBytes()));
        } catch (NoSuchFileException e) {
            return store;
        }
        for (String section : ini.sections()) {
            Hash16 hash;
            try {
                hash = Hash16.parseHex(section.strip());
            } catch (IllegalArgumentException e) {
                continue;
            }
            String text = ini.get(section, "Comment").orElse("");
            int rating = (int) Math.clamp(ini.getLong(section, "Rate").orElse(0), 0, FileComment.MAX_RATING);
            FileComment comment = FileComment.of(text, rating);
            if (!comment.isEmpty()) {
                store.comments.put(hash, comment);
            }
        }
        return store;
    }

    public Optional<FileComment> get(Hash16 file) {
        return Optional.ofNullable(this.comments.get(file));
    }

    /** Keeps {@code comment} for {@code file}; an empty one takes the comment away. */
    public void set(Hash16 file, FileComment comment) throws IOException {
        if (comment.isEmpty()) {
            if (this.comments.remove(file) == null) {
                return;
            }
        } else if (comment.equals(this.comments.put(file, comment))) {
            return;
        }
        this.save();
    }

    private void save() throws IOException {
        StringBuilder text = new StringBuilder();
        this.comments.forEach((hash, c) -> text.append('[')
                .append(hash.toHex())
                .append("]\r\nComment=")
                .append(c.text())
                .append("\r\nRate=")
                .append(c.rating())
                .append("\r\n"));
        AtomicFiles.writeBytes(this.file, text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
