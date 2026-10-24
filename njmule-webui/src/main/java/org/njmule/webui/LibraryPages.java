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
package org.njmule.webui;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadPriority;

/** The shared files and the statistics, as the desktop app shows them. */
final class LibraryPages {

    static final String SHARED = "/shared";
    static final String STATISTICS = "/statistics";

    private static final String ACTION = "action";

    private final EngineCalls calls;

    LibraryPages(EngineCalls calls) {
        this.calls = calls;
    }

    /** The shared files with their counters of this session or of all, as {@code query} chooses. */
    String shared(Html.Frame frame, Map<String, String> query) {
        List<SharedFileInfo> files = this.calls.await(this.calls.engine().sharedFiles());
        List<SharedDirectory> directories = this.calls.await(this.calls.engine().sharedDirectories());
        boolean total = query.getOrDefault("counts", "").equals("total");
        String csrf = frame.csrf();
        StringBuilder body = new StringBuilder(2048 + files.size() * 512);
        body.append("<div class=\"toolbar\"><span class=\"grow\">");
        if (directories.isEmpty()) {
            body.append("Only the incoming folder is shared.");
        } else {
            body.append("Folders: ");
            for (int i = 0; i < directories.size(); i++) {
                SharedDirectory d = directories.get(i);
                body.append(i == 0 ? "" : ", ")
                        .append("<span class=\"mono\">")
                        .append(Html.text(d.path().toString()))
                        .append("</span>")
                        .append(d.subdirectories() ? " (with subfolders)" : "");
            }
        }
        body.append("</span>")
                .append(Html.segments(
                        "Counters",
                        List.of(Map.entry("shared", "Session"), Map.entry("shared?counts=total", "Cumulative")),
                        total ? "shared?counts=total" : "shared"))
                .append("</div>");
        String summary = "Shared files: " + files.size()
                + frame.status().map(s -> " · uploads: " + s.uploads()).orElse("");
        if (files.isEmpty()) {
            body.append(Html.empty("No shared files. Files are shared once downloaded, and from the folders the"
                    + " settings of the desktop app or the command line name."));
            return Html.page(frame, "Shared files", summary, body.toString(), 0);
        }
        body.append("<div class=\"table\"><table><thead><tr><th>File Name</th><th class=\"num\">Size</th>")
                .append("<th>Priority</th><th class=\"num\">Requests</th><th class=\"num\">Accepted Requests</th>")
                .append("<th class=\"num\">Transferred Data</th><th>Folder</th></tr></thead><tbody>");
        for (SharedFileInfo f : files.stream()
                .sorted(Comparator.comparing(SharedFileInfo::name, String.CASE_INSENSITIVE_ORDER))
                .toList()) {
            SharedFileInfo.Statistics s = f.statistics();
            Path folder = f.path().getParent();
            body.append("<tr><td class=\"name\">")
                    .append(Html.text(f.name()))
                    .append("</td><td class=\"num\">")
                    .append(Html.size(f.size()))
                    .append("</td><td class=\"nowrap\">")
                    .append(Html.form(
                            SHARED,
                            csrf,
                            Html.hidden(ACTION, "priority")
                                    + Html.hidden("hash", f.hash().toHex())
                                    + priorities(f.priority()),
                            "Set",
                            "small"))
                    .append("</td><td class=\"num\">")
                    .append(Html.count(total ? s.totalRequests() : s.requests()))
                    .append("</td><td class=\"num\">")
                    .append(Html.count(total ? s.totalAccepted() : s.accepted()))
                    .append("</td><td class=\"num\">")
                    .append(Html.size(total ? s.totalTransferred() : s.transferred()))
                    .append("</td><td class=\"mono\">")
                    .append(folder == null ? "" : Html.text(folder.toString()))
                    .append("</td></tr>");
        }
        body.append("</tbody></table></div>");
        return Html.page(frame, "Shared files", summary, body.toString(), 0);
    }

    private static String priorities(UploadPriority current) {
        StringBuilder select = new StringBuilder("<select name=\"priority\" aria-label=\"Priority\">");
        for (UploadPriority p : UploadPriority.values()) {
            select.append("<option value=\"")
                    .append(p.name().toLowerCase(Locale.ROOT))
                    .append(p == current ? "\" selected>" : "\">")
                    .append(words(p))
                    .append("</option>");
        }
        return select.append("</select> ").toString();
    }

    private static String words(UploadPriority p) {
        return switch (p) {
            case VERY_LOW -> "Very Low";
            case LOW -> "Low";
            case NORMAL -> "Normal";
            case HIGH -> "High";
            case RELEASE -> "Release";
            case AUTO -> "Auto";
        };
    }

