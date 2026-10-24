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

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadInfo;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

/**
 * The JSON answers of the remote-control API: the engine's records written field by field, as the schemas of the
 * OpenAPI document describe them. Enum values are lower case.
 */
final class ApiJson {

    private static final JsonFactory FACTORY = new JsonFactory();

    private ApiJson() {}

    static byte[] write(Consumer<JsonGenerator> body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        try (JsonGenerator g = FACTORY.createGenerator(ObjectWriteContext.empty(), out, JsonEncoding.UTF8)) {
            body.accept(g);
        }
        return out.toByteArray();
    }

    static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    static void status(JsonGenerator g, EngineStatus s) {
        g.writeStartObject();
        g.writeName("server");
        if (s.server().isPresent()) {
            server(g, s.server().get());
        } else {
            g.writeNull();
        }
        g.writeNumberProperty("clientId", s.clientId());
        g.writeBooleanProperty("lowId", s.lowId());
        g.writeBooleanProperty("obfuscated", s.obfuscated());
        g.writeName("kad");
        if (s.kad().isPresent()) {
            kad(g, s.kad().get());
        } else {
            g.writeNull();
        }
        g.writeNumberProperty("downloadBytesPerSecond", s.downloadBytesPerSecond());
        g.writeNumberProperty("uploadBytesPerSecond", s.uploadBytesPerSecond());
        g.writeNumberProperty("downloaded", s.downloaded());
        g.writeNumberProperty("uploaded", s.uploaded());
        g.writeNumberProperty("downloads", s.downloads());
        g.writeNumberProperty("uploads", s.uploads());
        g.writeNumberProperty("queued", s.queued());
        g.writeNumberProperty("sharedFiles", s.sharedFiles());
        g.writeNumberProperty("totalDownloaded", s.totalDownloaded());
        g.writeNumberProperty("totalUploaded", s.totalUploaded());
        g.writeNumberProperty("sessions", s.sessions());
        g.writeNumberProperty("sessionSeconds", s.sessionSeconds());
        g.writeNumberProperty("totalSeconds", s.totalSeconds());
        g.writeStringProperty("publicIp", s.publicIp());
        if (s.publicIpv6().isPresent()) {
            g.writeStringProperty("publicIpv6", s.publicIpv6().get());
        }
        g.writeNumberProperty("tcpPort", s.tcpPort());
        // Present while the network interface the preferences bind to is gone, which keeps njMule offline.
        if (s.missingInterface().isPresent()) {
            g.writeStringProperty("missingInterface", s.missingInterface().get());
        }
        g.writeEndObject();
    }

    static void server(JsonGenerator g, ServerInfo s) {
        g.writeStartObject();
        g.writeStringProperty("address", s.address());
        g.writeStringProperty("name", s.name());
        g.writeStringProperty("description", s.description());
        g.writeNumberProperty("users", s.users());
        g.writeNumberProperty("files", s.files());
        g.writeNumberProperty("maxUsers", s.maxUsers());
        g.writeNumberProperty("lowIdUsers", s.lowIdUsers());
        g.writeNumberProperty("softFiles", s.softFiles());
        g.writeNumberProperty("hardFiles", s.hardFiles());
        g.writeNumberProperty("failed", s.failed());
        g.writeStringProperty("priority", name(s.priority()));
        g.writeNumberProperty("ping", s.ping());
        g.writeStringProperty("version", s.version());
        g.writeBooleanProperty("obfuscation", s.supportsObfuscation());
        g.writeBooleanProperty("static", s.isStatic());
        g.writeEndObject();
    }

