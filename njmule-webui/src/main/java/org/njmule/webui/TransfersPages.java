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

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UploadInfo;

/**
 * The transfers, as the desktop app shows them: downloads, uploads, the queue and the banned clients as segments; the
 * downloads by state, with the details of one beside them.
 */
final class TransfersPages {

    static final String PATH = "/transfers";

    // The longest text of the link field: percent-encoded, a character takes at most 9 bytes, so a full field fits the
    // largest body a form may send.
    static final int MAX_LINKS_LENGTH = 65_536;

    // Pasted links are separated by white space, which file names may contain too.
    private static final Pattern LINKS = Pattern.compile("\\s+(?=ed2k://|magnet:\\?)", Pattern.CASE_INSENSITIVE);

    private static final String ACTION = "action";
    private static final String HASH = "hash";
    private static final String DASH = "\u2014";

    /** The states a download shows, in the words and tones of the desktop app's chips. */
    enum Shown {
        DOWNLOADING("Downloading", Html.Tone.BUSY),
        WAITING("Waiting", Html.Tone.NONE),
        NO_SOURCES("No sources", Html.Tone.WARN),
        PAUSED("Paused", Html.Tone.NONE),
        STOPPED("Stopped", Html.Tone.NONE),
        NO_SPACE("Insufficient disk space", Html.Tone.WARN),
        ERRONEOUS("Erroneous", Html.Tone.ERROR),
        COMPLETE("Complete", Html.Tone.OK);

        private final String words;
        private final Html.Tone tone;

        Shown(String words, Html.Tone tone) {
            this.words = words;
            this.tone = tone;
        }

        static Shown of(DownloadInfo d) {
            return switch (d.status()) {
                case DOWNLOADING -> d.transferring() > 0 ? DOWNLOADING : d.sources() == 0 ? NO_SOURCES : WAITING;
                case PAUSED -> PAUSED;
                case STOPPED -> STOPPED;
                case NO_SPACE -> NO_SPACE;
                case ERROR -> ERRONEOUS;
                case COMPLETE -> COMPLETE;
            };
        }
    }

    /** The state chips above the downloads: each takes the downloads shown in some states. */
    private enum Filter {
        ALL("All"),
        DOWNLOADING("Downloading"),
        WAITING("Waiting"),
        NO_SOURCES("No sources"),
        PAUSED("Paused"),
        ERRONEOUS("Erroneous"),
        COMPLETE("Complete");

        private final String words;

        Filter(String words) {
            this.words = words;
        }

        boolean takes(Shown shown) {
            return switch (this) {
                case ALL -> true;
                case DOWNLOADING -> shown == Shown.DOWNLOADING;
                case WAITING -> shown == Shown.WAITING;
                case NO_SOURCES -> shown == Shown.NO_SOURCES;
                case PAUSED -> shown == Shown.PAUSED || shown == Shown.STOPPED;
                case ERRONEOUS -> shown == Shown.ERRONEOUS || shown == Shown.NO_SPACE;
                case COMPLETE -> shown == Shown.COMPLETE;
            };
        }

        String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        static Filter of(String key) {
            for (Filter f : values()) {
                if (f.key().equals(key)) {
                    return f;
                }
            }
            return ALL;
        }
    }

    private final EngineCalls calls;

    TransfersPages(EngineCalls calls) {
        this.calls = calls;
    }

