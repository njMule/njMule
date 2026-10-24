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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.remote.SearchCollector;

/**
 * The searches, as the desktop app shows them: the words with the network and filters, one tab a search, the results
 * sorted by any column with their other names under them, what each is to us, and the media details when there are.
 */
final class SearchPages {

    static final String PATH = "/search";

    /** The scope that picks the network as the desktop app does. */
    static final String AUTOMATIC = "AUTOMATIC";

    // Seconds between the loads of a search page while its results may come.
    static final int REFRESH = 2;

    private static final String ACTION = "action";
    private static final String SEARCH = "search";

    /** The kinds of file a search may ask for, as the attribute the search expression takes. */
    private enum FileType {
        ANY("Any", ""),
        ARCHIVE("Archive", "archive"),
        AUDIO("Audio", "audio"),
        CD_IMAGE("CD-Image", "cd"),
        PICTURE("Picture", "image"),
        PROGRAM("Program", "program"),
        VIDEO("Video", "video"),
        DOCUMENT("Document", "document");

        private final String words;
        private final String attribute;

        FileType(String words, String attribute) {
            this.words = words;
            this.attribute = attribute;
        }
    }

    /** The columns the results sort by; spam goes last whatever the column. */
    private enum Column {
        NAME("File Name"),
        SIZE("Size"),
        SOURCES("Availability"),
        COMPLETE("Complete"),
        TYPE("Type");

        private final String title;

        Column(String title) {
            this.title = title;
        }

        Comparator<SearchResult> order() {
            return switch (this) {
                case NAME -> Comparator.comparing(SearchResult::name, String.CASE_INSENSITIVE_ORDER);
                case SIZE -> Comparator.comparingLong(SearchResult::size);
                case SOURCES -> Comparator.comparingInt(SearchResult::sources);
                case COMPLETE -> Comparator.comparingInt(SearchResult::completeSources);
                case TYPE -> Comparator.comparing((SearchResult r) -> r.type().orElse(""));
            };
        }

        static Column of(String key) {
            for (Column c : values()) {
                if (c.name().equalsIgnoreCase(key)) {
                    return c;
                }
            }
            return SOURCES;
        }
    }

    private final EngineCalls calls;
    private final SearchCollector searches;

    SearchPages(EngineCalls calls, SearchCollector searches) {
        this.calls = calls;
        this.searches = searches;
    }

    /** The page of the search {@code query} names, or of the latest one. */
    String page(Html.Frame frame, Map<String, String> query) {
        String csrf = frame.csrf();
        List<SearchCollector.Listed> started = this.searches.searches();
        Long id = parseId(query.get("id"));
        if (id == null && !started.isEmpty()) {
            id = started.getLast().id();
        }
        List<SearchResult> found = id == null ? null : this.searches.results(id).orElse(null);
        SearchCollector.Asked asked = id == null
                ? new SearchCollector.Asked("", AUTOMATIC)
                : this.searches.asked(id).orElse(new SearchCollector.Asked("", AUTOMATIC));
        StringBuilder body = new StringBuilder(8192);
        body.append(form(csrf, asked));
        if (!started.isEmpty()) {
            body.append("<div class=\"toolbar\"><nav class=\"filters\" aria-label=\"Searches\">");
            for (SearchCollector.Listed s : started) {
                body.append("<a href=\"search?id=")
                        .append(s.id())
                        .append(s.id() == id ? "\" aria-current=\"true\">" : "\">")
                        .append(Html.text(
                                s.asked().expression().isEmpty()
                                        ? "#" + s.id()
                                        : words(s.asked().expression())))
                        .append(" <span class=\"count\">")
                        .append(this.searches.results(s.id()).map(List::size).orElse(0))
                        .append("</span></a>");
            }
            body.append("</nav></div>");
        }
        long running =
                started.stream().filter(s -> this.searches.running(s.id())).count();
        String summary = "Searches: " + started.size() + " · running: " + running;
        if (found == null) {
            body.append(Html.empty(
                    "Type a few words of the file's name. Filters narrow the results by kind, size and sources;"
                            + " Kad and Global reach more of the network than one server."));
            return Html.page(frame, "Search", summary, body.toString(), 0);
        }
        boolean isRunning = this.searches.running(id);
        String hidden = Html.hidden(SEARCH, String.valueOf(id));
        body.append("<div class=\"toolbar\"><span>")
                .append(isRunning ? "Searching… " : "Search finished: ")
                .append(found.size())
                .append(found.size() == 1 ? " result" : " results")
                .append(isRunning ? " so far." : ".")
                .append("</span><span class=\"grow\"></span>")
                .append(
                        isRunning
                                ? Html.form(PATH, csrf, Html.hidden(ACTION, "stop") + hidden, "Stop", "small")
                                : Html.form(PATH, csrf, Html.hidden(ACTION, "more") + hidden, "More results", "small"))
                .append(' ')
                .append(Html.form(PATH, csrf, Html.hidden(ACTION, "close") + hidden, "Close", "small"))
                .append("</div>");
        if (found.isEmpty()) {
            body.append(Html.empty(isRunning ? "Nothing yet: results come as the network answers." : "No results."));
            return Html.page(frame, "Search", summary, body.toString(), isRunning ? REFRESH : 0);
        }
        body.append(results(csrf, id, found, query));
        return Html.page(frame, "Search", summary, body.toString(), isRunning ? REFRESH : 0);
    }