    static void update(JsonGenerator g, UpdateStatus s) {
        g.writeStartObject();
        g.writeStringProperty("state", name(s.phase()));
        g.writeStringProperty("current", s.current());
        g.writeStringProperty("installation", name(s.installation()));
        g.writeStringProperty("installer", name(s.installer()));
        g.writeBooleanProperty("canInstall", s.canInstall());
        g.writeBooleanProperty("checking", s.checking());
        optional(g, "lastCheck", s.lastCheck().map(Object::toString));
        optional(g, "failure", s.failure());
        g.writeName("available");
        if (s.available().isPresent()) {
            newVersion(g, s.available().get());
        } else {
            g.writeNull();
        }
        optional(g, "skipped", s.skipped());
        g.writeName("download");
        if (s.download().isPresent()) {
            g.writeStartObject();
            g.writeNumberProperty("received", s.download().get().received());
            g.writeNumberProperty("size", s.download().get().size());
            g.writeEndObject();
        } else {
            g.writeNull();
        }
        g.writeBooleanProperty("ready", s.ready().isPresent());
        g.writeStringProperty("install", name(s.install()));
        g.writeEndObject();
    }

    static void newVersion(JsonGenerator g, NewVersion v) {
        g.writeStartObject();
        g.writeStringProperty("version", v.version());
        g.writeStringProperty("published", v.published().toString());
        g.writeBooleanProperty("critical", v.critical());
        g.writeStringProperty("notes", v.notes());
        g.writeStringProperty("page", v.page().toString());
        g.writeName("download");
        if (v.download().isPresent()) {
            NewVersion.Download d = v.download().get();
            g.writeStartObject();
            g.writeStringProperty("url", d.uri().toString());
            g.writeNumberProperty("size", d.size());
            g.writeStringProperty("sha256", d.sha256());
            g.writeEndObject();
        } else {
            g.writeNull();
        }
        g.writeEndObject();
    }

    static void kad(JsonGenerator g, KadInfo k) {
        g.writeStartObject();
        g.writeBooleanProperty("connected", k.connected());
        g.writeBooleanProperty("firewalled", k.firewalled());
        g.writeNumberProperty("contacts", k.contacts());
        g.writeNumberProperty("verifiedContacts", k.verifiedContacts());
        g.writeStringProperty("id", k.id());
        g.writeStringProperty("publicIp", k.publicIp());
        g.writeNumberProperty("udpPort", k.udpPort());
        g.writeBooleanProperty("udpFirewalled", k.udpFirewalled());
        g.writeBooleanProperty("udpVerified", k.udpVerified());
        g.writeStringProperty("buddy", name(k.buddy()));
        g.writeEndObject();
    }

    static void download(JsonGenerator g, DownloadInfo d) {
        g.writeStartObject();
        g.writeStringProperty("hash", d.hash().toHex());
        g.writeStringProperty("name", d.name());
        g.writeNumberProperty("size", d.size());
        g.writeNumberProperty("completed", d.completed());
        g.writeStringProperty("status", name(d.status()));
        g.writeStringProperty("priority", name(d.priority()));
        g.writeNumberProperty("category", d.category());
        g.writeNumberProperty("sources", d.sources());
        g.writeNumberProperty("transferring", d.transferring());
        g.writeNumberProperty("bytesPerSecond", d.bytesPerSecond());
        g.writeBooleanProperty("previewFirst", d.previewFirst());
        g.writeStringProperty("link", d.link().toUri());
        g.writeEndObject();
    }

    static void source(JsonGenerator g, SourceInfo s) {
        g.writeStartObject();
        g.writeStringProperty("file", s.file().toHex());
        g.writeStringProperty("fileName", s.fileName());
        g.writeStringProperty("address", s.address());
        optional(g, "nick", s.nick());
        g.writeStringProperty("software", s.software());
        g.writeStringProperty("state", name(s.state()));
        g.writeNumberProperty("queueRank", s.queueRank());
        g.writeNumberProperty("received", s.received());
        g.writeNumberProperty("parts", s.parts());
        g.writeNumberProperty("partCount", s.partCount());
        optional(g, "reportedName", s.reportedName());
        g.writeEndObject();
    }

