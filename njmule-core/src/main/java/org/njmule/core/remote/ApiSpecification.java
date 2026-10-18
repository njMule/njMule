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
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.path.NodePath;
import com.networknt.schema.path.PathType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The OpenAPI document of the remote-control API, the source of its routes: a request is matched to an operation of the
 * document's paths, and its parameters and body are checked against the document's schemas before the operation runs.
 */
final class ApiSpecification {

    static final String RESOURCE = "openapi.yaml";

    /** The largest request body taken. */
    static final int MAX_BODY = 1024 * 1024;

    // The document's own address, against which its references resolve; nothing is fetched from it.
    private static final String BASE = "https://njmule.invalid/api/v1/openapi.json";

    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace", "query");
    private static final int MAX_ERRORS = 3;

    private static final JsonMapper BODIES = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxDocumentLength(MAX_BODY)
                            .maxNestingDepth(8)
                            .maxStringLength(MAX_BODY)
                            .maxNameLength(64)
                            .maxNumberLength(20)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final byte[] yaml;
    private final JsonNode document;
    private final List<Route> routes;

    private ApiSpecification(byte[] yaml, JsonNode document, List<Route> routes) {
        this.yaml = yaml;
        this.document = document;
        this.routes = routes;
    }

    /** The document shipped with njMule. */
    static ApiSpecification load() {
        try (InputStream in = ApiSpecification.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            return parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ApiSpecification parse(byte[] yaml) {
        JsonNode document = YAMLMapper.shared().readTree(yaml);
        // Messages in English, whatever the system's language: they go to the caller.
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                Dialects.getOpenApi31(),
                b -> b.schemas(Map.of(BASE, document.toString()))
                        .schemaRegistryConfig(SchemaRegistryConfig.builder()
                                .locale(Locale.ENGLISH)
                                .build()));
        Schema root = registry.loadSchema(SchemaLocation.of(BASE), false);
        Document doc = new Document(document, root);
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            routes.add(doc.route(path.getKey(), path.getValue()));
        }
        routes.sort(Route.CONCRETE_FIRST);
        Set<String> ids = new HashSet<>();
        for (Route r : routes) {
            for (Operation o : r.operations().values()) {
                if (!ids.add(o.id())) {
                    throw Document.invalid("operationId " + o.id() + " is used twice");
                }
            }
        }
        return new ApiSpecification(yaml.clone(), document, List.copyOf(routes));
    }

    /** The document as it is shipped. */
    byte[] yaml() {
        return this.yaml.clone();
    }

    JsonNode document() {
        return this.document;
    }

    /** The operations of the document by their ids. */
    Map<String, Operation> operations() {
        Map<String, Operation> all = new TreeMap<>();
        for (Route r : this.routes) {
            for (Operation o : r.operations().values()) {
                all.put(o.id(), o);
            }
        }
        return all;
    }

    /** The path of the document that the segments of a request's path match, concrete paths before templated ones. */
    Optional<Route> route(List<String> segments) {
        return this.routes.stream().filter(r -> r.matches(segments)).findFirst();
    }

    /** A path of the document and its operations by HTTP method. */
    record Route(String path, List<String> template, Map<String, Operation> operations) {

        // OpenAPI matches concrete paths before templated ones: /servers/connect before /servers/{address}.
        static final Comparator<Route> CONCRETE_FIRST = (a, b) -> {
            for (int i = 0; i < Math.min(a.template.size(), b.template.size()); i++) {
                int byKind = Boolean.compare(variable(a.template.get(i)), variable(b.template.get(i)));
                if (byKind != 0) {
                    return byKind;
                }
            }
            return Integer.compare(a.template.size(), b.template.size());
        };

        boolean matches(List<String> segments) {
            if (segments.size() != this.template.size()) {
                return false;
            }
            for (int i = 0; i < segments.size(); i++) {
                String t = this.template.get(i);
                if (!variable(t) && !t.equals(segments.get(i))) {
                    return false;
                }
            }
            return true;
        }

