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
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.LongConsumer;

/**
 * The web as a test sets it: bodies by address, HTTP 404 for any other, and what was asked in order. Downloads go on
 * from what the part file holds, and one can be cut short once.
 */
final class FakeWeb implements GitHubReleases.Fetcher, PackageDownload.Transfer {

    static final String REPOSITORY = "https://github.com/njMule/njMule";
    static final String API = "https://api.github.com/repos/njMule/njMule";
    static final String LATEST = REPOSITORY + "/releases/latest/download/";
    static final String LISTING = API + "/releases?per_page=10";

    private final Map<URI, byte[]> bodies = new ConcurrentHashMap<>();
    private final List<URI> asked = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private volatile long cutAfter = -1;

    void serve(String address, String body) {
        this.bodies.put(URI.create(address), body.getBytes(StandardCharsets.UTF_8));
    }

    /** Serves the manifest {@code json} and its signature by {@code signer} under {@code base}. */
    void serveRelease(String base, String json, TestSigning signer) {
        serve(base + UpdateManifest.FILE_NAME, json);
        serve(base + UpdateManifest.FILE_NAME + GitHubReleases.SIGNATURE_SUFFIX, signer.sign(json));
    }

    void serve(String address, byte[] body) {
        this.bodies.put(URI.create(address), body.clone());
    }

    /** Cuts the next download short after {@code bytes} bytes, as a dropped line would. */
    void cutNextDownloadAfter(long bytes) {
        this.cutAfter = bytes;
    }

    /** Makes every fetch wait until {@link #release()}. */
    void holdFetches() {
        this.hold = new CountDownLatch(1);
    }

    void release() {
        this.hold.countDown();
    }

    List<URI> asked() {
        return List.copyOf(this.asked);
    }

    GitHubReleases releases() {
        return new GitHubReleases(this, URI.create(REPOSITORY), URI.create(API));
    }

    @Override
    public byte[] fetch(URI uri) throws IOException {
        this.asked.add(uri);
        try {
            this.hold.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + uri.getHost(), e);
        }
        byte[] body = this.bodies.get(uri);
        if (body == null) {
            throw new IOException("HTTP 404 from " + uri.getHost());
        }
        return body.clone();
    }

    @Override
    public void download(URI uri, Path part, long size, LongConsumer progress) throws IOException {
        byte[] body = fetch(uri);
        long held = Files.isRegularFile(part) ? Files.size(part) : 0;
        long cut = this.cutAfter;
        this.cutAfter = -1;
        long end = cut >= 0 ? Math.min(body.length, held + cut) : body.length;
        try (OutputStream out = Files.newOutputStream(
                part, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            out.write(Arrays.copyOfRange(body, (int) Math.min(held, body.length), (int) end));
        }
        progress.accept(end);
        if (cut >= 0 || end < size) {
            throw new IOException("the download from " + uri.getHost() + " stopped at " + end + " of " + size);
        }
    }
}
