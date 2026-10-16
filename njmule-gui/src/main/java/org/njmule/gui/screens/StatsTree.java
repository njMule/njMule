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
package org.njmule.gui.screens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.model.LiveList;
import org.njmule.gui.ui.Gui;

/**
 * The statistics tree: transfers, uploads, downloads, connection, time, clients, servers, shared files and disk space,
 * filled again each time from the model. Its branches stay open or closed while the values change.
 */
final class StatsTree {

    /** A line of the tree and the lines below it. */
    record Entry(String text, List<Entry> children) {
        Entry {
            Objects.requireNonNull(text, "text");
            children = List.copyOf(children);
        }

        static Entry of(String text, Entry... children) {
            return new Entry(text, List.of(children));
        }
    }

    // Free space is read from the disk at most this often.
    private static final long DISK_EVERY_NANOS = 10_000_000_000L;

    private final Gui gui;
    private final TreeView<String> view = new TreeView<>(new TreeItem<>());
    private long diskReadAt;
    private List<Entry> disk = List.of();

    StatsTree(Gui gui) {
        this.gui = gui;
        this.view.setShowRoot(false);
        this.view.getStyleClass().add("stats-tree");
    }

    TreeView<String> view() {
        return this.view;
    }

    /** Shows the statistics now; the main sections are open the first time. */
    void refresh() {
        TreeItem<String> root = this.view.getRoot();
        boolean first = root.getChildren().isEmpty();
        sync(root, entries());
        if (first) {
            root.getChildren().forEach(item -> item.setExpanded(true));
        }
    }

    List<Entry> entries() {
        EngineModel m = this.gui.model();
        EngineStatus s = m.statusProperty().get();
        Formats f = this.gui.formats();
        long seconds = Math.max(1, s.sessionSeconds());
        List<Entry> all = new ArrayList<>();
        all.add(Entry.of(
                this.gui.t("IDS_EM_TRANS"),
                Entry.of(this.gui.t("IDS_STATS_SRATIO") + " " + ratio(s.uploaded(), s.downloaded())),
                Entry.of(this.gui.t("IDS_STATS_CRATIO") + " " + ratio(s.totalUploaded(), s.totalDownloaded()))));
        all.add(Entry.of(
                this.gui.t("IDS_TW_UPLOADS"),
                Entry.of(
                        this.gui.t("IDS_STATS_SESSION"),
                        Entry.of(this.gui.f("IDS_STATS_UDATA", f.size(s.uploaded()))),
                        Entry.of(this.gui.f("IDS_STATS_ACTUL", s.uploads())),
                        Entry.of(this.gui.f("IDS_STATS_WAITINGUSERS", s.queued()))),
                Entry.of(
                        this.gui.t("IDS_STATS_CUMULATIVE"),
                        Entry.of(this.gui.f("IDS_STATS_UDATA", f.size(s.totalUploaded()))))));
        all.add(Entry.of(
                this.gui.t("IDS_TW_DOWNLOADS"),
                Entry.of(
                        this.gui.t("IDS_STATS_SESSION"),
                        Entry.of(this.gui.f("IDS_STATS_DDATA", f.size(s.downloaded()))),
                        Entry.of(this.gui.f("IDS_STATS_ACTDL", s.downloads())),
                        Entry.of(this.gui.f(
                                "IDS_STATS_FOUNDSRC", m.sources().rows().size())),
                        Entry.of(this.gui.t("IDS_STATS_COMPDL") + ": "
                                + m.completedProperty().get())),
                Entry.of(
                        this.gui.t("IDS_STATS_CUMULATIVE"),
                        Entry.of(this.gui.f("IDS_STATS_DDATA", f.size(s.totalDownloaded()))))));
        all.add(Entry.of(
                this.gui.t("IDS_CONNECTION"),
                Entry.of(
                        this.gui.t("IDS_STATS_SESSION"),
                        Entry.of(this.gui.f("IDS_STATS_AVGDL", f.rate(s.downloaded() / seconds))),
                        Entry.of(this.gui.f("IDS_STATS_AVGUL", f.rate(s.uploaded() / seconds))),
                        Entry.of(this.gui.f("IDS_STATS_MAXDL", f.rate(m.maxDownloadRate()))),
                        Entry.of(this.gui.t("IDS_STATS_MAXUL") + ": " + f.rate(m.maxUploadRate())))));
        all.add(Entry.of(
                this.gui.t("IDS_STATS_TIMESTATS"),
                Entry.of(this.gui.t("IDS_STATS_SESSION") + ": " + f.duration(s.sessionSeconds())),
                Entry.of(this.gui.t("IDS_STATS_CUMULATIVE") + ": " + f.duration(s.totalSeconds()))));
        all.add(clients(m.sources(), m.uploads()));
        all.add(servers(m.servers()));
        all.add(shared(m.sharedFiles()));
        all.add(new Entry(this.gui.t("IDS_STATS_FREESPACE"), disk()));
        return all;
    }