        /** The values of the path's variables in the segments of a request it matches. */
        Map<String, String> values(List<String> segments) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < segments.size(); i++) {
                String t = this.template.get(i);
                if (variable(t)) {
                    values.put(t.substring(1, t.length() - 1), segments.get(i));
                }
            }
            return values;
        }

        Optional<Operation> operation(String method) {
            return Optional.ofNullable(this.operations.get(method.toLowerCase(Locale.ROOT)));
        }

        /** The methods of the path, for an Allow header. */
        String allowed() {
            return this.operations.keySet().stream()
                    .map(m -> m.toUpperCase(Locale.ROOT))
                    .sorted()
                    .collect(Collectors.joining(", "));
        }

        private static boolean variable(String segment) {
            return segment.startsWith("{") && segment.endsWith("}");
        }
    }

    /** An operation of the document: what it takes and how to check it. */
    record Operation(String id, String method, String path, List<Parameter> parameters, Optional<Body> body) {

        /**
         * The parameters of a request, read as their schemas type them and checked against them; an optional one left
         * out is absent.
         */
        Map<String, JsonNode> arguments(Map<String, String> pathValues, Map<String, String> query) {
            Map<String, JsonNode> arguments = new LinkedHashMap<>();
            for (Parameter p : this.parameters) {
                String given = p.in().equals("path") ? pathValues.get(p.name()) : query.get(p.name());
                if (given == null) {
                    if (p.required()) {
                        throw new RemoteApi.Failure(400, "Missing parameter: " + p.name());
                    }
                    continue;
                }
                JsonNode value = p.read(given);
                check(p.schema(), value, "Parameter " + p.name());
                arguments.put(p.name(), value);
            }
            return arguments;
        }

        /** The body of a request, parsed and checked against its schema; an empty object when it may be left out. */
        JsonNode body(byte[] bytes) {
            Body spec = this.body.orElseThrow();
            if (bytes.length == 0) {
                if (spec.required()) {
                    throw new RemoteApi.Failure(400, "The request needs a JSON body.");
                }
                return JsonNodeFactory.instance.objectNode();
            }
            JsonNode body;
            try {
                body = BODIES.readTree(bytes);
            } catch (JacksonException e) {
                throw new RemoteApi.Failure(400, "Malformed JSON: " + e.getOriginalMessage());
            }
            check(spec.schema(), body, "Body");
            return body;
        }

        private static void check(Schema schema, JsonNode value, String what) {
            List<Error> errors = schema.validate(value);
            if (!errors.isEmpty()) {
                throw new RemoteApi.Failure(
                        400,
                        what + " does not follow the API: "
                                + errors.stream()
                                        .limit(MAX_ERRORS)
                                        .map(ApiSpecification::message)
                                        .collect(Collectors.joining("; ")));
            }
        }
    }

    private static String message(Error error) {
        String at = error.getInstanceLocation() == null
                ? ""
                : error.getInstanceLocation().toString();
        return at.isEmpty() ? error.getMessage() : at + ": " + error.getMessage();
    }

    /** A path or query parameter. */
    record Parameter(String name, String in, boolean required, Kind kind, Schema schema) {

        /** How the text of a parameter becomes the JSON value its schema checks. */
        enum Kind {
            STRING,
            INTEGER,
            // A list separated by commas: style form without explode.
            STRINGS
        }

        JsonNode read(String text) {
            return switch (this.kind) {
                case STRING -> JsonNodeFactory.instance.stringNode(text);
                case INTEGER -> {
                    try {
                        yield JsonNodeFactory.instance.numberNode(Long.parseLong(text.strip()));
                    } catch (NumberFormatException e) {
                        throw new RemoteApi.Failure(400, "Parameter " + this.name + " must be an integer.");
                    }
                }
                case STRINGS -> {
                    ArrayNode items = JsonNodeFactory.instance.arrayNode();
                    Arrays.stream(text.split(",", -1))
                            .map(String::strip)
                            .filter(s -> !s.isEmpty())
                            .forEach(items::add);
                    yield items;
                }
            };
        }
    }

    record Body(boolean required, Schema schema) {}

    // Reads the parts of the document this class supports, and fails on any other, so that the document cannot ask
    // for what nothing would check.
    private record Document(JsonNode node, Schema root) {

        Route route(String path, JsonNode item) {
            if (!path.startsWith("/")) {
                throw invalid("path " + path + " must start with /");
            }
            List<String> template =
                    Arrays.stream(path.split("/", -1)).filter(s -> !s.isEmpty()).toList();
            NodePath at = pointer("paths", path);
            List<Located> shared = parameters(item.path("parameters"), at.append("parameters"));
            Map<String, Operation> operations = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : item.properties()) {
                String method = e.getKey();
                if (!METHODS.contains(method)) {
                    continue;
                }
                operations.put(method, operation(path, method, e.getValue(), at.append(method), shared));
            }
            return new Route(path, template, Map.copyOf(operations));
        }

        private Operation operation(String path, String method, JsonNode op, NodePath at, List<Located> shared) {
            String id = op.path("operationId").asString("");
            if (id.isEmpty()) {
                throw invalid(method + " " + path + " has no operationId");
            }
            Map<String, Located> byName = new LinkedHashMap<>();
            for (Located p : shared) {
                byName.put(p.in() + ":" + p.name(), p);
            }
            for (Located p : parameters(op.path("parameters"), at.append("parameters"))) {
                byName.put(p.in() + ":" + p.name(), p);
            }
            List<Parameter> parameters = new ArrayList<>();
            for (Located p : byName.values()) {
                parameters.add(parameter(id, p));
            }
            Optional<Body> body = Optional.empty();
            if (op.has("requestBody")) {
                Located request = resolve(op.get("requestBody"), at.append("requestBody"));
                JsonNode json = request.node().path("content").path("application/json");
                if (json.isMissingNode() || request.node().path("content").size() != 1) {
                    throw invalid(id + " must take application/json only");
                }
                NodePath schemaAt = request.at()
                        .append("content")
                        .append("application/json")
                        .append("schema");
                body = Optional.of(new Body(request.node().path("required").asBoolean(false), schema(schemaAt)));
            }
            return new Operation(id, method, path, List.copyOf(parameters), body);
        }

        private List<Located> parameters(JsonNode list, NodePath at) {
            List<Located> parameters = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                parameters.add(resolve(list.get(i), at.append(i)));
            }
            return parameters;
        }

        private Parameter parameter(String operation, Located p) {
            String in = p.in();
            if (!in.equals("path") && !in.equals("query")) {
                throw invalid(operation + ": parameters in " + in + " are not supported");
            }
            JsonNode schema =
                    resolve(p.node().path("schema"), p.at().append("schema")).node();
            String type = schema.path("type").asString("string");
            Parameter.Kind kind = switch (type) {
                case "string" -> Parameter.Kind.STRING;
                case "integer" -> Parameter.Kind.INTEGER;
                case "array" -> {
                    boolean form = p.node().path("style").asString("form").equals("form");
                    if (!form
                            || p.node().path("explode").asBoolean(true)
                            || !schema.path("items").path("type").asString("").equals("string")) {
                        throw invalid(operation + ": " + p.name() + " must be strings in a form without explode");
                    }
                    yield Parameter.Kind.STRINGS;
                }
                default -> throw invalid(operation + ": parameters of type " + type + " are not supported");
            };
            boolean required = in.equals("path") || p.node().path("required").asBoolean(false);
            return new Parameter(p.name(), in, required, kind, schema(p.at().append("schema")));
        }

        // A node, or the one its $ref names, with where it is.
        private Located resolve(JsonNode node, NodePath at) {
            JsonNode ref = node.get("$ref");
            if (ref == null) {
                return new Located(node, at);
            }
            String target = ref.asString();
            if (!target.startsWith("#/")) {
                throw invalid("only references inside the document are supported: " + target);
            }
            NodePath to = new NodePath(PathType.JSON_POINTER);
            for (String token : target.substring(2).split("/", -1)) {
                to = to.append(token.replace("~1", "/").replace("~0", "~"));
            }
            JsonNode resolved = this.node.at(target.substring(1));
            if (resolved.isMissingNode()) {
                throw invalid("unresolved reference " + target);
            }
            return resolve(resolved, to);
        }

        private Schema schema(NodePath at) {
            Schema schema = this.root.getSubSchema(at);
            schema.initializeValidators();
            return schema;
        }

        private static NodePath pointer(String... tokens) {
            NodePath path = new NodePath(PathType.JSON_POINTER);
            for (String token : tokens) {
                path = path.append(token);
            }
            return path;
        }

        private static IllegalStateException invalid(String why) {
            return new IllegalStateException(RESOURCE + ": " + why);
        }
    }

    private record Located(JsonNode node, NodePath at) {
        String name() {
            return this.node.path("name").asString("");
        }

        String in() {
            return this.node.path("in").asString("");
        }
    }
}