    static void upload(JsonGenerator g, UploadInfo u) {
        g.writeStartObject();
        g.writeStringProperty("address", u.address());
        g.writeStringProperty("userHash", u.userHash().toHex());
        optional(g, "nick", u.nick());
        g.writeStringProperty("software", u.software());
        g.writeStringProperty("file", u.file());
        g.writeStringProperty("state", name(u.state()));
        g.writeNumberProperty("transferred", u.transferred());
        g.writeNumberProperty("bytesPerSecond", u.bytesPerSecond());
        g.writeNumberProperty("waitingSeconds", u.waitingSeconds());
        g.writeEndObject();
    }

    static void shared(JsonGenerator g, SharedFileInfo f) {
        g.writeStartObject();
        g.writeStringProperty("hash", f.hash().toHex());
        g.writeStringProperty("name", f.name());
        g.writeNumberProperty("size", f.size());
        g.writeStringProperty("path", f.path().toString());
        g.writeStringProperty("priority", name(f.priority()));
        g.writeStringProperty("link", f.link().toUri());
        g.writeEndObject();
    }

    static void directory(JsonGenerator g, SharedDirectory d) {
        g.writeStartObject();
        g.writeStringProperty("path", d.path().toString());
        g.writeBooleanProperty("subdirectories", d.subdirectories());
        g.writeEndObject();
    }

