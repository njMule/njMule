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
package org.njmule.core.search;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.SearchFiles;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;

/** The search tabs left open at exit, in {@code StoredSearches.met}, to show again at the next start. */
public final class StoredSearches {

    public static final String FILE_NAME = "StoredSearches.met";

    // The search methods as the file numbers them; 0 is automatic, which ran as one of the others.
    private static final int SERVER = 1;
    private static final int GLOBAL = 2;
    private static final int KAD = 3;

    private StoredSearches() {}

    /**
     * The searches in {@code file}, or in its backup when it is damaged; none when there is no file. Tabs that browsed
     * a client's shared files are left out: they cannot be asked again.
     */
    public static List<SavedSearch> load(Path file) throws IOException {
        AtomicFiles.cleanupInterruptedWrite(file);
        List<SearchFiles.StoredSearch> stored;
        try {
            stored = AtomicFiles.read(file, in -> {
                try {
                    return SearchFiles.readStored(in.readAllBytes());
                } catch (DecodeException e) {
                    throw new IOException(file + ": " + e.getMessage(), e);
                }
            });
        } catch (NoSuchFileException e) {
            return List.of();
        }
        List<SavedSearch> searches = new ArrayList<>();
        for (SearchFiles.StoredSearch search : stored) {
            if (!search.clientSharedFiles()) {
                searches.add(saved(search));
            }
        }
        return List.copyOf(searches);
    }

    public static void save(Path file, List<SavedSearch> searches) throws IOException {
        List<SearchFiles.StoredSearch> stored = new ArrayList<>();
        long id = 0;
        for (SavedSearch search : searches) {
            stored.add(stored(id++, search));
        }
        AtomicFiles.writeBytes(file, SearchFiles.writeStored(stored));
    }

    private static SavedSearch saved(SearchFiles.StoredSearch stored) {
        SearchRequest.Scope scope = switch (stored.type()) {
            case GLOBAL -> SearchRequest.Scope.GLOBAL;
            case KAD -> SearchRequest.Scope.KAD;
            default -> SearchRequest.Scope.SERVER;
        };
        List<SearchResult> results = new ArrayList<>();
        for (SearchFiles.Result r : stored.results()) {
            FileEntry entry = new FileEntry(r.fileHash(), r.clientId(), r.port(), r.tags());
            if (entry.name().isPresent()
                    && entry.size().isPresent()
                    && SearchResult.isDownloadable(
                            entry.name().get(), entry.size().getAsLong())) {
                results.add(new SearchResult(
                        entry.hash(),
                        entry.name().get(),
                        entry.size().getAsLong(),
                        entry.sources(),
                        entry.completeSources(),
                        entry.type()));
            }
        }
        return new SavedSearch(
                stored.title(),
                new SearchRequest(withFileType(stored.expression(), stored.fileType()), scope),
                results);
    }

    // Other clients keep the file type apart from the expression; it becomes a type condition when it is one we know.
    private static String withFileType(String expression, String fileType) {
        if (fileType.isBlank()) {
            return expression;
        }
        String typed = expression + " @type=" + fileType.strip();
        try {
            SearchParser.parse(typed);
            return typed;
        } catch (SearchSyntaxException e) {
            return expression;
        }
    }

    private static SearchFiles.StoredSearch stored(long id, SavedSearch search) {
        int type = switch (search.request().scope()) {
            case SERVER -> SERVER;
            case GLOBAL -> GLOBAL;
            case KAD -> KAD;
        };
        List<SearchFiles.Result> results = new ArrayList<>();
        for (SearchResult r : search.results()) {
            List<Tag> tags = new ArrayList<>();
            tags.add(Tag.of(FileTag.FILENAME, r.name()));
            tags.add(Tag.of(FileTag.FILESIZE, r.size()));
            tags.add(Tag.of(FileTag.SOURCES, r.sources()));
            tags.add(Tag.of(FileTag.COMPLETE_SOURCES, r.completeSources()));
            r.type().ifPresent(t -> tags.add(Tag.of(FileTag.FILETYPE, t)));
            results.add(new SearchFiles.Result(r.hash(), 0, 0, tags));
        }
        // The file type stays empty (any): the expression holds the one asked for.
        return new SearchFiles.StoredSearch(
                id, type, false, search.title(), search.request().expression(), "", results);
    }
}
