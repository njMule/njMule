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

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.path.NodePath;
import com.networknt.schema.path.PathType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Checks the answers and events of the API against the response schemas of its OpenAPI document. */
final class ApiContract {

    private static final String BASE = "https://njmule.invalid/test/openapi.json";
    private static final NodePath ERROR = pointer("components", "responses", "Error");

    private final ApiSpecification specification = ApiSpecification.load();
    private final JsonNode document = this.specification.document();
    private final Schema root;

    ApiContract() {
        this.root = ApiSpecification.registry(BASE, this.document).loadSchema(SchemaLocation.of(BASE), false);
    }

    ApiSpecification specification() {
        return this.specification;
    }

    /**
     * What in an answer does not follow the response the document gives for its status (or its default response); empty
     * when it follows it.
     */
    List<String> answerViolations(String method, String pathAndQuery, int status, String contentType, String body) {
        String path = pathAndQuery.contains("?") ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
        Optional<ApiSpecification.Route> route = this.specification.route(RemoteApi.segments(path));
        if (route.isEmpty() || route.get().operation(method).isEmpty()) {
            // No operation of the document: the answer is the document's error.
            return violations(new Located(node(ERROR), ERROR), status, contentType, body);
        }
        NodePath at = pointer("paths", route.get().path(), method.toLowerCase(Locale.ROOT), "responses");
        JsonNode responses = node(at);
        String code = responses.has(String.valueOf(status)) ? String.valueOf(status) : "default";
        if (!responses.has(code)) {
            return List.of("the document gives no answer " + status + " to " + method + " " + path);
        }
        return violations(resolve(responses.get(code), at.append(code)), status, contentType, body);
    }

    private List<String> violations(Located response, int status, String contentType, String body) {
        String type = contentType.split(";", -1)[0].strip();
        JsonNode content = response.node().path("content").path(type);
        if (content.isMissingNode()) {
            return List.of("the document gives no " + type + " answer " + status);
        }
        if (!type.equals("application/json")) {
            return List.of();
        }
        Schema schema = this.root.getSubSchema(
                response.at().append("content").append(type).append("schema"));
        return messages(schema.validate(JsonMapper.shared().readTree(body)));
    }

    /** What in an event of the stream does not follow the document's item schema and the data schema of its name. */
    List<String> eventViolations(String name, String id, String data) {
        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("event", name);
        item.put("id", id);
        item.put("data", data);
        NodePath events = pointer("components", "schemas", "EngineEvent");
        List<String> violations = messages(this.root.getSubSchema(events).validate(item));
        if (!violations.isEmpty()) {
            return violations;
        }
        JsonNode kinds = node(events.append("oneOf"));
        for (int i = 0; i < kinds.size(); i++) {
            Located kind = resolve(kinds.get(i), events.append("oneOf").append(i));
            if (kind.node()
                    .path("properties")
                    .path("event")
                    .path("const")
                    .asString()
                    .equals(name)) {
                Schema schema = this.root.getSubSchema(
                        kind.at().append("properties").append("data").append("contentSchema"));
                return messages(schema.validate(JsonMapper.shared().readTree(data)));
            }
        }
        return List.of("no event named " + name);
    }

    private static List<String> messages(List<Error> errors) {
        return errors.stream().map(Error::toString).toList();
    }

    private JsonNode node(NodePath at) {
        JsonNode node = this.document;
        for (int i = 0; i < at.getNameCount(); i++) {
            Object element = at.getElement(i);
            node = element instanceof Integer index ? node.path(index) : node.path(element.toString());
        }
        return node;
    }

    private Located resolve(JsonNode node, NodePath at) {
        if (!node.has("$ref")) {
            return new Located(node, at);
        }
        NodePath to = new NodePath(PathType.JSON_POINTER);
        for (String token : node.get("$ref").asString().substring(2).split("/", -1)) {
            to = to.append(token.replace("~1", "/").replace("~0", "~"));
        }
        return resolve(node(to), to);
    }

    private static NodePath pointer(String... tokens) {
        NodePath path = new NodePath(PathType.JSON_POINTER);
        for (String token : tokens) {
            path = path.append(token);
        }
        return path;
    }

    private record Located(JsonNode node, NodePath at) {}
}
