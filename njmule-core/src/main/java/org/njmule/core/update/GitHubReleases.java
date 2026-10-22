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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.util.Bytes;
import org.semver4j.Semver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The releases of njMule on GitHub. The newest release is a web address that redirects to its files, which GitHub
 * serves without the limits of its API; test versions are found through the API, at most once a day per client, and the
 * newest release stands in when the API does not answer.
 */
final class GitHubReleases {

    static final String SIGNATURE_SUFFIX = ".minisig";

    private static final Logger logger = LoggerFactory.getLogger(GitHubReleases.class);
    // The newest releases are enough to find the newest test version.
    private static final int LISTED = 10;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxDocumentLength(8L * 1024 * 1024)
                            .maxNestingDepth(16)
                            .build())
                    .build())
            .build();

    private final Fetcher fetcher;
    private final URI repository;
    private final URI api;

    /** Fetches the body at an address. */
    @FunctionalInterface
    interface Fetcher {
        byte[] fetch(URI uri) throws IOException;
    }

    /** A manifest as fetched and its signature file, both unchecked. */
    record Signed(Bytes manifest, String signature) {
        Signed {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(signature, "signature");
        }
    }

    /**
     * The releases of the repository at {@code repository} (https://github.com/owner/name), listed by {@code api}
     * (https://api.github.com/repos/owner/name).
     */
    GitHubReleases(Fetcher fetcher, URI repository, URI api) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.repository = withoutSlash(repository);
        this.api = withoutSlash(api);
    }

    /** The manifest of the newest release of {@code channel}. */
    Signed latest(UpdateChannel channel) throws IOException {
        if (channel == UpdateChannel.PRERELEASE) {
            Optional<String> tag;
            try {
                tag = newestTag();
            } catch (IOException e) {
                logger.debug("Cannot list the releases, taking the newest one: {}", e.toString());
                tag = Optional.empty();
            }
            if (tag.isPresent()) {
                return fetch(this.repository.toString() + "/releases/download/" + tag.get() + "/");
            }
        }
        return fetch(this.repository.toString() + "/releases/latest/download/");
    }

    /** Where a file of the release of {@code version} is downloaded from. */
    URI download(Semver version, String name) {
        return URI.create(this.repository + "/releases/download/v" + version.getVersion() + "/" + name);
    }

    /** The page of the release of {@code version}. */
    URI page(Semver version) {
        return URI.create(this.repository + "/releases/tag/v" + version.getVersion());
    }

    private Signed fetch(String base) throws IOException {
        byte[] manifest = this.fetcher.fetch(URI.create(base + UpdateManifest.FILE_NAME));
        byte[] signature = this.fetcher.fetch(URI.create(base + UpdateManifest.FILE_NAME + SIGNATURE_SUFFIX));
        return new Signed(Bytes.of(manifest), new String(signature, StandardCharsets.UTF_8));
    }

    // The tag of the newest release, test versions included, that has a signed manifest.
    private Optional<String> newestTag() throws IOException {
        byte[] listing = this.fetcher.fetch(URI.create(this.api + "/releases?per_page=" + LISTED));
        JsonNode releases;
        try {
            releases = JSON.readTree(listing);
        } catch (JacksonException e) {
            throw new IOException("malformed list of releases: " + e.getOriginalMessage(), e);
        }
        if (!releases.isArray()) {
            throw new IOException("malformed list of releases");
        }
        return releases.valueStream()
                .filter(r -> !r.path("draft").asBoolean(false))
                .filter(GitHubReleases::isSigned)
                .map(r -> r.path("tag_name").asString(""))
                .filter(tag -> version(tag).isPresent())
                .max(Comparator.comparing(tag -> version(tag).orElseThrow()));
    }

    private static boolean isSigned(JsonNode release) {
        Set<String> names = new HashSet<>();
        release.path("assets")
                .valueStream()
                .forEach(a -> names.add(a.path("name").asString("")));
        return names.contains(UpdateManifest.FILE_NAME) && names.contains(UpdateManifest.FILE_NAME + SIGNATURE_SUFFIX);
    }

    // Tags are "v" and the version.
    private static Optional<Semver> version(String tag) {
        return tag.startsWith("v") ? Optional.ofNullable(Semver.parse(tag.substring(1))) : Optional.empty();
    }

    private static URI withoutSlash(URI uri) {
        String text = uri.toString();
        return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
    }
}