    /** The page {@code query} asks for: a segment, a state of the downloads, the details of one. */
    String page(Html.Frame frame, Map<String, String> query) {
        String view = query.getOrDefault("view", "downloads");
        List<DownloadInfo> downloads = this.calls.await(this.calls.engine().downloads());
        List<UploadInfo> uploads =
                this.calls.maybe(this.calls.engine().uploads()).orElse(List.of());
        List<BanInfo> bans = this.calls.maybe(this.calls.engine().bans()).orElse(List.of());
        long uploading = uploads.stream()
                .filter(u -> u.state() == UploadInfo.State.UPLOADING)
                .count();
        StringBuilder body = new StringBuilder(8192 + downloads.size() * 1024);
        body.append("<div class=\"toolbar\">")
                .append(Html.segments(
                        "Transfers",
                        List.of(
                                Map.entry(
                                        "transfers", "Downloads <span class=\"count\">" + downloads.size() + "</span>"),
                                Map.entry(
                                        "transfers?view=uploads",
                                        "Uploads <span class=\"count\">" + uploading + "</span>"),
                                Map.entry(
                                        "transfers?view=queue",
                                        "On queue <span class=\"count\">" + (uploads.size() - uploading) + "</span>"),
                                Map.entry(
                                        "transfers?view=banned",
                                        "Banned <span class=\"count\">" + bans.size() + "</span>")),
                        view.equals("downloads") ? "transfers" : "transfers?view=" + view))
                .append("<span class=\"grow\"></span>");
        String content = switch (view) {
            case "uploads" -> uploads(uploads, UploadInfo.State.UPLOADING, "Nobody downloads from us now.");
            case "queue" -> uploads(uploads, UploadInfo.State.QUEUED, "Nobody waits on our queue.");
            case "banned" -> banned(frame.csrf(), bans);
            default -> {
                body.append(addLinksForm(frame.csrf()));
                yield downloads(frame.csrf(), downloads, query);
            }
        };
        body.append("</div>").append(content);
        long active =
                downloads.stream().filter(d -> Shown.of(d) == Shown.DOWNLOADING).count();
        String summary = "Downloading: " + active
                + frame.status()
                        .map(s -> " · ↓ " + Html.rate(s.downloadBytesPerSecond()))
                        .orElse("");
        return Html.page(frame, "Transfers", summary, body.toString(), 0);
    }

    private static String addLinksForm(String csrf) {
        return Html.form(
                PATH,
                csrf,
                Html.hidden(ACTION, "add")
                        + "<input type=\"text\" name=\"link\" size=\"40\" required"
                        + " pattern=\"\\s*([eE][dD]2[kK]://|[mM][aA][gG][nN][eE][tT]:\\?).+\""
                        + " maxlength=\"" + MAX_LINKS_LENGTH + "\""
                        + " title=\"One or more eD2K file or magnet links\""
                        + " aria-label=\"eD2K or magnet links\" placeholder=\"ed2k://|file|...|/\"> ",
                "Add links",
                true);
    }

    private String downloads(String csrf, List<DownloadInfo> downloads, Map<String, String> query) {
        StringBuilder out = new StringBuilder(4096 + downloads.size() * 1024);
        Optional<String> cancelled = Optional.ofNullable(query.get("cancelled"));
        cancelled.ifPresent(hash -> out.append("<p class=\"notice\">The download was cancelled. ")
                .append(Html.form(PATH, csrf, Html.hidden(ACTION, "restore") + Html.hidden(HASH, hash), "Undo", false))
                .append("</p>"));
        if (downloads.isEmpty()) {
            return out.append(Html.empty("No downloads. Search the network, or paste eD2K links above."))
                    .toString();
        }
        Filter filter = Filter.of(query.getOrDefault("state", ""));
        Map<Filter, Integer> counts = new LinkedHashMap<>();
        for (Filter f : Filter.values()) {
            counts.put(f, (int)
                    downloads.stream().filter(d -> f.takes(Shown.of(d))).count());
        }
        out.append("<nav class=\"filters\" aria-label=\"States\">");
        counts.forEach((f, n) -> {
            if (f == Filter.ALL || n > 0 || f == filter) {
                out.append("<a href=\"transfers")
                        .append(f == Filter.ALL ? "" : "?state=" + f.key())
                        .append(f == filter ? "\" aria-current=\"true\">" : "\">")
                        .append(f.words)
                        .append(" <span class=\"count\">")
                        .append(n)
                        .append("</span></a>");
            }
        });
        out.append("</nav><p></p>");
        Map<Integer, String> categories = new HashMap<>();
        this.calls
                .maybe(this.calls.engine().categories())
                .orElse(List.of())
                .forEach(c -> categories.put(c.id(), c.name()));
        Optional<DownloadInfo> chosen = file(query.get(HASH))
                .flatMap(h -> downloads.stream().filter(d -> d.hash().equals(h)).findFirst());
        String back = filter == Filter.ALL ? "transfers?" : "transfers?state=" + filter.key() + "&amp;";
        StringBuilder table = new StringBuilder(2048 + downloads.size() * 1024);
        table.append("<div class=\"table\"><table><thead><tr><th>File Name</th><th class=\"num\">Size</th>")
                .append("<th>Progress</th><th class=\"num\">Speed</th><th class=\"num\">Sources</th><th>Status</th>")
                .append(
                        "<th class=\"num\">Remaining</th><th>Priority</th><th>Category</th><th></th></tr></thead><tbody>");
        int shown = 0;
        for (DownloadInfo d : downloads) {
            if (!filter.takes(Shown.of(d))) {
                continue;
            }
            shown++;
            boolean isChosen = chosen.filter(c -> c.hash().equals(d.hash())).isPresent();
            table.append(isChosen ? "<tr class=\"chosen\">" : "<tr>")
                    .append("<td class=\"name\"><a href=\"")
                    .append(back)
                    .append("hash=")
                    .append(d.hash().toHex())
                    .append("\">")
                    .append(Html.text(d.name()))
                    .append("</a></td><td class=\"num\">")
                    .append(Html.size(d.size()))
                    .append("</td><td class=\"nowrap\">")
                    .append(Html.progress(fraction(d), bar(d)))
                    .append("</td><td class=\"num\">")
                    .append(d.bytesPerSecond() > 0 ? Html.rate(d.bytesPerSecond()) : DASH)
                    .append("</td><td class=\"num\">")
                    .append(d.status() == DownloadInfo.Status.COMPLETE ? DASH : d.transferring() + " / " + d.sources())
                    .append("</td><td>")
                    .append(Html.chip(Shown.of(d).words, Shown.of(d).tone, Optional.empty()))
                    .append("</td><td class=\"num\">")
                    .append(remaining(d))
                    .append("</td><td class=\"nowrap\">")
                    .append(priority(csrf, d))
                    .append("</td><td>")
                    .append(Html.text(categories.getOrDefault(d.category(), "")))
                    .append("</td><td class=\"actions\">")
                    .append(actions(csrf, d))
                    .append("</td></tr>");
        }
        table.append("</tbody></table></div>");
        if (shown == 0) {
            table.setLength(0);
            table.append(Html.empty("No download is " + filter.words.toLowerCase(Locale.ROOT) + "."));
        }
        if (chosen.isPresent()) {
            out.append("<div class=\"split\"><div>")
                    .append(table)
                    .append("</div>")
                    .append(details(csrf, chosen.get(), categories))
                    .append("</div>");
        } else {
            out.append(table);
        }
        return out.toString();
    }

