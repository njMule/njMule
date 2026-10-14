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

import com.google.common.collect.Range;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
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
 * The downloads: their part files in the temp directory, the data arriving from sources, the verification of each
 * finished part and the move of finished files to the incoming directory. Used on the engine thread only; disk work
 * runs on each file's own thread and reports back here.
 */
public final class DownloadQueue implements AutoCloseable {

    /** What the queue reports, on the engine thread. */
    public interface Listener {
        void added(Download download);

        void progress(Download download);

        void completed(Download download, Path file);

        void failed(Download download, String reason);

        /** The temp disk has dropped below, or come back above, the free space downloads need. */
        default void diskSpace(boolean enough) {}

        /** A part failed its check and is missing again; AICH recovery data may save its good blocks. */
        default void corrupt(Download download, int part) {}

        /** The sources at {@code ips} (IPv4, host order) were found sending corrupt data. */
        default void corruptSenders(Download download, Set<Integer> ips) {}
    }

    /** Part files are saved this often while their progress changes. */
    public static final Duration SAVE_INTERVAL = Duration.ofSeconds(60);

    /** The free space of the temp disk is looked at this often. */
    public static final Duration SPACE_CHECK = Duration.ofSeconds(10);

    // A write that fails while the temp disk has less room than this found it full: the downloads wait for room.
    private static final long FULL_DISK_RESERVE = 64L * 1024 * 1024;
    // A finished file held by another program is moved again after 2, 4, ... 64 seconds, two minutes in all.
    private static final int FINISH_RETRIES = 6;
    private static final Duration FINISH_RETRY_DELAY = Duration.ofSeconds(2);
    // How long closing waits for the last saves and writes.
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(30);

    private static final Logger logger = LoggerFactory.getLogger(DownloadQueue.class);
    private static final Pattern MET_NAME = Pattern.compile(
            "(\\d{1,6})" + Pattern.quote(PartFile.MET_SUFFIX) + "(?:" + Pattern.quote(AtomicFiles.BAK_SUFFIX) + ")?");

    /** One download with its storage, its known sources and the parts verified this run. */
    public final class Download {
        private final PartFile file;
        private PartFileStorage storage;
        private final Set<Ed2kLink.Source> sources = new LinkedHashSet<>();
        private final BitSet verified = new BitSet();
        private boolean dirty;
        private boolean finishing;
        // The generation of its last part.met save: a save is skipped once a newer one follows, and so after a cancel.
        private final AtomicLong saves = new AtomicLong();
        // Moves of its finished file tried again because another program held it.
        private int finishRetries;
        // Parts that failed their check and are being downloaded again, the bytes written into each since it was last
        // hashed, and those being hashed.
        private final BitSet corrupted = new BitSet();
        private final Map<Integer, Long> sinceIch = new HashMap<>();
        private final BitSet ichRunning = new BitSet();
        // Parts whose check is on its way.
        private final BitSet checking = new BitSet();
        private final CorruptionBlackBox blackBox = new CorruptionBlackBox();

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
            return this.file.status() == PartFile.Status.DOWNLOADING
                    && !this.finishing
                    && !DownloadQueue.this.spaceShort;
        }

        PartFileStorage storage() {
            return this.storage;
        }

        /** Whether its finished file is being moved to the incoming directory. */
        boolean isFinishing() {
            return this.finishing;
        }

