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
package org.njmule.core.remote;

import java.net.URI;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadNote;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.KnownClient;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.NotificationResult;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.Schedule;
import tools.jackson.databind.JsonNode;

/**
 * The answers and events of the remote-control API read back into the engine's records, as {@link ApiJson} wrote them.
 * What the API leaves out comes back empty: a search result's origin and AICH hash, the folder of a ready update.
 */
final class ApiReader {

    private ApiReader() {}

    static EngineStatus status(JsonNode n) {
        return new EngineStatus(
                nullable(n, "server").map(ApiReader::server),
                n.path("clientId").asLong(),
                n.path("lowId").asBoolean(),
                n.path("obfuscated").asBoolean(),
                nullable(n, "kad").map(ApiReader::kad),
                n.path("downloadBytesPerSecond").asLong(),
                n.path("uploadBytesPerSecond").asLong(),
                n.path("downloaded").asLong(),
                n.path("uploaded").asLong(),
                n.path("downloads").asInt(),
                n.path("uploads").asInt(),
                n.path("queued").asInt(),
                n.path("sharedFiles").asInt(),
                n.path("totalDownloaded").asLong(),
                n.path("totalUploaded").asLong(),
                n.path("sessions").asLong(),
                n.path("sessionSeconds").asLong(),
                n.path("totalSeconds").asLong(),
                text(n, "publicIp"),
                n.path("tcpPort").asInt(),
                n.path("relatedSearch").asBoolean(),
                optional(n, "missingInterface"),
                optional(n, "publicIpv6"),
                n.path("overheadUploaded").asLong(),
                n.path("overheadDownloaded").asLong());
    }

    static ServerInfo server(JsonNode n) {
        return new ServerInfo(
                text(n, "address"),
                text(n, "name"),
                text(n, "description"),
                n.path("users").asLong(),
                n.path("files").asLong(),
                n.path("maxUsers").asLong(),
                n.path("failed").asInt(),
                constant(ServerInfo.Priority.class, n, "priority"),
                n.path("ping").asInt(),
                n.path("softFiles").asLong(),
                n.path("hardFiles").asLong(),
                n.path("lowIdUsers").asLong(),
                text(n, "version"),
                n.path("obfuscation").asBoolean(),
                n.path("static").asBoolean());
    }

    static KadInfo kad(JsonNode n) {
        return new KadInfo(
                n.path("connected").asBoolean(),
                n.path("contacts").asInt(),
                n.path("verifiedContacts").asInt(),
                n.path("firewalled").asBoolean(),
                text(n, "id"),
                text(n, "publicIp"),
                n.path("udpPort").asInt(),
                n.path("udpFirewalled").asBoolean(),
                n.path("udpVerified").asBoolean(),
                constant(KadInfo.Buddy.class, n, "buddy"),
                n.path("users").asLong(),
                n.path("files").asLong());
    }

    /** A download; its parts are {@code without} when the answer has none. */
    static DownloadInfo download(JsonNode n, PartMap without) {
        return new DownloadInfo(
                hash(n, "hash"),
                text(n, "name"),
                n.path("size").asLong(),
                n.path("completed").asLong(),
                constant(DownloadInfo.Status.class, n, "status"),
                constant(DownloadPriority.class, n, "priority"),
                n.path("category").asInt(),
                n.path("sources").asInt(),
                n.path("transferring").asInt(),
                n.path("bytesPerSecond").asLong(),
                n.has("parts") ? parts(n.path("parts")) : without,
                n.path("previewFirst").asBoolean(),
                n.path("autoSwap").asBoolean());
    }

    static PartMap parts(JsonNode n) {
        return new PartMap(
                n.path("size").asLong(),
                spans(n.path("missing")),
                spans(n.path("requested")),
                list(n.path("sources"), JsonNode::asInt));
    }

    private static List<PartMap.Span> spans(JsonNode n) {
        return list(n, s -> new PartMap.Span(s.path(0).asLong(), s.path(1).asLong()));
    }

    static SourceInfo source(JsonNode n) {
        return new SourceInfo(
                hash(n, "file"),
                text(n, "fileName"),
                text(n, "address"),
                optional(n, "nick"),
                text(n, "software"),
                constant(SourceInfo.State.class, n, "state"),
                n.path("queueRank").asInt(),
                n.path("received").asLong(),
                n.path("parts").asInt(),
                n.path("partCount").asInt(),
                optional(n, "askedFor").map(Hash16::parseHex),
                text(n, "askedForName"),
                optional(n, "reportedName"),
                optional(n, "country"));
    }