    // The file a link names, if it names a valid one.
    private static Optional<Hash16> file(String hex) {
        if (hex == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Hash16.parseHex(hex));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static double fraction(DownloadInfo d) {
        return d.size() <= 0 ? 0 : (double) d.completed() / d.size();
    }

    private static String bar(DownloadInfo d) {
        return switch (Shown.of(d)) {
            case COMPLETE -> "done";
            case PAUSED, STOPPED, NO_SPACE, ERRONEOUS -> "halted";
            default -> "";
        };
    }

    // The time left at the current rate, while it downloads.
    private static String remaining(DownloadInfo d) {
        if (d.status() != DownloadInfo.Status.DOWNLOADING || d.bytesPerSecond() <= 0) {
            return DASH;
        }
        return Html.duration((d.size() - d.completed()) / d.bytesPerSecond());
    }

    // The priority as set, Auto with the one it takes now; a choice to change it.
    private static String priority(String csrf, DownloadInfo d) {
        StringBuilder select = new StringBuilder("<select name=\"priority\" aria-label=\"Priority\">");
        for (DownloadPriority p : DownloadPriority.values()) {
            String name = p.name().toLowerCase(Locale.ROOT);
            String words = p == DownloadPriority.AUTO
                    ? "Auto (" + capitalized(d.priority().effective(d.sources()).name()) + ")"
                    : capitalized(p.name());
            select.append("<option value=\"")
                    .append(name)
                    .append(p == d.priority() ? "\" selected>" : "\">")
                    .append(words)
                    .append("</option>");
        }
        select.append("</select> ");
        return Html.form(
                PATH,
                csrf,
                Html.hidden(ACTION, "priority") + Html.hidden(HASH, d.hash().toHex()) + select,
                "Set",
                "small");
    }

    private static String actions(String csrf, DownloadInfo d) {
        String hash = Html.hidden(HASH, d.hash().toHex());
        StringBuilder out = new StringBuilder(512);
        if (d.status() != DownloadInfo.Status.COMPLETE) {
            boolean halted = d.status() == DownloadInfo.Status.PAUSED || d.status() == DownloadInfo.Status.STOPPED;
            out.append(Html.form(
                            PATH,
                            csrf,
                            Html.hidden(ACTION, halted ? "resume" : "pause") + hash,
                            halted ? "Resume" : "Pause",
                            "small"))
                    .append(' ');
            if (d.status() != DownloadInfo.Status.STOPPED) {
                out.append(Html.form(PATH, csrf, Html.hidden(ACTION, "stop") + hash, "Stop", "small"))
                        .append(' ');
            }
            out.append(Html.form(PATH, csrf, Html.hidden(ACTION, "cancel") + hash, "Cancel", "small danger"));
        }
        return out.toString();
    }

    // The inspector of one download: its bar of parts, its facts, its sources and the names they give it.
    private String details(String csrf, DownloadInfo d, Map<Integer, String> categories) {
        StringBuilder out = new StringBuilder(4096);
        String category = categories.getOrDefault(d.category(), "");
        out.append("<aside class=\"panel\" aria-label=\"Details\"><h2>")
                .append(Html.text(d.name()))
                .append("</h2><p class=\"count\">")
                .append(Html.size(d.size()))
                .append(category.isEmpty() ? "" : " · " + Html.text(category))
                .append(" · ")
                .append(capitalized(d.priority().name()))
                .append("</p><p>")
                .append(Html.progress(fraction(d), bar(d)))
                .append(" ")
                .append(Html.size(d.completed()))
                .append(" of ")
                .append(Html.size(d.size()))
                .append("</p>")
                .append(parts(d.parts(), d.size()))
                .append("<ul class=\"legend\"><li><i class=\"have\"></i>Downloaded</li>")
                .append("<li><i class=\"missing\"></i>Missing</li></ul><p>")
                .append(actions(csrf, d))
                .append("</p><dl class=\"facts\">");
        int partCount = d.size() <= 0 ? 0 : (int) ((d.size() + PartMap.PART_SIZE - 1) / PartMap.PART_SIZE);
        fact(out, "Status", Html.text(Shown.of(d).words));
        fact(out, "Completed", Html.size(d.completed()) + " (" + (int) Math.floor(fraction(d) * 100) + " %)");
        fact(out, "Parts", completeParts(d.parts(), d.size(), partCount) + " / " + partCount);
        fact(out, "Datarate", d.bytesPerSecond() > 0 ? Html.rate(d.bytesPerSecond()) : DASH);
        fact(out, "Sources", d.sources() + " (transferring: " + d.transferring() + ")");
        fact(out, "Remaining", remaining(d));
        fact(out, "Hash", "<span class=\"mono\">" + d.hash().toHex() + "</span>");
        out.append("</dl><p><label>eD2K link<br><input type=\"text\" readonly size=\"34\" value=\"")
                .append(Html.text(link(d)))
                .append("\"></label></p>");
        List<SourceInfo> sources = this.calls.maybe(this.calls.engine().sources()).orElse(List.of()).stream()
                .filter(s -> s.file().equals(d.hash()))
                .sorted(Comparator.comparing((SourceInfo s) -> s.state() != SourceInfo.State.DOWNLOADING)
                        .thenComparingInt(s -> s.queueRank() <= 0 ? Integer.MAX_VALUE : s.queueRank()))
                .toList();
        out.append("<h2>Sources <span class=\"count\">").append(sources.size()).append("</span></h2>");
        if (sources.isEmpty()) {
            out.append("<p class=\"count\">None known yet.</p>");
        } else {
            out.append("<div class=\"table\"><table><thead><tr><th>User</th><th>State</th>")
                    .append("<th class=\"num\">Parts</th></tr></thead><tbody>");
            for (SourceInfo s : sources) {
                out.append("<tr><td>")
                        .append(Html.text(s.nick().orElse(s.address())))
                        .append("<br><span class=\"count\">")
                        .append(Html.text(s.software()))
                        .append("</span></td><td>")
                        .append(Html.text(sourceState(s)))
                        .append("</td><td class=\"num\">")
                        .append(s.parts())
                        .append(" / ")
                        .append(s.partCount())
                        .append("</td></tr>");
            }
            out.append("</tbody></table></div>");
        }
        Map<String, Integer> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sources.forEach(s -> s.reportedName().ifPresent(n -> names.merge(n, 1, Integer::sum)));
        if (!names.isEmpty()) {
            out.append("<h2>Source names</h2><div class=\"table\"><table><thead><tr><th>File Name</th>")
                    .append("<th class=\"num\">Sources</th></tr></thead><tbody>");
            names.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> out.append("<tr><td class=\"name\">")
                            .append(Html.text(e.getKey()))
                            .append("</td><td class=\"num\">")
                            .append(e.getValue())
                            .append("</td></tr>"));
            out.append("</tbody></table></div>");
        }
        return out.append("</aside>").toString();
    }