    /** What a posted form of the shared files asks; the page to show next. */
    String actOnShared(Map<String, String> form) {
        if (!form.getOrDefault(ACTION, "").equals("priority")) {
            throw new IllegalArgumentException("Unknown action.");
        }
        Hash16 hash = Hash16.parseHex(form.getOrDefault("hash", ""));
        UploadPriority priority =
                UploadPriority.valueOf(form.getOrDefault("priority", "").toUpperCase(Locale.ROOT));
        if (!this.calls.await(this.calls.engine().setUploadPriority(hash, priority))) {
            throw new IllegalArgumentException("That file is no longer shared: reload the page.");
        }
        return SHARED;
    }

    /** The numbers of this session and of all, then those of the servers and the shared files. */
    String statistics(Html.Frame frame) {
        EngineStatus s = frame.status()
                .orElseGet(() -> this.calls.await(this.calls.engine().status()));
        StringBuilder body = new StringBuilder(4096);
        body.append("<div class=\"tiles\">")
                .append(Html.tile(Html.rate(s.downloadBytesPerSecond()), "Download rate"))
                .append(Html.tile(Html.rate(s.uploadBytesPerSecond()), "Upload rate"))
                .append(Html.tile(Html.count(s.downloads()), "Downloads"))
                .append(Html.tile(Html.count(s.uploads()), "Uploading"))
                .append(Html.tile(Html.count(s.queued()), "On Queue"))
                .append(Html.tile(Html.count(s.sharedFiles()), "Shared Files"))
                .append("</div><div class=\"cards\">");
        StringBuilder session = new StringBuilder("<dl class=\"facts\">");
        fact(session, "Downloaded", Html.size(s.downloaded()));
        fact(session, "Uploaded", Html.size(s.uploaded()));
        fact(session, "UL:DL ratio", ratio(s.uploaded(), s.downloaded()));
        fact(session, "Running", Html.duration(s.sessionSeconds()));
        fact(session, "Average download rate", Html.rate(perSecond(s.downloaded(), s.sessionSeconds())));
        fact(session, "Average upload rate", Html.rate(perSecond(s.uploaded(), s.sessionSeconds())));
        body.append(Html.card("This session", Icons.STATISTICS, "", Html.Tone.NONE, session + "</dl>", ""));
        StringBuilder all = new StringBuilder("<dl class=\"facts\">");
        fact(all, "Downloaded total", Html.size(s.totalDownloaded()));
        fact(all, "Uploaded total", Html.size(s.totalUploaded()));
        fact(all, "UL:DL ratio", ratio(s.totalUploaded(), s.totalDownloaded()));
        fact(all, "Running in all", Html.duration(s.totalSeconds()));
        fact(all, "Sessions", Html.count(s.sessions()));
        body.append(Html.card("Cumulative", Icons.STATISTICS, "", Html.Tone.NONE, all + "</dl>", ""));
        Optional<List<ServerInfo>> servers =
                this.calls.maybe(this.calls.engine().servers());
        servers.ifPresent(list -> {
            StringBuilder out = new StringBuilder("<dl class=\"facts\">");
            long failed = list.stream().filter(x -> x.failed() > 0).count();
            fact(out, "Working", Html.count(list.size() - failed));
            fact(out, "Failed", Html.count(failed));
            fact(out, "Total", Html.count(list.size()));
            fact(
                    out,
                    "Users",
                    Html.count(list.stream().mapToLong(ServerInfo::users).sum()));
            fact(
                    out,
                    "Files",
                    Html.count(list.stream().mapToLong(ServerInfo::files).sum()));
            body.append(Html.card("Servers", Icons.SERVER, "", Html.Tone.NONE, out + "</dl>", ""));
        });
        this.calls.maybe(this.calls.engine().sharedFiles()).ifPresent(files -> {
            StringBuilder out = new StringBuilder("<dl class=\"facts\">");
            long size = files.stream().mapToLong(SharedFileInfo::size).sum();
            fact(out, "Count", Html.count(files.size()));
            fact(out, "Total size", Html.size(size));
            fact(out, "Average size", files.isEmpty() ? "-" : Html.size(size / files.size()));
            fact(
                    out,
                    "Largest",
                    files.stream().mapToLong(SharedFileInfo::size).max().stream()
                            .mapToObj(Html::size)
                            .findFirst()
                            .orElse("-"));
            body.append(Html.card("Shared files", Icons.SHARED, "", Html.Tone.NONE, out + "</dl>", ""));
        });
        body.append("</div>");
        String summary = "This session: " + Html.duration(s.sessionSeconds()) + " · ↓ " + Html.size(s.downloaded())
                + " · ↑ " + Html.size(s.uploaded());
        return Html.page(frame, "Statistics", summary, body.toString(), 0);
    }

    private static void fact(StringBuilder out, String term, String value) {
        out.append("<dt>").append(term).append("</dt><dd>").append(value).append("</dd>");
    }

    private static String ratio(long up, long down) {
        if (down <= 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f : 1", (double) up / down);
    }

    private static long perSecond(long bytes, long seconds) {
        return seconds <= 0 ? 0 : bytes / seconds;
    }
}