    static UploadInfo upload(JsonNode n) {
        return new UploadInfo(
                text(n, "address"),
                hash(n, "userHash"),
                optional(n, "nick"),
                text(n, "software"),
                text(n, "file"),
                constant(UploadInfo.State.class, n, "state"),
                n.path("transferred").asLong(),
                n.path("bytesPerSecond").asLong(),
                n.path("waitingSeconds").asLong(),
                optional(n, "country"));
    }

    static SharedFileInfo shared(JsonNode n) {
        JsonNode t = n.path("statistics");
        return new SharedFileInfo(
                hash(n, "hash"),
                text(n, "name"),
                n.path("size").asLong(),
                Path.of(text(n, "path")),
                constant(UploadPriority.class, n, "priority"),
                new SharedFileInfo.Statistics(
                        t.path("requests").asLong(),
                        t.path("accepted").asLong(),
                        t.path("transferred").asLong(),
                        t.path("totalRequests").asLong(),
                        t.path("totalAccepted").asLong(),
                        t.path("totalTransferred").asLong()));
    }

    static SharedDirectory directory(JsonNode n) {
        return new SharedDirectory(
                Path.of(text(n, "path")), n.path("subdirectories").asBoolean());
    }

    static SearchResult result(JsonNode n) {
        JsonNode m = n.path("media");
        SearchResult.Media media = m.isObject()
                ? new SearchResult.Media(
                        text(m, "artist"),
                        text(m, "album"),
                        text(m, "title"),
                        m.path("length").asInt(),
                        m.path("bitrate").asInt(),
                        text(m, "codec"))
                : SearchResult.Media.NONE;
        return new SearchResult(
                hash(n, "hash"),
                text(n, "name"),
                n.path("size").asLong(),
                n.path("sources").asInt(),
                completeSources(n),
                optional(n, "type"),
                media,
                SearchResult.Origin.NONE,
                n.path("spamRating").asInt(),
                Optional.empty(),
                list(
                        n.path("names"),
                        each -> new SearchResult.Name(
                                text(each, "name"), each.path("sources").asInt(), completeSources(each))));
    }

    static SavedSearch savedSearch(JsonNode n) {
        return new SavedSearch(
                text(n, "title"),
                new SearchRequest(text(n, "query"), constant(SearchRequest.Scope.class, n, "scope")),
                list(n.path("results"), ApiReader::result));
    }

    static SpamMarks spamMarks(JsonNode n) {
        return new SpamMarks(
                Set.copyOf(list(n.path("spam"), h -> Hash16.parseHex(h.asString()))),
                Set.copyOf(list(n.path("notSpam"), h -> Hash16.parseHex(h.asString()))),
                Set.copyOf(list(n.path("names"), JsonNode::asString)));
    }

    static PeerInfo peer(JsonNode n) {
        return new PeerInfo(
                text(n, "address"),
                hash(n, "userHash"),
                optional(n, "nick"),
                text(n, "software"),
                n.path("obfuscated").asBoolean(),
                constant(PeerInfo.Identification.class, n, "identification"));
    }

    // Kad does not tell how many sources are complete.
    private static int completeSources(JsonNode n) {
        JsonNode count = n.path("completeSources");
        return count.isNumber() ? count.asInt() : -1;
    }

    static Category category(JsonNode n) {
        return new Category(
                n.path("id").asInt(),
                text(n, "name"),
                optional(n, "incoming").map(Path::of),
                text(n, "comment"),
                n.path("color").asInt(),
                constant(DownloadPriority.class, n, "priority"),
                text(n, "patterns"),
                n.path("regex").asBoolean());
    }

    static FileComment comment(JsonNode n) {
        return new FileComment(text(n, "text"), n.path("rating").asInt());
    }

    static PeerComment peerComment(JsonNode n) {
        return new PeerComment(text(n, "who"), text(n, "fileName"), comment(n));
    }

    static KadNote kadNote(JsonNode n) {
        return new KadNote(text(n, "who"), text(n, "fileName"), comment(n));
    }

