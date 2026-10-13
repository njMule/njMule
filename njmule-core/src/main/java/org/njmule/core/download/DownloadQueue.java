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
package org.njmule.core.download;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.PartFile;
import org.njmule.core.files.PartFileStorage;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.PartMet;
import org.njmule.core.security.PathSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The downloads: their part files in the temp directory, the data arriving from sources, the
 * verification of each finished part and the move of finished files to the incoming directory. Used
 * on the engine thread only; disk work runs on each file's own thread and reports back here.
 */
public final class DownloadQueue implements AutoCloseable {

    /** What the queue reports, on the engine thread. */
    public interface Listener {
        void added(Download download);

        void progress(Download download);

        void completed(Download download, Path file);

        void failed(Download download, String reason);
    }

    /** Part files are saved this often while their progress changes. */
    public static final Duration SAVE_INTERVAL = Duration.ofSeconds(60);

    private static final Logger LOG = LoggerFactory.getLogger(DownloadQueue.class);

    /** One download with its storage, its known sources and the parts verified this run. */
    public static final class Download {
        private final PartFile file;
        private final PartFileStorage storage;
        private final Set<Ed2kLink.Source> sources = new LinkedHashSet<>();
        private final BitSet verified = new BitSet();
        private boolean dirty;
        private boolean finishing;

        Download(PartFile file, PartFileStorage storage) {
            this.file = file;
            this.storage = storage;
        }

        public PartFile file() {
            return this.file;
        }

        public Hash16 hash() {
            return this.file.hash();
        }

        /** Sources given by links; the queue does not connect to them itself. */
        public Set<Ed2kLink.Source> sources() {
            return Set.copyOf(this.sources);
        }

        public boolean isActive() {
            return this.file.status() == PartFile.Status.DOWNLOADING && !this.finishing;
        }

        /** The parts whose hash was checked: the ones other clients may download from us. */
        public BitSet verifiedParts() {
            return (BitSet) this.verified.clone();
        }
    }

    private final EngineLoop loop;
    private final Path tempDir;
    private final Supplier<Path> incomingDir;
    private final Listener listener;
    private final Map<Hash16, Download> downloads = new LinkedHashMap<>();
    private ScheduledFuture<?> saver;