    static void result(JsonGenerator g, SearchResult r) {
        g.writeStartObject();
        g.writeStringProperty("hash", r.hash().toHex());
        g.writeStringProperty("name", r.name());
        g.writeNumberProperty("size", r.size());
        g.writeNumberProperty("sources", r.sources());
        completeSources(g, r.completeSources());
        optional(g, "type", r.type());
        g.writeNumberProperty("spamRating", r.spamRating());
        g.writeBooleanProperty("spam", r.isSpam());
        // The names the file was found under, when more than one.
        g.writeArrayPropertyStart("names");
        for (SearchResult.Name n : r.names()) {
            g.writeStartObject();
            g.writeStringProperty("name", n.name());
            g.writeNumberProperty("sources", n.sources());
            completeSources(g, n.completeSources());
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeEndObject();
    }

    private static void completeSources(JsonGenerator g, int count) {
        if (count < 0) {
            // Kad does not tell it.
            g.writeNullProperty("completeSources");
        } else {
            g.writeNumberProperty("completeSources", count);
        }
    }

    static void category(JsonGenerator g, Category c) {
        g.writeStartObject();
        g.writeNumberProperty("id", c.id());
        g.writeStringProperty("name", c.name());
        optional(g, "incoming", c.incoming().map(Object::toString));
        g.writeStringProperty("comment", c.comment());
        g.writeStringProperty("priority", name(c.priority()));
        g.writeNumberProperty("color", c.color());
        g.writeStringProperty("patterns", c.patterns());
        g.writeBooleanProperty("regex", c.regex());
        g.writeEndObject();
    }

    static void portTest(JsonGenerator g, PortTest t) {
        g.writeStartObject();
        g.writeNumberProperty("tcpPort", t.tcpPort());
        g.writeNumberProperty("udpPort", t.udpPort());
        g.writeStringProperty("forwarding", name(t.forwarding()));
        g.writeStringProperty("router", t.router());
        g.writeStringProperty("tcp", name(t.tcp()));
        g.writeStringProperty("udp", name(t.udp()));
        g.writeBooleanProperty("done", t.done());
        g.writeEndObject();
    }

    static void chat(JsonGenerator g, Conversations.Chat c) {
        g.writeStartObject();
        g.writeStringProperty("address", c.address());
        optional(g, "userHash", c.userHash().map(Hash16::toHex));
        optional(g, "nick", c.nick());
        optional(g, "captcha", c.captcha().map(p -> Base64.getEncoder().encodeToString(p.image())));
        g.writeArrayPropertyStart("lines");
        for (Conversations.Line l : c.lines()) {
            g.writeStartObject();
            g.writeNumberProperty("sequence", l.sequence());
            g.writeStringProperty("time", l.time().toString());
            g.writeStringProperty("author", name(l.author()));
            g.writeStringProperty("text", l.text());
            optional(g, "notice", l.notice().map(ApiJson::name));
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeEndObject();
    }

    static void friend(JsonGenerator g, Friend f) {
        g.writeStartObject();
        g.writeStringProperty("userHash", f.userHash().toHex());
        g.writeStringProperty("name", f.name());
        optional(g, "address", f.address().map(a -> a.host() + ":" + a.port()));
        g.writeEndObject();
    }

    static void ban(JsonGenerator g, BanInfo b) {
        g.writeStartObject();
        g.writeStringProperty("address", b.address());
        g.writeNumberProperty("secondsLeft", b.secondsLeft());
        g.writeEndObject();
    }

    static void kadContact(JsonGenerator g, KadContactInfo c) {
        g.writeStartObject();
        g.writeStringProperty("id", c.id());
        g.writeStringProperty("address", c.address());
        g.writeNumberProperty("type", c.type());
        g.writeStringProperty("distance", c.distance());
        g.writeNumberProperty("version", c.version());
        g.writeBooleanProperty("verified", c.verified());
        g.writeEndObject();
    }

    static void kadSearch(JsonGenerator g, KadSearchInfo s) {
        g.writeStartObject();
        g.writeStringProperty("target", s.target());
        g.writeStringProperty("kind", name(s.kind()));
        g.writeNumberProperty("answers", s.answers());
        g.writeEndObject();
    }

    static void log(JsonGenerator g, long sequence, LogLine line) {
        g.writeStartObject();
        g.writeNumberProperty("sequence", sequence);
        g.writeStringProperty("time", line.time().toString());
        g.writeStringProperty("level", name(line.level()));
        g.writeStringProperty("message", line.message());
        g.writeEndObject();
    }

    static <T> void array(JsonGenerator g, List<T> items, Writer<T> each) {
        g.writeStartArray();
        for (T item : items) {
            each.write(g, item);
        }
        g.writeEndArray();
    }

    @FunctionalInterface
    interface Writer<T> {
        void write(JsonGenerator g, T value);
    }

    /** The name of an event in the stream, as "download.progress". */
    static String eventName(EngineEvent event) {
        return switch (event) {
            case EngineEvent.Started e -> "started";
            case EngineEvent.Stopping e -> "stopping";
            case EngineEvent.PreferencesChanged e -> "preferences.changed";
            case EngineEvent.Log e -> "log";
            case EngineEvent.ServerConnecting e -> "server.connecting";
            case EngineEvent.ServerConnected e -> "server.connected";
            case EngineEvent.ServerDisconnected e -> "server.disconnected";
            case EngineEvent.ServerMessage e -> "server.message";
            case EngineEvent.ServerUpdated e -> "server.updated";
            case EngineEvent.ServersChanged e -> "servers.changed";
            case EngineEvent.SearchResults e -> "search.results";
            case EngineEvent.SearchEnded e -> "search.ended";
            case EngineEvent.DownloadAdded e -> "download.added";
            case EngineEvent.DownloadProgress e -> "download.progress";
            case EngineEvent.DownloadCompleted e -> "download.completed";
            case EngineEvent.DownloadFailed e -> "download.failed";
            case EngineEvent.DownloadRemoved e -> "download.removed";
            case EngineEvent.DiskSpace e -> "disk.space";
            case EngineEvent.MessageReceived e -> "message.received";
            case EngineEvent.CaptchaRequested e -> "captcha.requested";
            case EngineEvent.CaptchaAnswered e -> "captcha.answered";
            case EngineEvent.MessageFailed e -> "message.failed";
            case EngineEvent.UpdateStatusChanged e -> "update.status";
            case EngineEvent.UpdateAvailable e -> "update.available";
            case EngineEvent.UpdateReady e -> "update.ready";
            case EngineEvent.UpdateRestart e -> "update.restart";
        };
    }

    /** The data of an event; settings and pictures stay out, the API has their own calls. */
    static void event(JsonGenerator g, EngineEvent event) {
        g.writeStartObject();
        switch (event) {
            case EngineEvent.Started e -> g.writeStringProperty("version", e.version());
            case EngineEvent.Stopping e -> {
                // Nothing more to say.
            }
            case EngineEvent.PreferencesChanged e -> {
                // Read them with GET limits or the settings calls.
            }
            case EngineEvent.Log e -> {
                g.writeStringProperty("time", e.line().time().toString());
                g.writeStringProperty("level", name(e.line().level()));
                g.writeStringProperty("message", e.line().message());
            }
            case EngineEvent.ServerConnecting e -> serverProperty(g, e.server());
            case EngineEvent.ServerConnected e -> {
                serverProperty(g, e.server());
                g.writeNumberProperty("clientId", e.clientId());
                g.writeBooleanProperty("lowId", e.lowId());
                g.writeBooleanProperty("obfuscated", e.obfuscated());
            }
            case EngineEvent.ServerDisconnected e -> {
                if (e.server().isPresent()) {
                    serverProperty(g, e.server().get());
                }
                g.writeStringProperty("reason", e.reason());
            }
            case EngineEvent.ServerMessage e -> {
                serverProperty(g, e.server());
                g.writeStringProperty("text", e.text());
            }
            case EngineEvent.ServerUpdated e -> serverProperty(g, e.server());
            case EngineEvent.ServersChanged e -> g.writeNumberProperty("count", e.count());
            case EngineEvent.SearchResults e -> {
                g.writeNumberProperty("search", e.search().value());
                g.writeName("results");
                array(g, e.results(), ApiJson::result);
            }
            case EngineEvent.SearchEnded e -> {
                g.writeNumberProperty("search", e.search().value());
                g.writeNumberProperty("results", e.results());
            }
            case EngineEvent.DownloadAdded e -> downloadProperty(g, e.download());
            case EngineEvent.DownloadProgress e -> downloadProperty(g, e.download());
            case EngineEvent.DownloadCompleted e -> {
                downloadProperty(g, e.download());
                g.writeStringProperty("file", e.file().toString());
            }
            case EngineEvent.DownloadFailed e -> {
                downloadProperty(g, e.download());
                g.writeStringProperty("reason", e.reason());
            }
            case EngineEvent.DownloadRemoved e ->
                g.writeStringProperty("hash", e.hash().toHex());
            case EngineEvent.DiskSpace e -> {
                g.writeStringProperty("directory", e.directory().toString());
                g.writeBooleanProperty("enough", e.enough());
            }
            case EngineEvent.MessageReceived e -> {
                g.writeStringProperty("address", e.address());
                g.writeStringProperty("userHash", e.userHash().toHex());
                optional(g, "nick", e.nick());
                g.writeStringProperty("text", e.text());
            }
            case EngineEvent.CaptchaRequested e -> {
                g.writeStringProperty("address", e.address());
                g.writeStringProperty("userHash", e.userHash().toHex());
            }
            case EngineEvent.CaptchaAnswered e -> {
                g.writeStringProperty("address", e.address());
                g.writeBooleanProperty("solved", e.solved());
            }
            case EngineEvent.MessageFailed e -> {
                g.writeStringProperty("address", e.address());
                g.writeStringProperty("reason", e.reason());
            }
            case EngineEvent.UpdateStatusChanged e -> {
                g.writeName("status");
                update(g, e.status());
            }
            case EngineEvent.UpdateAvailable e -> {
                g.writeName("version");
                newVersion(g, e.version());
            }
            case EngineEvent.UpdateReady e -> {
                g.writeName("version");
                newVersion(g, e.version());
            }
            case EngineEvent.UpdateRestart e -> g.writeStringProperty("version", e.version());
        }
        g.writeEndObject();
    }

    private static void serverProperty(JsonGenerator g, ServerInfo s) {
        g.writeName("server");
        server(g, s);
    }

    private static void downloadProperty(JsonGenerator g, DownloadInfo d) {
        g.writeName("download");
        download(g, d);
    }

    private static void optional(JsonGenerator g, String name, Optional<String> value) {
        if (value.isPresent()) {
            g.writeStringProperty(name, value.get());
        } else {
            g.writeNullProperty(name);
        }
    }
}
