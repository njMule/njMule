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
package org.njmule.core.download;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.persist.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The downloads that completed, which stay in the list of downloads, restarts included, until the user clears them.
 * Engine thread only; kept in {@value #FILE_NAME}.
 */
public final class FinishedDownloads {

    public static final String FILE_NAME = "finished.json";

    private static final Logger logger = LoggerFactory.getLogger(FinishedDownloads.class);
    private static final int FORMAT = 1;

    private final Path file;
    // In the order they completed.
    private final LinkedHashMap<Hash16, DownloadInfo> finished = new LinkedHashMap<>();

    private FinishedDownloads(Path file) {
        this.file = file;
    }

    /** The completed downloads kept in {@code configDir}; none when they cannot be read. */
    public static FinishedDownloads load(Path configDir) {
        FinishedDownloads kept = new FinishedDownloads(configDir.resolve(FILE_NAME));
        try {
            JsonNode root =
                    AtomicFiles.read(kept.file, in -> JsonMapper.shared().readTree(in));
            if (root.path("format").asInt() != FORMAT) {
                return kept;
            }
            for (JsonNode n : root.path("downloads")) {
                DownloadInfo d = complete(
                        Hash16.parseHex(n.path("hash").asString()),
                        n.path("name").asString(),
                        n.path("size").asLong(),
                        DownloadPriority.valueOf(n.path("priority").asString()),
                        n.path("category").asInt());
                kept.finished.put(d.hash(), d);
            }
        } catch (NoSuchFileException e) {
            // Nothing completed yet.
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            logger.warn("Starting a new list of completed downloads: cannot read {}: {}", kept.file, e.toString());
            kept.finished.clear();
        }
        return kept;
    }

    /** Keeps {@code download}, which has just completed. */
    public void add(DownloadInfo download) {
        this.finished.remove(download.hash());
        this.finished.put(
                download.hash(),
                complete(download.hash(), download.name(), download.size(), download.priority(), download.category()));
        save();
    }

    /** Forgets the completed download of {@code hash}, as one downloaded again; {@code false} when there is none. */
    public boolean remove(Hash16 hash) {
        if (this.finished.remove(hash) == null) {
            return false;
        }
        save();
        return true;
    }

    /** Forgets every completed download, and tells which they were. */
    public List<Hash16> clear() {
        List<Hash16> cleared = List.copyOf(this.finished.keySet());
        if (!cleared.isEmpty()) {
            this.finished.clear();
            save();
        }
        return cleared;
    }

    /** The completed downloads, in the order they completed. */
    public List<DownloadInfo> downloads() {
        return List.copyOf(this.finished.values());
    }

    // Sources and rates end with the download; every byte is there.
    private static DownloadInfo complete(Hash16 hash, String name, long size, DownloadPriority priority, int category) {
        return new DownloadInfo(
                hash,
                name,
                size,
                size,
                DownloadInfo.Status.COMPLETE,
                priority,
                category,
                0,
                0,
                0,
                new PartMap(size, List.of(), List.of(), List.of()));
    }

    private void save() {
        ObjectNode root = JsonMapper.shared().createObjectNode();
        root.put("format", FORMAT);
        ArrayNode list = root.putArray("downloads");
        for (DownloadInfo d : this.finished.values()) {
            ObjectNode n = list.addObject();
            n.put("hash", d.hash().toHex());
            n.put("name", d.name());
            n.put("size", d.size());
            n.put("priority", d.priority().name());
            n.put("category", d.category());
        }
        try {
            AtomicFiles.writeBytes(this.file, JsonMapper.shared().writeValueAsBytes(root));
        } catch (IOException | JacksonException e) {
            logger.warn("Cannot save the completed downloads to {}: {}", this.file, e.toString());
        }
    }
}