    private DownloadQueue(
            EngineLoop loop, Path tempDir, Supplier<Path> incomingDir, Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir");
        this.incomingDir = Objects.requireNonNull(incomingDir, "incomingDir");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Loads the part files of {@code tempDir}; unreadable ones are left alone and logged. */
    public static DownloadQueue load(
            EngineLoop loop, Path tempDir, Supplier<Path> incomingDir, Listener listener)
            throws IOException {
        DownloadQueue queue = new DownloadQueue(loop, tempDir, incomingDir, listener);
        Files.createDirectories(tempDir);
        try (DirectoryStream<Path> mets =
                Files.newDirectoryStream(tempDir, "*" + PartFile.MET_SUFFIX)) {
            for (Path met : mets) {
                String name = String.valueOf(met.getFileName());
                String number = name.substring(0, name.length() - PartFile.MET_SUFFIX.length());
                if (!number.matches("\\d{1,6}")) {
                    continue;
                }
                try {
                    PartFile file = PartFile.load(tempDir, Integer.parseInt(number));
                    Download download = new Download(file, PartFileStorage.open(file.dataFile()));
                    queue.downloads.put(file.hash(), download);
                    // A crash may have come between writing a part and checking it.
                    queue.hashSetKnown(download);
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Cannot load the download {}", met, e);
                }
            }
        }
        queue.saver =
                loop.scheduleRepeating(
                        "save downloads", SAVE_INTERVAL, SAVE_INTERVAL, queue::saveChanged);
        return queue;
    }

    /** Adds a download; an existing one for the same file gets the link's sources. */
    public Download add(Ed2kLink.FileLink link) throws IOException {
        Download existing = this.downloads.get(link.hash());
        if (existing != null) {
            existing.sources.addAll(link.sources());
            return existing;
        }
        PartFile file =
                PartFile.create(
                        this.nextNumber(),
                        this.tempDir,
                        PathSanitizer.fileName(link.name()),
                        link.size(),
                        link.hash(),
                        link.aichHash(),
                        link.partHashes());
        file.save();
        Download download = new Download(file, PartFileStorage.open(file.dataFile()));
        download.sources.addAll(link.sources());
        this.downloads.put(file.hash(), download);
        this.listener.added(download);
        return download;
    }

    /**
     * Takes over a download another client left in {@code met} and {@code data}, both already in
     * our temp directory: they get the next free number and the complete parts are checked again.
     */
    public Download adopt(Path met, Path data) throws IOException {
        PartMet.Contents contents = PartMet.read(Files.readAllBytes(met));
        if (this.downloads.containsKey(contents.fileHash())) {
            throw new FileAlreadyExistsException(
                    contents.name().orElse(contents.fileHash().toString()),
                    null,
                    "already downloading");
        }
        int number = this.nextNumber();
        Path ownData = this.tempDir.resolve(PartFile.fileName(number) + PartFile.DATA_SUFFIX);
        Path ownMet = this.tempDir.resolve(PartFile.fileName(number) + PartFile.MET_SUFFIX);
        Files.move(data, ownData);
        Files.move(met, ownMet);
        PartFile file;
        try {
            file = PartFile.load(this.tempDir, number);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(ownMet);
            Files.deleteIfExists(ownData);
            throw e;
        }
        file.rename(PathSanitizer.fileName(file.name()));
        Download download = new Download(file, PartFileStorage.open(file.dataFile()));
        download.dirty = true;
        this.downloads.put(file.hash(), download);
        this.listener.added(download);
        this.hashSetKnown(download);
        return download;
    }

    public Path tempDir() {
        return this.tempDir;
    }

    public Optional<Download> get(Hash16 hash) {
        return Optional.ofNullable(this.downloads.get(hash));
    }

    public List<Download> all() {
        return List.copyOf(this.downloads.values());
    }

    /**
     * Data for {@code download} from a source; it is written, then the parts it ends are checked.
     */
    public void received(Download download, long start, byte[] data) {
        long end = start + data.length;
        if (!download.isActive() || end > download.file.size()) {
            return;
        }
        download.storage
                .write(start, data)
                .whenComplete(
                        (ok, failure) ->
                                this.loop.post(() -> this.written(download, start, end, failure)));
    }

    /** The hash set arrived: parts finished before it can now be checked. */
    public void hashSetKnown(Download download) {
        for (int part = 0; part < download.file.partCount(); part++) {
            if (download.file.isComplete(part) && !download.verified.get(part)) {
                this.verify(download, part);
            }
        }
    }

    public void pause(Download download) {
        download.file.setStatus(PartFile.Status.PAUSED);
        download.dirty = true;
    }

    public void resume(Download download) {
        if (download.file.status() == PartFile.Status.PAUSED) {
            download.file.setStatus(PartFile.Status.DOWNLOADING);
            download.dirty = true;
        }
    }

    /** Stops the download and deletes its files. */
    public void cancel(Download download) {
        this.downloads.remove(download.hash());
        download.storage.close();
        try {
            Files.deleteIfExists(download.file.metFile());
            Files.deleteIfExists(AtomicFiles.bakPath(download.file.metFile().toAbsolutePath()));
            Files.deleteIfExists(download.file.dataFile());
        } catch (IOException e) {
            LOG.warn("Cannot delete the files of {}", download.file.name(), e);
        }
    }

    /** Saves every part file and closes the storages. */
    @Override
    public void close() {
        if (this.saver != null) {
            this.saver.cancel(false);
        }
        for (Download download : this.downloads.values()) {
            download.dirty = true;
        }
        this.saveChanged();
        for (Download download : this.downloads.values()) {
            download.storage.close();
        }
    }

    private void written(Download download, long start, long end, Throwable failure) {
        if (failure != null) {
            download.file.setStatus(PartFile.Status.ERROR);
            download.dirty = true;
            this.listener.failed(download, "cannot write the data: " + failure.getMessage());
            return;
        }
        download.file.written(start, end);
        download.dirty = true;
        int first = (int) (start / Ed2k.PARTSIZE);
        int last = (int) ((end - 1) / Ed2k.PARTSIZE);
        for (int part = first; part <= last; part++) {
            if (download.file.isComplete(part) && !download.verified.get(part)) {
                this.verify(download, part);
            }
        }
        this.listener.progress(download);
    }

    private void verify(Download download, int part) {
        Optional<Hash16> expected = download.file.partHash(part);
        if (expected.isEmpty()) {
            return;
        }
        download.storage
                .hashPart(download.file.size(), part)
                .whenComplete(
                        (actual, failure) ->
                                this.loop.post(
                                        () ->
                                                this.checked(
                                                        download,
                                                        part,
                                                        expected.get(),
                                                        actual,
                                                        failure)));
    }

    private void checked(
            Download download, int part, Hash16 expected, Hash16 actual, Throwable failure) {
        if (failure != null || !expected.equals(actual)) {
            LOG.warn("Part {} of {} is corrupt, downloading it again", part, download.file.name());
            download.file.corrupt(part);
            download.dirty = true;
            this.listener.progress(download);
            return;
        }
        download.verified.set(part);
        if (download.file.isComplete()
                && download.verified.cardinality() == download.file.partCount()
                && !download.finishing) {
            this.finish(download);
        }
    }

    private void finish(Download download) {
        download.finishing = true;
        Path target;
        try {
            target = PathSanitizer.unique(this.incomingDir.get(), download.file.name());
        } catch (IOException e) {
            download.finishing = false;
            this.listener.failed(download, e.getMessage());
            return;
        }
        download.storage
                .finish(target)
                .whenComplete(
                        (moved, failure) ->
                                this.loop.post(() -> this.finished(download, moved, failure)));
    }

    private void finished(Download download, Path moved, Throwable failure) {
        if (failure != null) {
            download.finishing = false;
            download.file.setStatus(PartFile.Status.ERROR);
            this.listener.failed(
                    download, "cannot move the finished file: " + failure.getMessage());
            return;
        }
        download.file.setStatus(PartFile.Status.COMPLETE);
        this.downloads.remove(download.hash());
        try {
            Files.deleteIfExists(download.file.metFile());
            Files.deleteIfExists(AtomicFiles.bakPath(download.file.metFile().toAbsolutePath()));
        } catch (IOException e) {
            LOG.warn("Cannot delete the metadata of {}", download.file.name(), e);
        }
        this.listener.completed(download, moved);
    }

    private void saveChanged() {
        for (Download download : this.downloads.values()) {
            if (download.dirty) {
                try {
                    download.file.save();
                    download.dirty = false;
                } catch (IOException e) {
                    LOG.warn("Cannot save {}", download.file.metFile(), e);
                }
            }
        }
    }

    private int nextNumber() {
        Set<Integer> used = new HashSet<>();
        for (Download download : this.downloads.values()) {
            used.add(download.file.number());
        }
        int number = 1;
        while (used.contains(number)
                || Files.exists(
                        this.tempDir.resolve(PartFile.fileName(number) + PartFile.MET_SUFFIX))) {
            number++;
        }
        return number;
    }
}