    private static void fact(StringBuilder out, String term, String value) {
        out.append("<dt>").append(term).append("</dt><dd>").append(value).append("</dd>");
    }

    // The bytes held over the whole file, the missing ones marked; scaled to the width by the browser.
    static String parts(PartMap parts, long size) {
        if (size <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(256 + parts.missing().size() * 64)
                .append("<svg class=\"parts\" viewBox=\"0 0 1000 10\" preserveAspectRatio=\"none\" role=\"img\"")
                .append(" aria-label=\"Parts of the file\"><rect class=\"have\" width=\"1000\" height=\"10\"/>");
        for (PartMap.Span span : parts.missing()) {
            double x = span.start() * 1000.0 / size;
            double width = Math.max(0.5, (span.end() - span.start()) * 1000.0 / size);
            out.append(String.format(
                    Locale.ROOT, "<rect class=\"missing\" x=\"%.2f\" width=\"%.2f\" height=\"10\"/>", x, width));
        }
        return out.append("</svg>").toString();
    }

    // The parts with no missing byte.
    private static int completeParts(PartMap parts, long size, int partCount) {
        boolean[] incomplete = new boolean[partCount];
        for (PartMap.Span span : parts.missing()) {
            long last = Math.min(size, span.end()) - 1;
            for (long p = span.start() / PartMap.PART_SIZE; p <= last / PartMap.PART_SIZE && p < partCount; p++) {
                incomplete[(int) p] = true;
            }
        }
        int complete = 0;
        for (boolean missing : incomplete) {
            complete += missing ? 0 : 1;
        }
        return complete;
    }

    private static String sourceState(SourceInfo s) {
        return switch (s.state()) {
            case WAITING -> "Waiting";
            case CONNECTING -> "Connecting";
            case ASKING -> "Asking";
            case QUEUED -> s.queueRank() > 0 ? "On queue, rank " + s.queueRank() : "On queue";
            case DOWNLOADING -> "Transferring, " + Html.size(s.received());
            case A4AF -> "Asked for another file: " + s.askedForName();
        };
    }

    private static String link(DownloadInfo d) {
        return d.size() > 0 ? d.link().toUri() : "";
    }

    private static String uploads(List<UploadInfo> uploads, UploadInfo.State state, String none) {
        List<UploadInfo> shown =
                uploads.stream().filter(u -> u.state() == state).toList();
        if (shown.isEmpty()) {
            return Html.empty(none);
        }
        StringBuilder out = new StringBuilder(1024 + shown.size() * 384);
        out.append("<div class=\"table\"><table><thead><tr><th>User Name</th><th>Software</th><th>File Name</th>")
                .append("<th class=\"num\">Speed</th><th class=\"num\">Transferred</th><th class=\"num\">Waited</th>")
                .append("</tr></thead><tbody>");
        for (UploadInfo u : shown) {
            out.append("<tr><td>")
                    .append(Html.text(u.nick().orElse(u.address())))
                    .append("</td><td>")
                    .append(Html.text(u.software()))
                    .append("</td><td class=\"name\">")
                    .append(Html.text(u.file()))
                    .append("</td><td class=\"num\">")
                    .append(u.bytesPerSecond() > 0 ? Html.rate(u.bytesPerSecond()) : DASH)
                    .append("</td><td class=\"num\">")
                    .append(Html.size(u.transferred()))
                    .append("</td><td class=\"num\">")
                    .append(Html.duration(u.waitingSeconds()))
                    .append("</td></tr>");
        }
        return out.append("</tbody></table></div>").toString();
    }

    private static String banned(String csrf, List<BanInfo> bans) {
        if (bans.isEmpty()) {
            return Html.empty("No client is banned.");
        }
        StringBuilder out = new StringBuilder(512 + bans.size() * 256);
        out.append("<div class=\"table\"><table><thead><tr><th>IP</th><th class=\"num\">Time left</th><th></th>")
                .append("</tr></thead><tbody>");
        for (BanInfo b : bans) {
            out.append("<tr><td class=\"mono\">")
                    .append(Html.text(b.address()))
                    .append("</td><td class=\"num\">")
                    .append(Html.duration(b.secondsLeft()))
                    .append("</td><td class=\"actions\">")
                    .append(Html.form(
                            PATH,
                            csrf,
                            Html.hidden(ACTION, "unban") + Html.hidden("ip", b.address()),
                            "Unban",
                            "small"))
                    .append("</td></tr>");
        }
        return out.append("</tbody></table></div>").toString();
    }

    /** A cancel asks first, as the desktop app does: this page names both outcomes. */
    String confirmCancel(Html.Frame frame, Hash16 hash) {
        DownloadInfo d = this.calls.await(this.calls.engine().downloads()).stream()
                .filter(x -> x.hash().equals(hash))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That download is gone: reload the page."));
        String body = "<p>Cancel <b>" + Html.text(d.name()) + "</b>? Its part file and what was downloaded of it ("
                + Html.size(d.completed()) + ") are deleted.</p><p>"
                + Html.form(
                        PATH,
                        frame.csrf(),
                        Html.hidden(ACTION, "cancel")
                                + Html.hidden(HASH, hash.toHex())
                                + Html.hidden("confirmed", "yes"),
                        "Cancel download",
                        "danger")
                + " <a href=\"transfers\">Keep downloading</a></p>";
        return Html.page(frame, "Cancel the download?", "", body, 0);
    }

    /** Whether {@code form} cancels a download not confirmed yet, which {@link #confirmCancel} asks about first. */
    static boolean asksFirst(Map<String, String> form) {
        return form.getOrDefault(ACTION, "").equals("cancel")
                && !form.getOrDefault("confirmed", "").equals("yes");
    }

    /** What a posted form asks; the page to show next. */
    String act(Map<String, String> form) {
        String action = form.getOrDefault(ACTION, "");
        if (action.equals("add")) {
            addLinks(form.getOrDefault("link", ""));
            return PATH;
        }
        if (action.equals("unban")) {
            if (!this.calls.await(this.calls.engine().unban(form.getOrDefault("ip", "")))) {
                throw new IllegalArgumentException("That client is no longer banned: reload the page.");
            }
            return PATH + "?view=banned";
        }
        Hash16 hash = Hash16.parseHex(form.getOrDefault(HASH, ""));
        CompletableFuture<Boolean> done = switch (action) {
            case "pause" -> this.calls.engine().pauseDownload(hash);
            case "resume" -> this.calls.engine().resumeDownload(hash);
            case "stop" -> this.calls.engine().stopDownload(hash);
            case "priority" ->
                this.calls
                        .engine()
                        .setDownloadPriority(
                                hash,
                                DownloadPriority.valueOf(
                                        form.getOrDefault("priority", "").toUpperCase(Locale.ROOT)));
            case "cancel" -> this.calls.engine().cancelDownload(hash);
            case "restore" -> this.calls.engine().restoreDownload(hash);
            default -> throw new IllegalArgumentException("Unknown action.");
        };
        if (!this.calls.await(done)) {
            throw new IllegalArgumentException(
                    action.equals("restore")
                            ? "Too late to undo: the download is gone."
                            : "That download is gone: reload the page.");
        }
        return action.equals("cancel") ? PATH + "?cancelled=" + hash.toHex() : PATH;
    }

    /** Adds the downloads of one or more eD2K file links, separated by white space. */
    void addLinks(String text) {
        for (String one : LINKS.split(text.strip(), -1)) {
            if (one.isEmpty()) {
                continue;
            }
            if (!(Ed2kLink.tryParse(one).orElse(null) instanceof Ed2kLink.FileLink link)) {
                throw new IllegalArgumentException("Not an eD2K file link: " + one);
            }
            this.calls.await(this.calls.engine().addDownload(link));
        }
    }

    /** Whether {@code text} holds links rather than words to search. */
    static boolean holdsLinks(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        return t.startsWith("ed2k://") || t.startsWith("magnet:?");
    }

    private static String capitalized(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
