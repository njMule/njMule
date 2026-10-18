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
package org.njmule.core.update;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.semver4j.Semver;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The description of a release that njMule offers as an update, {@value #FILE_NAME}, signed beside it: its version, its
 * notes and the packages it has. Packages of a kind this version does not know are left out, so that later releases may
 * add some.
 *
 * @param version the release's version
 * @param published when it was released
 * @param critical whether it fixes a security problem
 * @param notes what changed, in Markdown
 * @param assets its packages
 */
record UpdateManifest(Semver version, Instant published, boolean critical, String notes, List<Asset> assets) {

    static final String FILE_NAME = "njmule-update.json";
    static final int SCHEMA = 1;
    static final int MAX_SIZE = 256 * 1024;
    static final int MAX_NOTES = 64 * 1024;

    private static final Pattern PLATFORM_PATTERN = Pattern.compile("[a-z0-9_]{1,16}-[a-z0-9_]{1,16}");
    private static final Pattern KIND_PATTERN = Pattern.compile("[a-z0-9-]{1,16}");
    // Names of release assets: no path, nothing a URL would have to escape.
    // A file name of its own: never "." or "..", nor hidden.
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_~+-][A-Za-z0-9._~+-]{0,127}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxDocumentLength(MAX_SIZE)
                            .maxNestingDepth(4)
                            .maxStringLength(MAX_NOTES)
                            .maxNameLength(32)
                            .maxNumberLength(20)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    UpdateManifest {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(notes, "notes");
        assets = List.copyOf(assets);
    }

    /**
     * A package of the release.
     *
     * @param platform the system and processor it runs on, as "windows-x64"
     * @param kind the kind of package, as {@link org.njmule.core.api.InstallKind#id()} names it
     * @param name the file's name among the release's assets
     * @param size its length in bytes
     * @param sha256 its SHA-256 hash in lower-case hexadecimal
     */
    record Asset(String platform, String kind, String name, long size, String sha256) {}

    /** The package for {@code platform} of {@code kind}, if the release has one. */
    Optional<Asset> asset(String platform, String kind) {
        return this.assets.stream()
                .filter(a -> a.platform().equals(platform) && a.kind().equals(kind))
                .findFirst();
    }

    /**
     * Reads a manifest whose signature has been checked.
     *
     * @throws IOException when it is malformed or of another schema
     */
    static UpdateManifest parse(byte[] json) throws IOException {
        if (json.length > MAX_SIZE) {
            throw new IOException("the update information is too large");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new IOException("malformed update information: " + e.getOriginalMessage(), e);
        }
        if (!root.isObject()) {
            throw new IOException("malformed update information");
        }
        JsonNode schema = root.get("schema");
        if (schema == null || !schema.isIntegralNumber() || schema.asLong() != SCHEMA) {
            throw new IOException("update information of an unknown schema");
        }
        Semver version = Semver.parse(text(root, "version"));
        if (version == null) {
            throw new IOException("update information without a valid version");
        }
        Instant published;
        try {
            published = Instant.parse(text(root, "published"));
        } catch (DateTimeParseException e) {
            throw new IOException("update information without a valid date", e);
        }
        JsonNode critical = root.get("critical");
        if (critical != null && !critical.isBoolean()) {
            throw new IOException("malformed update information: critical");
        }
        JsonNode notes = root.get("notes");
        if (notes != null && !notes.isString()) {
            throw new IOException("malformed update information: notes");
        }
        JsonNode assets = root.get("assets");
        if (assets == null || !assets.isArray()) {
            throw new IOException("update information without packages");
        }
        List<Asset> read = new ArrayList<>();
        for (JsonNode asset : assets.values()) {
            asset(asset).ifPresent(read::add);
        }
        return new UpdateManifest(
                version,
                published,
                critical != null && critical.booleanValue(),
                notes == null ? "" : notes.asString(),
                read);
    }

    // An asset of a kind or platform this version does not know of is left out; one that is malformed is not.
    private static Optional<Asset> asset(JsonNode node) throws IOException {
        if (!node.isObject()) {
            throw new IOException("malformed update information: an asset");
        }
        String platform = text(node, "platform");
        String kind = text(node, "kind");
        String name = text(node, "name");
        String sha256 = text(node, "sha256");
        JsonNode size = node.get("size");
        if (!PLATFORM_PATTERN.matcher(platform).matches()
                || !KIND_PATTERN.matcher(kind).matches()) {
            return Optional.empty();
        }
        if (!NAME_PATTERN.matcher(name).matches()
                || name.equals(".")
                || name.equals("..")
                || !SHA256_PATTERN.matcher(sha256).matches()
                || size == null
                || !size.isIntegralNumber()
                || size.asLong() <= 0) {
            throw new IOException("malformed update information: asset " + name);
        }
        return Optional.of(new Asset(platform, kind, name, size.asLong(), sha256));
    }

    private static String text(JsonNode node, String name) throws IOException {
        JsonNode value = node.get(name);
        if (value == null || !value.isString()) {
            throw new IOException("malformed update information: " + name);
        }
        return value.asString();
    }
}