    // The words of the box: the expression without the attributes the filters put after them.
    private static String words(String expression) {
        int at = expression.indexOf(" @");
        return at < 0 ? expression : expression.substring(0, at);
    }

    private static String form(String csrf, SearchCollector.Asked asked) {
        StringBuilder out = new StringBuilder(2048)
                .append("<form method=\"post\" action=\"search\"><input type=\"hidden\" name=\"csrf\" value=\"")
                .append(Html.text(csrf))
                .append("\"><div class=\"toolbar\"><input type=\"search\" name=\"query\" class=\"grow\" required")
                .append(" maxlength=\"")
                .append(SearchRequest.MAX_EXPRESSION_LENGTH)
                .append("\" aria-label=\"Words of the file's name\" value=\"")
                .append(Html.text(words(asked.expression())))
                .append("\" autofocus> <fieldset class=\"segments radios\"><legend class=\"skip\">Network</legend>");
        for (String[] scope : new String[][] {
            {AUTOMATIC, "Automatic"}, {"SERVER", "Server"}, {"GLOBAL", "Global (Servers)"}, {"KAD", "Kad"}
        }) {
            String chosen = asked.scope().isEmpty() ? AUTOMATIC : asked.scope();
            out.append("<input type=\"radio\" name=\"scope\" id=\"scope-")
                    .append(scope[0])
                    .append("\" value=\"")
                    .append(scope[0])
                    .append(scope[0].equals(chosen) ? "\" checked>" : "\">")
                    .append("<label for=\"scope-")
                    .append(scope[0])
                    .append("\">")
                    .append(scope[1])
                    .append("</label>");
        }
        out.append("</fieldset> <button class=\"main\">Search</button></div>")
                .append("<details class=\"box\"><summary>Filters</summary><div class=\"fields\">")
                .append("<label>Type<select name=\"type\">");
        for (FileType t : FileType.values()) {
            out.append("<option value=\"")
                    .append(t.name())
                    .append("\">")
                    .append(t.words)
                    .append("</option>");
        }
        return out.append("</select></label>")
                .append(number("minSize", "Min. Size [MB]"))
                .append(number("maxSize", "Max. Size [MB]"))
                .append(number("availability", "Availability"))
                .append(number("complete", "Complete Sources"))
                .append("<label>Extension<input type=\"text\" name=\"extension\" size=\"8\" maxlength=\"16\"></label>")
                .append("</div></details></form>")
                .toString();
    }