    static Schedule schedule(JsonNode n) {
        return new Schedule(list(
                n.path("rules"),
                r -> new Schedule.Rule(
                        text(r, "name"),
                        r.path("enabled").asBoolean(),
                        days(r.path("days")),
                        LocalTime.parse(text(r, "start")),
                        LocalTime.parse(text(r, "end")),
                        optionalInt(r, "uploadKiBps"),
                        optionalInt(r, "downloadKiBps"),
                        optionalInt(r, "maxConnections"),
                        optionalInt(r, "maxSourcesPerFile"),
                        optionalInt(r, "stopCategory"),
                        optionalInt(r, "resumeCategory"))));
    }

    private static Set<DayOfWeek> days(JsonNode n) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        n.values().forEach(d -> days.add(DayOfWeek.valueOf(d.asString().toUpperCase(Locale.ROOT))));
        return days;
    }

    static ImportReport importReport(JsonNode n) {
        return new ImportReport(
                n.path("dryRun").asBoolean(),
                list(
                        n.path("items"),
                        i -> new ImportReport.Item(
                                text(i, "file"),
                                constant(ImportReport.Outcome.class, i, "outcome"),
                                list(
                                        i.path("phrases"),
                                        p -> new ImportReport.Phrase(
                                                text(p, "key"), list(p.path("args"), ApiReader::argument))))),
                n.path("startAtLogin").asBoolean());
    }

    private static Object argument(JsonNode n) {
        if (n.isNumber()) {
            return n.canConvertToInt() ? (Object) n.asInt() : (Object) n.asLong();
        }
        return n.asString();
    }

    static PortTest portTest(JsonNode n) {
        return new PortTest(
                n.path("tcpPort").asInt(),
                n.path("udpPort").asInt(),
                constant(PortTest.Forwarding.class, n, "forwarding"),
                text(n, "router"),
                constant(PortTest.Reach.class, n, "tcp"),
                constant(PortTest.Reach.class, n, "udp"),
                n.path("done").asBoolean());
    }

    static Friend friend(JsonNode n) {
        return new Friend(
                hash(n, "userHash"), text(n, "name"), optional(n, "address").map(ServerAddress::parse));
    }

    static BanInfo ban(JsonNode n) {
        return new BanInfo(text(n, "address"), n.path("secondsLeft").asLong());
    }

    static KadContactInfo kadContact(JsonNode n) {
        return new KadContactInfo(
                text(n, "id"),
                text(n, "address"),
                n.path("type").asInt(),
                text(n, "distance"),
                n.path("version").asInt(),
                n.path("verified").asBoolean());
    }

    static KadSearchInfo kadSearch(JsonNode n) {
        return new KadSearchInfo(
                text(n, "target"),
                constant(KadSearchInfo.Kind.class, n, "kind"),
                n.path("answers").asInt());
    }

    static LogLine logLine(JsonNode n) {
        return new LogLine(
                Instant.parse(text(n, "time")), constant(LogLine.Level.class, n, "level"), text(n, "message"));
    }

    static NewVersion newVersion(JsonNode n) {
        return new NewVersion(
                text(n, "version"),
                Instant.parse(text(n, "published")),
                n.path("critical").asBoolean(),
                text(n, "notes"),
                URI.create(text(n, "page")),
                nullable(n, "download")
                        .map(d -> new NewVersion.Download(
                                URI.create(text(d, "url")), d.path("size").asLong(), text(d, "sha256"))));
    }

    /** The state of updates; a ready update has no folder on this computer, only its name. */
    static UpdateStatus update(JsonNode n) {
        return new UpdateStatus(
                text(n, "current"),
                constant(InstallKind.class, n, "installation"),
                constant(UpdateStatus.Installer.class, n, "installer"),
                n.path("checking").asBoolean(),
                optional(n, "lastCheck").map(Instant::parse),
                optional(n, "failure"),
                nullable(n, "available").map(ApiReader::newVersion),
                optional(n, "skipped"),
                nullable(n, "download")
                        .map(d -> new UpdateStatus.Progress(
                                d.path("received").asLong(), d.path("size").asLong())),
                n.path("ready").asBoolean() ? Optional.of(Path.of("remote")) : Optional.empty(),
                constant(UpdateStatus.Install.class, n, "install"));
    }

    /**
     * An event of the stream, its download's parts {@code without} as the event has none; empty for those that need
     * another call to tell all (the preferences, a CAPTCHA's picture) and for names this version does not know.
     */
    static Optional<EngineEvent> event(String name, JsonNode n, PartMap without) {
        EngineEvent event = switch (name) {
            case "started" -> new EngineEvent.Started(text(n, "version"));
            case "stopping" -> new EngineEvent.Stopping();
            case "log" -> new EngineEvent.Log(logLine(n));
            case "server.connecting" -> new EngineEvent.ServerConnecting(server(n.path("server")));
            case "server.connected" ->
                new EngineEvent.ServerConnected(
                        server(n.path("server")),
                        n.path("clientId").asLong(),
                        n.path("lowId").asBoolean(),
                        n.path("obfuscated").asBoolean());
            case "server.disconnected" ->
                new EngineEvent.ServerDisconnected(nullable(n, "server").map(ApiReader::server), text(n, "reason"));
            case "server.message" -> new EngineEvent.ServerMessage(server(n.path("server")), text(n, "text"));
            case "server.updated" -> new EngineEvent.ServerUpdated(server(n.path("server")));
            case "servers.changed" ->
                new EngineEvent.ServersChanged(n.path("count").asInt());
            case "search.results" ->
                new EngineEvent.SearchResults(
                        new SearchId(n.path("search").asLong()), list(n.path("results"), ApiReader::result));
            case "search.ended" ->
                new EngineEvent.SearchEnded(
                        new SearchId(n.path("search").asLong()),
                        n.path("results").asInt());
            case "download.added" -> new EngineEvent.DownloadAdded(download(n.path("download"), without));
            case "download.progress" -> new EngineEvent.DownloadProgress(download(n.path("download"), without));
            case "download.completed" ->
                new EngineEvent.DownloadCompleted(download(n.path("download"), without), Path.of(text(n, "file")));
            case "download.failed" ->
                new EngineEvent.DownloadFailed(download(n.path("download"), without), text(n, "reason"));
            case "download.removed" -> new EngineEvent.DownloadRemoved(hash(n, "hash"));
            case "disk.space" ->
                new EngineEvent.DiskSpace(
                        Path.of(text(n, "directory")), n.path("enough").asBoolean());
            case "message.received" ->
                new EngineEvent.MessageReceived(
                        text(n, "address"), hash(n, "userHash"), optional(n, "nick"), text(n, "text"));
            case "captcha.answered" ->
                new EngineEvent.CaptchaAnswered(
                        text(n, "address"), n.path("solved").asBoolean());
            case "message.failed" -> new EngineEvent.MessageFailed(text(n, "address"), text(n, "reason"));
            case "update.status" -> new EngineEvent.UpdateStatusChanged(update(n.path("status")));
            case "update.available" -> new EngineEvent.UpdateAvailable(newVersion(n.path("version")));
            case "update.ready" -> new EngineEvent.UpdateReady(newVersion(n.path("version")));
            case "update.restart" -> new EngineEvent.UpdateRestart(text(n, "version"));
            default -> null;
        };
        return Optional.ofNullable(event);
    }

    static KnownClient knownClient(JsonNode n) {
        return new KnownClient(
                hash(n, "userHash"),
                text(n, "nick"),
                text(n, "software"),
                n.path("firstSeen").asLong(),
                n.path("lastSeen").asLong(),
                optional(n, "country"),
                n.path("uploaded").asLong(),
                n.path("downloaded").asLong());
    }

    static NotificationResult notificationResult(JsonNode n) {
        return new NotificationResult(
                n.path("channel").asString(),
                n.path("sent").asBoolean()
                        ? Optional.empty()
                        : Optional.of(n.path("error").asString("")));
    }

    static <T> List<T> list(JsonNode n, Function<JsonNode, T> each) {
        List<T> items = new ArrayList<>();
        n.values().forEach(item -> items.add(each.apply(item)));
        return List.copyOf(items);
    }

    static Hash16 hash(JsonNode n, String name) {
        return Hash16.parseHex(text(n, name));
    }

    private static String text(JsonNode n, String name) {
        return n.path(name).asString("");
    }

    private static Optional<String> optional(JsonNode n, String name) {
        JsonNode value = n.path(name);
        return value.isString() ? Optional.of(value.asString()) : Optional.empty();
    }

    private static Optional<JsonNode> nullable(JsonNode n, String name) {
        JsonNode value = n.path(name);
        return value.isObject() ? Optional.of(value) : Optional.empty();
    }

    private static OptionalInt optionalInt(JsonNode n, String name) {
        JsonNode value = n.path(name);
        return value.isNumber() ? OptionalInt.of(value.asInt()) : OptionalInt.empty();
    }

    private static <E extends Enum<E>> E constant(Class<E> type, JsonNode n, String name) {
        return Enum.valueOf(type, text(n, name).toUpperCase(Locale.ROOT));
    }
}