    // The clients we download from or upload to, by software, each once.
    private Entry clients(LiveList<String, SourceInfo> sources, LiveList<String, UploadInfo> uploads) {
        Map<String, String> byAddress = new HashMap<>();
        sources.rows().forEach(r -> byAddress.put(r.get().address(), r.get().software()));
        uploads.rows().forEach(r -> byAddress.put(r.get().address(), r.get().software()));
        Map<String, Integer> bySoftware = new HashMap<>();
        byAddress.values().forEach(sw -> bySoftware.merge(sw.isBlank() ? "?" : sw, 1, Integer::sum));
        List<Entry> lines = new ArrayList<>();
        bySoftware.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> lines.add(Entry.of(e.getKey() + ": " + e.getValue() + " ("
                        + this.gui.formats().percent(e.getValue(), byAddress.size()) + ")")));
        return new Entry(this.gui.t("IDS_CLIENTS") + " (" + byAddress.size() + ")", lines);
    }

    private Entry servers(LiveList<String, ServerInfo> servers) {
        int working = 0;
        int failed = 0;
        long users = 0;
        long files = 0;
        long allUsers = 0;
        long allFiles = 0;
        for (LiveList.Row<ServerInfo> row : servers.rows()) {
            ServerInfo s = row.get();
            allUsers += s.users();
            allFiles += s.files();
            if (s.failed() > 0) {
                failed++;
            } else if (s.ping() > 0 || s.users() > 0) {
                working++;
                users += s.users();
                files += s.files();
            }
        }
        Formats f = this.gui.formats();
        return Entry.of(
                this.gui.t("IDS_EM_SERVER"),
                Entry.of(this.gui.t("IDS_SF_WORKING") + ": " + working),
                Entry.of(this.gui.t("IDS_SF_FAIL") + ": " + failed),
                Entry.of(this.gui.t("IDS_SF_TOTAL") + ": " + servers.rows().size()),
                Entry.of(this.gui.t("IDS_SF_WUSER") + ": " + f.count(users)),
                Entry.of(this.gui.t("IDS_SF_WFILE") + ": " + f.count(files)),
                Entry.of(this.gui.t("IDS_SF_USER") + ": " + f.count(allUsers)),
                Entry.of(this.gui.t("IDS_SF_FILE") + ": " + f.count(allFiles)));
    }

    private Entry shared(LiveList<Path, SharedFileInfo> shared) {
        long total = 0;
        SharedFileInfo largest = null;
        for (LiveList.Row<SharedFileInfo> row : shared.rows()) {
            total += row.get().size();
            if (largest == null || row.get().size() > largest.size()) {
                largest = row.get();
            }
        }
        int count = shared.rows().size();
        Formats f = this.gui.formats();
        List<Entry> lines = new ArrayList<>();
        lines.add(Entry.of(this.gui.f("IDS_STATS_TOTAL", count)));
        lines.add(Entry.of(this.gui.f("IDS_SF_SIZE", f.size(total))));
        lines.add(Entry.of(this.gui.f("IDS_SF_AVERAGESIZE", f.size(count == 0 ? 0 : total / count))));
        if (largest != null) {
            lines.add(Entry.of(
                    this.gui.t("IDS_STATS_LARGESTFILE") + ": " + largest.name() + " (" + f.size(largest.size()) + ")"));
        }
        return new Entry(this.gui.t("IDS_SF_FILES"), lines);
    }

    // The free space where downloads are written and where they finish.
    private List<Entry> disk() {
        long now = System.nanoTime();
        if (!this.disk.isEmpty() && now - this.diskReadAt < DISK_EVERY_NANOS) {
            return this.disk;
        }
        this.diskReadAt = now;
        Preferences p = this.gui.model().preferencesProperty().get();
        this.disk = List.of(
                free(this.gui.t("IDS_PW_TEMP"), p.effectiveTempDir(this.gui.directories())),
                free(this.gui.t("IDS_PW_INCOMING"), p.effectiveIncomingDir(this.gui.directories())));
        return this.disk;
    }

    private Entry free(String name, Path dir) {
        OptionalLong free = usable(dir);
        String size = free.isPresent() ? this.gui.formats().size(free.getAsLong()) : "-";
        return Entry.of(name + ": " + size + " (" + dir + ")");
    }

    // The folder may not exist yet: the first one up that does tells the disk.
    private static OptionalLong usable(Path dir) {
        Path existing = dir.toAbsolutePath();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Files.getFileStore(existing).getUsableSpace());
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    // Upload to download, as 1 : 2.5.
    private String ratio(long up, long down) {
        if (up == 0) {
            return down == 0 ? "-" : "0 : 1";
        }
        return "1 : " + this.gui.formats().ratio((double) down / up);
    }

    // Changes the items in place: the same items keep their open state and the selection.
    private static void sync(TreeItem<String> item, List<Entry> entries) {
        var children = item.getChildren();
        while (children.size() > entries.size()) {
            children.removeLast();
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i == children.size()) {
                children.add(new TreeItem<>(e.text()));
            } else if (!e.text().equals(children.get(i).getValue())) {
                children.get(i).setValue(e.text());
            }
            sync(children.get(i), e.children());
        }
    }
}