    private static String number(String name, String label) {
        return "<label>" + label + "<input type=\"number\" name=\"" + name + "\" min=\"0\" max=\"99999999\"></label>";
    }

    private String results(String csrf, long id, List<SearchResult> found, Map<String, String> query) {
        Column column = Column.of(query.getOrDefault("sort", ""));
        boolean up = query.getOrDefault("dir", "").equals("up");
        Comparator<SearchResult> order = up ? column.order() : column.order().reversed();
        List<SearchResult> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing(SearchResult::isSpam).thenComparing(order));
        Set<Hash16> downloading = knownHashes(this.calls.engine().downloads(), DownloadInfo::hash);
        Set<Hash16> shared = knownHashes(this.calls.engine().sharedFiles(), SharedFileInfo::hash);
        Optional<FileHistory> history = this.calls.maybe(this.calls.engine().fileHistory());
        // The media columns show when a result has something in them, as the files of other kinds have nothing.
        List<MediaColumn> media = MediaColumn.ALL.stream()
                .filter(c -> found.stream().anyMatch(c::has))
                .toList();
        StringBuilder out = new StringBuilder(4096 + sorted.size() * 640);
        out.append("<div class=\"table\"><table class=\"results\"><thead><tr>");
        for (Column c : Column.values()) {
            boolean current = c == column;
            boolean numeric = c == Column.SIZE || c == Column.SOURCES || c == Column.COMPLETE;
            out.append(numeric ? "<th class=\"num\">" : "<th>")
                    .append("<a href=\"search?id=")
                    .append(id)
                    .append("&amp;sort=")
                    .append(c.name().toLowerCase(Locale.ROOT))
                    .append(current && !up ? "&amp;dir=up" : "")
                    .append(current ? "\" aria-current=\"true\">" : "\">")
                    .append(c.title)
                    .append(current ? (up ? " ▲" : " ▼") : "")
                    .append("</a></th>");
        }
        out.append("<th>Status</th>");
        media.forEach(c -> out.append("<th>").append(c.title).append("</th>"));
        out.append("<th></th></tr></thead><tbody>");
        String hidden = Html.hidden(SEARCH, String.valueOf(id));
        for (SearchResult r : sorted) {
            Optional<String> known = known(r, downloading, shared, history);
            String hash = Html.hidden("hash", r.hash().toHex());
            out.append(r.isSpam() ? "<tr class=\"spam\">" : "<tr>")
                    .append("<td class=\"name\">")
                    .append(downloading.contains(r.hash()) ? "<b>" + Html.text(r.name()) + "</b>" : Html.text(r.name()))
                    .append(otherNames(r))
                    .append("</td><td class=\"num\">")
                    .append(Html.size(r.size()))
                    .append("</td><td class=\"num\">")
                    .append(r.sources())
                    .append("</td><td class=\"num\">")
                    .append(complete(r))
                    .append("</td><td>")
                    .append(Html.text(r.type().orElse("")))
                    .append("</td><td>")
                    .append(known.map(k -> Html.chip(k, tone(k), Optional.empty()))
                            .orElse(""))
                    .append("</td>");
            media.forEach(c -> out.append("<td>").append(Html.text(c.text(r))).append("</td>"));
            out.append("<td class=\"actions\">")
                    .append(Html.form(PATH, csrf, Html.hidden(ACTION, "download") + hidden + hash, "Download", "small"))
                    .append(' ')
                    .append(Html.form(
                            PATH,
                            csrf,
                            Html.hidden(ACTION, r.isSpam() ? "not-spam" : "spam") + hidden + hash,
                            r.isSpam() ? "Not spam" : "Spam",
                            "small"))
                    .append("</td></tr>");
        }
        return out.append("</tbody></table></div>").toString();
    }

    // The other names the sources give the file, with how many give each, when there are.
    private static String otherNames(SearchResult r) {
        List<SearchResult.Name> others = r.names().stream()
                .filter(n -> !n.name().equals(r.name()))
                .sorted(Comparator.comparingInt(SearchResult.Name::sources).reversed())
                .toList();
        if (others.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<ul class=\"names\">");
        for (SearchResult.Name n : others.subList(0, Math.min(5, others.size()))) {
            out.append("<li>")
                    .append(Html.text(n.name()))
                    .append(" (")
                    .append(n.sources())
                    .append(")</li>");
        }
        if (others.size() > 5) {
            out.append("<li>and ").append(others.size() - 5).append(" more names</li>");
        }
        return out.append("</ul>").toString();
    }

    // What the file is to us, as the desktop app says it.
    private static Optional<String> known(
            SearchResult r, Set<Hash16> downloading, Set<Hash16> shared, Optional<FileHistory> history) {
        if (downloading.contains(r.hash())) {
            return Optional.of("Downloading");
        }
        if (shared.contains(r.hash())) {
            return Optional.of("Shared");
        }
        if (history.filter(h -> h.downloaded(r.hash())).isPresent()) {
            return Optional.of("Downloaded");
        }
        if (history.filter(h -> h.cancelled(r.hash())).isPresent()) {
            return Optional.of("Cancelled");
        }
        return r.isSpam() ? Optional.of("Spam") : Optional.empty();
    }

    private static Html.Tone tone(String known) {
        return switch (known) {
            case "Downloading" -> Html.Tone.BUSY;
            case "Shared", "Downloaded" -> Html.Tone.OK;
            case "Spam" -> Html.Tone.WARN;
            default -> Html.Tone.NONE;
        };
    }

    // The files a column of results marks; none when the engine cannot tell, which leaves the results shown.
    private <T> Set<Hash16> knownHashes(CompletableFuture<List<T>> files, Function<T, Hash16> hash) {
        return this.calls.maybe(files).orElse(List.of()).stream().map(hash).collect(Collectors.toSet());
    }

    // As the desktop app: the complete sources as a share of the sources, "?" when the network does not tell (Kad),
    // but a file of one part is whole wherever it is.
    static String complete(SearchResult r) {
        if (r.completeSources() < 0) {
            return r.size() <= PartMap.PART_SIZE ? "Yes" : "?";
        }
        if (r.completeSources() == 0) {
            return "0%";
        }
        return r.sources() > 0 ? (100L * r.completeSources() / r.sources()) + "%" : "Yes";
    }

    /** What a posted form of the searches asks; the page to show next. */
    String act(Map<String, String> form, Optional<EngineStatus> status, TransfersPages transfers) {
        String action = form.getOrDefault(ACTION, "");
        return switch (action) {
            case "download" -> {
                this.calls.await(this.calls.engine().addDownload(result(form).link()));
                yield TransfersPages.PATH;
            }
            case "spam", "not-spam" -> {
                this.calls.await(this.calls.engine().markSpam(List.of(result(form)), action.equals("spam")));
                yield PATH + "?id=" + searchId(form);
            }
            case "more" -> {
                if (!this.calls.await(this.calls.engine().moreResults(new SearchId(searchId(form))))) {
                    throw new IllegalArgumentException("This search cannot ask for more.");
                }
                yield PATH + "?id=" + searchId(form);
            }
            case "stop" -> {
                this.calls.await(this.calls.engine().stopSearch(new SearchId(searchId(form))));
                yield PATH + "?id=" + searchId(form);
            }
            case "close" -> {
                long id = searchId(form);
                this.calls.maybe(this.calls.engine().stopSearch(new SearchId(id)));
                this.searches.forget(id);
                yield PATH;
            }
            default -> search(form, status, transfers);
        };
    }

    // New words to search, or links pasted in the search field, which are downloaded.
    private String search(Map<String, String> form, Optional<EngineStatus> status, TransfersPages transfers) {
        String words = form.getOrDefault("query", "").strip();
        if (words.isEmpty()) {
            return PATH;
        }
        if (TransfersPages.holdsLinks(words)) {
            transfers.addLinks(words);
            return TransfersPages.PATH;
        }
        String expression = expression(words, form);
        SearchRequest.checkExpression(expression);
        String chosen = form.getOrDefault("scope", SearchRequest.Scope.SERVER.name());
        SearchRequest.Scope scope =
                chosen.equals(AUTOMATIC) ? automaticScope(status) : SearchRequest.Scope.valueOf(chosen);
        SearchId id = this.calls.await(this.calls.engine().search(new SearchRequest(expression, scope)));
        this.searches.started(id.value(), new SearchCollector.Asked(expression, chosen));
        return PATH + "?id=" + id.value();
    }

    // The words with the filters as attributes, as the desktop app writes them.
    private static String expression(String words, Map<String, String> form) {
        StringBuilder e = new StringBuilder(words);
        FileType type = FileType.valueOf(form.getOrDefault("type", FileType.ANY.name()));
        if (type != FileType.ANY) {
            e.append(" @type=").append(type.attribute);
        }
        number(form, "minSize").ifPresent(v -> e.append(" @size>=").append(v).append('m'));
        number(form, "maxSize").ifPresent(v -> e.append(" @size<=").append(v).append('m'));
        number(form, "availability").ifPresent(v -> e.append(" @sources>=").append(v));
        number(form, "complete").ifPresent(v -> e.append(" @complete>=").append(v));
        String ext = form.getOrDefault("extension", "").strip();
        if (!ext.isEmpty()) {
            e.append(" @ext=").append(ext.startsWith(".") ? ext.substring(1) : ext);
        }
        return e.toString();
    }

    private static Optional<Integer> number(Map<String, String> form, String name) {
        String text = form.getOrDefault(name, "").strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        int value = Integer.parseInt(text);
        if (value < 0) {
            throw new IllegalArgumentException("A filter cannot be negative.");
        }
        return Optional.of(value);
    }

    // As in the desktop app: the network connected, Kad with both unless the server is a static or a big one.
    private SearchRequest.Scope automaticScope(Optional<EngineStatus> known) {
        EngineStatus status =
                known.orElseGet(() -> this.calls.await(this.calls.engine().status()));
        return SearchRequest.automatic(
                        status.server().isPresent(),
                        status.kad().map(KadInfo::connected).orElse(false),
                        status.server(),
                        this.calls.await(this.calls.engine().servers()).size())
                .orElseThrow(() -> new IllegalArgumentException("Not connected to a server or to Kad."));
    }

    private SearchResult result(Map<String, String> form) {
        return this.searches
                .result(searchId(form), Hash16.parseHex(form.getOrDefault("hash", "")))
                .orElseThrow(() -> new IllegalArgumentException("That result is gone: search again."));
    }

    private static long searchId(Map<String, String> form) {
        return Long.parseLong(form.getOrDefault(SEARCH, ""));
    }

    private static Long parseId(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A column of the media details of the results. */
    private record MediaColumn(String title, Function<SearchResult.Media, String> value) {
        static final List<MediaColumn> ALL = List.of(
                new MediaColumn("Artist", SearchResult.Media::artist),
                new MediaColumn("Album", SearchResult.Media::album),
                new MediaColumn("Title", SearchResult.Media::title),
                new MediaColumn("Length", m -> m.length() == 0 ? "" : playingTime(m.length())),
                new MediaColumn("Bitrate", m -> m.bitrate() == 0 ? "" : m.bitrate() + " kbit/s"),
                new MediaColumn("Codec", SearchResult.Media::codec));

        boolean has(SearchResult r) {
            return !text(r).isEmpty();
        }

        String text(SearchResult r) {
            return this.value.apply(r.media());
        }

        private static String playingTime(int seconds) {
            int h = seconds / 3600;
            int m = seconds / 60 % 60;
            int s = seconds % 60;
            return h > 0
                    ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                    : String.format(Locale.ROOT, "%d:%02d", m, s);
        }
    }
}