        /** The parts whose hash was checked: the ones other clients may download from us. */
        public BitSet verifiedParts() {
            return (BitSet) this.verified.clone();
        }
    }

    private final EngineLoop loop;
    private final Path tempDir;
    private final Function<PartFile, Path> incomingDir;
    private final LongSupplier minFree;
    private final Listener listener;
    private final Map<Hash16, Download> downloads = new LinkedHashMap<>();
    private ScheduledFuture<?> saver;
    private ScheduledFuture<?> spaceChecker;
    private boolean spaceShort;
    // Whether a write found the disk full: then downloads wait for the reserve at least, whatever the minimum is.
    private boolean diskFull;

    private DownloadQueue(
            EngineLoop loop,
            Path tempDir,
            Function<PartFile, Path> incomingDir,
            LongSupplier minFree,
            Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir");
        this.incomingDir = Objects.requireNonNull(incomingDir, "incomingDir");
        this.minFree = Objects.requireNonNull(minFree, "minFree");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Loads the part files of {@code tempDir}, also those of which only the {@code .bak} is left; unreadable ones are
     * left alone and logged.
     *
     * @param incomingDir where each finished file goes
     * @param minFree the bytes the temp disk must keep free while downloads write to it; 0 = no minimum
     */
    public static DownloadQueue load(
            EngineLoop loop,
            Path tempDir,
            Function<PartFile, Path> incomingDir,
            LongSupplier minFree,
            Listener listener)
            throws IOException {
        DownloadQueue queue = new DownloadQueue(loop, tempDir, incomingDir, minFree, listener);
        Files.createDirectories(tempDir);
        Set<Integer> numbers = new TreeSet<>();
        try (DirectoryStream<Path> mets = Files.newDirectoryStream(tempDir, "*" + PartFile.MET_SUFFIX + "*")) {
            for (Path met : mets) {
                Matcher m = MET_NAME.matcher(String.valueOf(met.getFileName()));
                if (m.matches()) {
                    numbers.add(Integer.parseInt(m.group(1)));
                }
            }
        }
        List<Download> loaded = new ArrayList<>();
        for (int number : numbers) {
            try {
                PartFile file = PartFile.load(tempDir, number);
                Download same = queue.downloads.get(file.hash());
                if (same != null) {
                    logger.warn(
                            "{} downloads the same file as {}; it is left alone",
                            PartFile.fileName(number),
                            PartFile.fileName(same.file.number()));
                    continue;
                }
                Download download = queue.new Download(file, PartFileStorage.open(file.dataFile()));
                queue.downloads.put(file.hash(), download);
                loaded.add(download);
            } catch (IOException | RuntimeException e) {
                logger.warn("Cannot load the download {}", PartFile.fileName(number), e);
            }
        }
        // In the order they were added, which their numbers, taken again once free, do not keep.
        loaded.sort(Comparator.comparingLong(d -> d.file.added()));
        queue.downloads.clear();
        for (Download download : loaded) {
            queue.downloads.put(download.hash(), download);
        }
        for (Download download : loaded) {
            // A crash may have come between writing a part and checking it.
            queue.hashSetKnown(download);
        }
        queue.saver = loop.scheduleRepeating("save downloads", SAVE_INTERVAL, SAVE_INTERVAL, queue::saveChanged);
        queue.checkSpace();
        queue.spaceChecker = loop.scheduleRepeating("disk space", SPACE_CHECK, SPACE_CHECK, queue::checkSpace);
        return queue;
    }

    /** Adds a download; an existing one for the same file gets the link's sources. */
    public Download add(Ed2kLink.FileLink link) throws IOException {
        return this.add(link, Category.NONE, DownloadPriority.AUTO);
    }

    /**
     * Adds a download in {@code category} with {@code priority}. An existing one gets the link's sources, and its AICH
     * hash if it has none, when the link is of the same file (see {@link #sameFile}).
     */
    public Download add(Ed2kLink.FileLink link, int category, DownloadPriority priority) throws IOException {
        Download existing = this.downloads.get(link.hash());
        if (existing != null) {
            if (sameFile(existing.file, link)) {
                existing.sources.addAll(link.sources());
                if (existing.file.aichHash().isEmpty() && link.aichHash().isPresent()) {
                    existing.file.trustAich(link.aichHash().get());
                    existing.dirty = true;
                }
            }
            return existing;
        }
        PartFile file = PartFile.create(
                this.nextNumber(),
                this.tempDir,
                PathSanitizer.fileName(link.name()),
                link.size(),
                link.hash(),
                link.aichHash(),
                link.partHashes());
        file.setCategory(category);
        file.setPriority(priority);
        file.setAdded(this.nextAdded());
        // Written at once, so that a download that cannot be kept is not added.
        file.save();
        Download download = new Download(file, PartFileStorage.open(file.dataFile()));
        download.sources.addAll(link.sources());
        this.downloads.put(file.hash(), download);
        this.listener.added(download);
        return download;
    }

    /**
     * Takes over a download another client left in {@code met} and {@code data}, both already in our temp directory:
     * they get the next free number and the complete parts are checked again.
     */
    public Download adopt(Path met, Path data) throws IOException {
        PartMet.Contents contents = PartMet.read(Files.readAllBytes(met));
        if (this.downloads.containsKey(contents.fileHash())) {
            throw new FileAlreadyExistsException(
                    contents.name().orElse(contents.fileHash().toString()), null, "already downloading");
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
        file.setAdded(this.nextAdded());
        Download download = new Download(file, PartFileStorage.open(file.dataFile()));
        download.dirty = true;
        this.downloads.put(file.hash(), download);
        this.listener.added(download);
        this.hashSetKnown(download);
        return download;
    }

    /**
     * Whether {@code link} names the file of {@code file}: the same size, and the same AICH hash when both have one.
     */
    static boolean sameFile(PartFile file, Ed2kLink.FileLink link) {
        return file.size() == link.size()
                && (file.aichHash().isEmpty()
                        || link.aichHash().isEmpty()
                        || file.aichHash().equals(link.aichHash()));
    }

    public Path tempDir() {
        return this.tempDir;
    }

    /** Whether downloads wait because the temp disk has less free space than they need. */
    public boolean isSpaceShort() {
        return this.spaceShort;
    }

    public Optional<Download> get(Hash16 hash) {
        return Optional.ofNullable(this.downloads.get(hash));
    }

    public List<Download> all() {
        return List.copyOf(this.downloads.values());
    }

    /** Data for {@code download} from a source; it is written, then the parts it ends are checked. */
    public void received(Download download, long start, byte[] data) {
        this.received(download, start, data, 0);
    }

    /**
     * As {@link #received(Download, long, byte[])}, from the source at {@code sourceIp} (IPv4, host order; 0 when not
     * known), which is told when its data proves corrupt.
     */
    public void received(Download download, long start, byte[] data, int sourceIp) {
        long end = start + data.length;
        if (!download.isActive() || end > download.file.size()) {
            return;
        }
        // Only what is still missing: a part made whole meanwhile, by AICH or ICH, holds checked data already.
        for (Range<Long> gap : download.file.missing(start, end)) {
            long from = gap.lowerEndpoint();
            long to = gap.upperEndpoint();
            byte[] slice = from == start && to == end
                    ? data
                    : Arrays.copyOfRange(data, (int) (from - start), (int) (to - start));
            this.loop.whenDone(
                    download.storage.write(from, slice),
                    (ok, failure) -> this.written(download, from, to, sourceIp, failure));
        }
    }

    /** The hash set arrived: parts finished before it can now be checked, unless checked before a restart. */
    public void hashSetKnown(Download download) {
        BitSet trusted = download.file.trustedParts();
        for (int part = 0; part < download.file.partCount(); part++) {
            if (!download.file.isComplete(part) || download.verified.get(part)) {
                continue;
            }
            if (trusted.get(part)) {
                download.verified.set(part);
            } else {
                this.verify(download, part);
            }
        }
        if (download.file.isComplete()
                && download.verified.cardinality() == download.file.partCount()
                && !download.finishing) {
            this.finish(download);
        }
    }

    public void pause(Download download) {
        download.file.setStatus(PartFile.Status.PAUSED);
        download.dirty = true;
    }

    /** Pauses the download for good: the caller forgets its sources. */
    public void stop(Download download) {
        download.file.setStatus(PartFile.Status.STOPPED);
        download.dirty = true;
    }

    /** Resumes a paused, stopped or failed download; {@code false} if it was none of these. */
    public boolean resume(Download download) {
        PartFile.Status status = download.file.status();
        if (status != PartFile.Status.PAUSED && status != PartFile.Status.STOPPED && status != PartFile.Status.ERROR) {
            return false;
        }
        download.file.setStatus(PartFile.Status.DOWNLOADING);
        download.dirty = true;
        if (download.file.isComplete() && !download.finishing && !download.storage.isOpen()) {
            // Its move failed: the data is checked again from the reopened file, then moved once more.
            try {
                download.storage = PartFileStorage.open(download.file.dataFile());
            } catch (IOException e) {
                download.file.setStatus(PartFile.Status.ERROR);
                this.listener.failed(download, "cannot open the data: " + e.getMessage());
                return true;
            }
            download.verified.clear();
            download.checking.clear();
            for (int part = 0; part < download.file.partCount(); part++) {
                this.verify(download, part);
            }
        }
        return true;
    }

    public void setPreviewFirst(Download download, boolean value) {
        download.file.setPreviewFirst(value);
        download.dirty = true;
    }

    public void setAutoSwap(Download download, boolean value) {
        download.file.setAutoSwap(value);
        download.dirty = true;
    }

    public void setPriority(Download download, DownloadPriority priority) {
        download.file.setPriority(priority);
        download.dirty = true;
    }

    public void setCategory(Download download, int category) {
        download.file.setCategory(category);
        download.dirty = true;
    }

    /**
     * Stops the download and deletes its files; {@code false} while its finished file is being moved, as a link added
     * again would take the same files.
     */
    public boolean cancel(Download download) {
        if (download.finishing) {
            return false;
        }
        this.downloads.remove(download.hash());
        // Saves still queued are skipped, and the files go once its disk thread is done with them.
        download.saves.incrementAndGet();
        download.storage.close();
        download.storage.whenClosed().whenComplete((ok, failure) -> deleteFiles(download.file));
        return true;
    }

    private static void deleteFiles(PartFile file) {
        try {
            Files.deleteIfExists(file.metFile());
            Files.deleteIfExists(AtomicFiles.bakPath(file.metFile().toAbsolutePath()));
            Files.deleteIfExists(file.dataFile());
        } catch (IOException e) {
            logger.warn("Cannot delete the files of {}", file.name(), e);
        }
    }

    /** Saves every part file and closes the storages. */
    @Override
    public void close() {
        if (this.saver != null) {
            this.saver.cancel(false);
        }
        if (this.spaceChecker != null) {
            this.spaceChecker.cancel(false);
        }
        for (Download download : this.downloads.values()) {
            download.dirty = true;
        }
        this.saveChanged();
        List<CompletableFuture<Void>> closing = new ArrayList<>();
        for (Download download : this.downloads.values()) {
            download.storage.close();
            closing.add(download.storage.whenClosed());
        }
        // The saves and the last writes end before njMule does.
        try {
            CompletableFuture.allOf(closing.toArray(CompletableFuture<?>[]::new))
                    .get(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            logger.warn("Not every download could be saved", e);
        }
    }

    private void written(Download download, long start, long end, int sourceIp, Throwable failure) {
        if (!this.isQueued(download)) {
            return;
        }
        if (failure != null) {
            // The session has let the block go: the range is asked for again once the download goes on.
            download.file.release(start, end);
            download.dirty = true;
            if (this.isOutOfSpace()) {
                logger.warn("The temp disk is full: downloads wait for room");
                this.diskFull = true;
                this.checkSpace();
                return;
            }
            download.file.setStatus(PartFile.Status.ERROR);
            this.listener.failed(download, "cannot write the data: " + failure.getMessage());
            return;
        }
        download.file.written(start, end);
        download.dirty = true;
        if (sourceIp != 0) {
            download.blackBox.received(start, end, sourceIp);
        }
        int first = (int) (start / Ed2k.PARTSIZE);
        int last = (int) ((end - 1) / Ed2k.PARTSIZE);
        for (int part = first; part <= last; part++) {
            if (download.file.isComplete(part)) {
                if (!download.verified.get(part)) {
                    this.verify(download, part);
                }
            } else if (download.corrupted.get(part)) {
                Range<Long> range = PartFile.partRange(download.file.size(), part);
                long bytes = Math.min(end, range.upperEndpoint()) - Math.max(start, range.lowerEndpoint());
                this.mayBeWholeAgain(download, part, bytes);
            }
        }
        this.listener.progress(download);
    }

    // Intelligent corruption handling: a corrupt part keeps its data on disk while it is downloaded again, so once the
    // bad bytes were overwritten the part as it stands is whole. It is hashed after each block written into it.
    private void mayBeWholeAgain(Download download, int part, long bytes) {
        long written = download.sinceIch.merge(part, bytes, Long::sum);
        Optional<Hash16> expected = download.file.partHash(part);
        if (written < Ed2k.EMBLOCKSIZE || download.ichRunning.get(part) || expected.isEmpty()) {
            return;
        }
        download.sinceIch.put(part, 0L);
        download.ichRunning.set(part);
        this.loop.whenDone(
                download.storage.hashPart(download.file.size(), part),
                (actual, failure) -> this.ichChecked(download, part, expected.get(), actual, failure));
    }

    private void ichChecked(Download download, int part, Hash16 expected, Hash16 actual, Throwable failure) {
        download.ichRunning.clear(part);
        if (!this.isQueued(download)
                || failure != null
                || !expected.equals(actual)
                || !download.corrupted.get(part)
                || download.file.isComplete(part)) {
            return;
        }
        Range<Long> range = PartFile.partRange(download.file.size(), part);
        long saved = 0;
        for (Range<Long> gap : download.file.missing(range.lowerEndpoint(), range.upperEndpoint())) {
            saved += gap.upperEndpoint() - gap.lowerEndpoint();
        }
        logger.info("ICH saved {} bytes of part {} of {}", saved, part, download.file.name());
        download.file.written(range.lowerEndpoint(), range.upperEndpoint());
        download.dirty = true;
        // Checked once more after any write queued meanwhile: no other can come, the part being whole.
        this.verify(download, part);
        this.listener.progress(download);
    }

    // A write or a check can finish after its download was cancelled, and then its storage is closed.
    private boolean isQueued(Download download) {
        return this.downloads.get(download.hash()) == download;
    }

    // One check of a part at a time: it runs after the writes queued before it, so writes that end meanwhile, as those
    // still on their way when ICH made the part whole, need no check of their own.
    private void verify(Download download, int part) {
        Optional<Hash16> expected = download.file.partHash(part);
        if (expected.isEmpty() || download.checking.get(part)) {
            return;
        }
        download.checking.set(part);
        this.loop.whenDone(
                download.storage.hashPart(download.file.size(), part),
                (actual, failure) -> this.checked(download, part, expected.get(), actual, failure));
    }

    private void checked(Download download, int part, Hash16 expected, Hash16 actual, Throwable failure) {
        download.checking.clear(part);
        if (!this.isQueued(download)) {
            return;
        }
        if (failure != null || !expected.equals(actual)) {
            logger.warn("Part {} of {} is corrupt, downloading it again", part, download.file.name());
            download.file.corrupt(part);
            download.corrupted.set(part);
            download.sinceIch.remove(part);
            download.dirty = true;
            if (failure == null) {
                Range<Long> range = PartFile.partRange(download.file.size(), part);
                this.blame(download, download.blackBox.failed(range.lowerEndpoint(), range.upperEndpoint()));
            }
            // Before progress, which asks for blocks: a part that waits for its recovery data is not among them.
            this.listener.corrupt(download, part);
            this.listener.progress(download);
            return;
        }
        download.verified.set(part);
        download.file.verified(part);
        Range<Long> range = PartFile.partRange(download.file.size(), part);
        download.blackBox.passed(range.lowerEndpoint(), range.upperEndpoint());
        download.corrupted.clear(part);
        download.sinceIch.remove(part);
        download.dirty = true;
        if (download.file.isComplete()
                && download.verified.cardinality() == download.file.partCount()
                && !download.finishing) {
            this.finish(download);
        }
    }

    /**
     * Takes back the blocks of {@code part} whose data on disk has the AICH hash in {@code blocks}, which recovery data
     * proved against the trusted master hash; the others are downloaded again.
     */
    public void recovered(Download download, int part, List<Hash20> blocks) {
        if (!this.isQueued(download) || download.verified.get(part)) {
            return;
        }
        this.loop.whenDone(
                download.storage.hashBlocks(download.file.size(), part),
                (ours, failure) -> this.takeBack(download, part, blocks, ours, failure));
    }

    private void takeBack(Download download, int part, List<Hash20> blocks, List<Hash20> ours, Throwable failure) {
        download.file.unhold(part);
        if (!this.isQueued(download)) {
            return;
        }
        if (failure != null || ours.size() != blocks.size()) {
            // The part is downloaded again whole, also by the sessions waiting for it.
            this.listener.progress(download);
            return;
        }
        long start = Ed2k.partStart(part);
        long end = start + Ed2k.partLength(download.file.size(), part);
        long saved = 0;
        List<Range<Long>> good = new ArrayList<>();
        List<Range<Long>> bad = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            long from = start + (long) i * Ed2k.EMBLOCKSIZE;
            long to = Math.min(end, from + Ed2k.EMBLOCKSIZE);
            if (blocks.get(i).equals(ours.get(i))) {
                download.file.written(from, to);
                good.add(Range.closedOpen(from, to));
                saved += to - from;
            } else {
                bad.add(Range.closedOpen(from, to));
            }
        }
        this.blame(download, download.blackBox.recovered(good, bad));
        download.dirty = true;
        logger.info("AICH saved {} of {} bytes of part {} of {}", saved, end - start, part, download.file.name());
        if (download.file.isComplete(part)) {
            this.verify(download, part);
        }
        this.listener.progress(download);
    }

    private void blame(Download download, Set<Integer> guilty) {
        if (!guilty.isEmpty()) {
            this.listener.corruptSenders(download, guilty);
        }
    }

    private void finish(Download download) {
        download.finishing = true;
        this.loop.whenDone(
                download.storage.finish(this.incomingDir.apply(download.file), download.file.name()),
                (moved, failure) -> this.finished(download, moved, failure));
    }

    private void finished(Download download, Path moved, Throwable failure) {
        // Moved or not, the data file is closed: a retry opens it again.
        download.storage.close();
        if (!this.isQueued(download)) {
            return;
        }
        if (failure != null) {
            if (isHeld(download, failure) && download.finishRetries < FINISH_RETRIES) {
                Duration delay = FINISH_RETRY_DELAY.multipliedBy(1L << download.finishRetries++);
                logger.info(
                        "{} is held by another program; it is moved again in {} s",
                        download.file.name(),
                        delay.toSeconds());
                this.loop.schedule("move a finished file again", delay, () -> this.finishAgain(download));
                return;
            }
            download.finishRetries = 0;
            download.finishing = false;
            download.file.setStatus(PartFile.Status.ERROR);
            this.listener.failed(download, "cannot move the finished file: " + failure.getMessage());
            return;
        }
        download.file.setStatus(PartFile.Status.COMPLETE);
        this.downloads.remove(download.hash());
        try {
            Files.deleteIfExists(download.file.metFile());
            Files.deleteIfExists(AtomicFiles.bakPath(download.file.metFile().toAbsolutePath()));
        } catch (IOException e) {
            logger.warn("Cannot delete the metadata of {}", download.file.name(), e);
        }
        this.listener.completed(download, moved);
    }

    // A virus scanner or an indexer opens a finished file at once and may keep it for a while: the move is tried again.
    private static boolean isHeld(Download download, Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof UncheckedIOException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (!(cause instanceof FileSystemException held)
                || cause instanceof NoSuchFileException
                || held.getFile() == null) {
            return false;
        }
        Path data = download.file.dataFile().toAbsolutePath().normalize();
        try {
            return Path.of(held.getFile()).toAbsolutePath().normalize().equals(data);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private void finishAgain(Download download) {
        if (!this.isQueued(download)) {
            return;
        }
        try {
            download.storage = PartFileStorage.open(download.file.dataFile());
        } catch (IOException e) {
            download.finishRetries = 0;
            download.finishing = false;
            download.file.setStatus(PartFile.Status.ERROR);
            this.listener.failed(download, "cannot open the data: " + e.getMessage());
            return;
        }
        this.finish(download);
    }

    // Downloads wait while the temp disk is short of space and go on by themselves once there is room again.
    void checkSpace() {
        long min = Math.max(this.minFree.getAsLong(), this.diskFull ? FULL_DISK_RESERVE : 0);
        boolean shortNow;
        if (min <= 0) {
            shortNow = false;
        } else {
            try {
                shortNow = Files.getFileStore(this.tempDir).getUsableSpace() < min;
            } catch (IOException e) {
                logger.warn("Cannot read the free space of {}", this.tempDir, e);
                return;
            }
        }
        if (!shortNow) {
            this.diskFull = false;
        }
        if (shortNow != this.spaceShort) {
            this.spaceShort = shortNow;
            this.listener.diskSpace(!shortNow);
        }
    }

    // A failed write on a disk short of the room downloads need is a full disk, not a broken one.
    private boolean isOutOfSpace() {
        try {
            return Files.getFileStore(this.tempDir).getUsableSpace()
                    < Math.max(this.minFree.getAsLong(), FULL_DISK_RESERVE);
        } catch (IOException e) {
            return false;
        }
    }

    void saveChanged() {
        for (Download download : this.downloads.values()) {
            // One being moved is complete and saved: its part.met goes once it is moved.
            if (download.dirty && !download.finishing) {
                this.save(download);
            }
        }
    }

    // Built here and written on the download's disk thread, after the data queued before it. A failed save is made
    // again at the next round, unless a newer one is on its way.
    private void save(Download download) {
        download.dirty = false;
        long generation = download.saves.incrementAndGet();
        PartMet.Contents contents = download.file.metContents();
        if (!download.storage.isOpen()) {
            // Closed after its finished file could not be moved: rare enough to be written here.
            try {
                PartFile.writeMet(download.file.metFile(), contents, download.file.dataFile());
            } catch (IOException e) {
                logger.warn("Cannot save {}", download.file.metFile(), e);
                download.dirty = true;
            }
            return;
        }
        this.loop.whenDone(
                download.storage.saveMet(download.file.metFile(), contents, () -> download.saves.get() == generation),
                (ok, failure) -> {
                    if (failure != null && download.saves.get() == generation && this.isQueued(download)) {
                        logger.warn("Cannot save {}: {}", download.file.metFile(), failure.toString());
                        download.dirty = true;
                    }
                });
    }

    private long nextAdded() {
        long last = 0;
        for (Download download : this.downloads.values()) {
            last = Math.max(last, download.file.added());
        }
        return last + 1;
    }

    private int nextNumber() {
        Set<Integer> used = new HashSet<>();
        for (Download download : this.downloads.values()) {
            used.add(download.file.number());
        }
        int number = 1;
        while (used.contains(number) || this.anyFileOf(number)) {
            number++;
        }
        return number;
    }

    // A number is taken while any file of it is left, so a new download never reopens an old part file.
    private boolean anyFileOf(int number) {
        String base = PartFile.fileName(number);
        Path met = this.tempDir.resolve(base + PartFile.MET_SUFFIX);
        return Files.exists(met)
                || Files.exists(AtomicFiles.bakPath(met))
                || Files.exists(AtomicFiles.tmpPath(met))
                || Files.exists(this.tempDir.resolve(base + PartFile.DATA_SUFFIX));
    }
}
