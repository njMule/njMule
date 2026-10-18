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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Friend;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UploadInfo;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/**
 * The JSON of the remote-control API: the engine's records written field by field, and request bodies read as flat
 * objects of strings, numbers, booleans and lists of strings. Enum values are lower case.
 */
final class ApiJson {

    /** The largest request body taken. */
    static final int MAX_BODY = 1024 * 1024;

    private static final int MAX_STRING = 256 * 1024;
    private static final int MAX_FIELDS = 64;

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_BODY)
                    .maxNestingDepth(3)
                    .maxStringLength(MAX_STRING)
                    .maxNameLength(64)
                    .maxNumberLength(20)
                    .build())
            .build();

    private ApiJson() {}

    /** A request body that is not a flat JSON object of the kinds taken. */
    static final class BadBody extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        BadBody(String message) {
            super(message);
        }
    }

    static byte[] write(Consumer<JsonGenerator> body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        try (JsonGenerator g = FACTORY.createGenerator(ObjectWriteContext.empty(), out, JsonEncoding.UTF8)) {
            body.accept(g);
        }
        return out.toByteArray();
    }

    /** The properties of a JSON object: String, Long, Boolean or List of String values; null when absent. */
    static Map<String, Object> read(byte[] body) {
        if (body.length == 0) {
            return Map.of();
        }
        Map<String, Object> fields = new HashMap<>();
        try (JsonParser p = FACTORY.createParser(ObjectReadContext.empty(), body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new BadBody("The body must be a JSON object.");
            }
            for (JsonToken t = p.nextToken(); t != JsonToken.END_OBJECT; t = p.nextToken()) {
                String name = p.currentName();
                if (fields.size() >= MAX_FIELDS) {
                    throw new BadBody("Too many fields.");
                }
                fields.put(name, value(p, p.nextToken()));
            }
            if (p.nextToken() != null) {
                throw new BadBody("Text after the JSON object.");
            }
        } catch (JacksonException e) {
            throw new BadBody("Malformed JSON: " + e.getOriginalMessage());
        }
        return fields;
    }

    private static Object value(JsonParser p, JsonToken t) {
        return switch (t) {
            case VALUE_STRING -> p.getString();
            case VALUE_NUMBER_INT -> p.getLongValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> Optional.empty();
            case START_ARRAY -> {
                List<String> items = new ArrayList<>();
                for (JsonToken i = p.nextToken(); i != JsonToken.END_ARRAY; i = p.nextToken()) {
                    if (i != JsonToken.VALUE_STRING) {
                        throw new BadBody("Lists hold strings only.");
                    }
                    items.add(p.getString());
                }
                yield List.copyOf(items);
            }
            default -> throw new BadBody("Unexpected " + t + " in the body.");
        };
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
