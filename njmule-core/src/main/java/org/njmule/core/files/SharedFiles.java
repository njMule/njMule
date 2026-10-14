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
package org.njmule.core.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.AichTree;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.files.hash.FileHasher;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.security.PathSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The complete files we share: those of the incoming directory and of the shared directories, not their subdirectories.
 * Hashes are remembered in {@code known.met} (and the AICH trees in {@code known2_64.met}) by name, size and
 * modification time, so unchanged files are not hashed again. Engine thread only; hashing runs on a thread of its own
 * and reports back.
 */
public final class SharedFiles implements AutoCloseable {

    /** What changes, on the engine thread. */
    public interface Listener {
        void shared(SharedFile file);

        void unshared(SharedFile file);
    }

    /** A shared file and its hashes. */
    public record SharedFile(
            Path path,
            String name,
            long size,
            Hash16 hash,
            List<Hash16> partHashes,
            Optional<Hash20> aichHash,
            long mtime) {
        public SharedFile {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(hash, "hash");
            partHashes = List.copyOf(partHashes);
            Objects.requireNonNull(aichHash, "aichHash");
        }
    }

    public static final String KNOWN_MET = "known.met";
    public static final String KNOWN2_MET = "known2_64.met";
    public static final String SHARED_DIRS = "shareddir.dat";

    /** The directories shared with their subdirectories; other clients know only {@link #SHARED_DIRS}. */
    public static final String SHARED_TREES = "sharedtrees.dat";

    /** A change in a shared directory is looked at this long after the last one, once a copy is done. */
    public static final Duration WATCH_DELAY = Duration.ofSeconds(5);

    /** The directories are looked at again this often. */
    public static final Duration RESCAN_INTERVAL = Duration.ofMinutes(10);

    private static final Duration SAVE_DELAY = Duration.ofSeconds(10);
    // A shared tree is walked this deep, and up to so many directories.
    private static final int MAX_TREE_DEPTH = 32;
    private static final int MAX_TREE_DIRS = 10_000;
    // AICH trees kept built, for the files clients ask recovery data of now.
    private static final int MAX_BUILT_TREES = 64;
    private static final Set<String> SKIPPED = Set.of(
            "desktop.ini",
            "thumbs.db",
            "ehthumbs.db",
            "ehthumbs_vista.db",
            ".ds_store",
            ".localized",
            ".directory",
            "icon\r",
            "folder.jpg");
    // Files other programs keep beside their work: Office owner files (they carry the user's name), macOS resource
    // forks, lock files, NFS leftovers and the temporary files of sync tools.
    private static final List<String> SKIPPED_PREFIXES = List.of("~$", "._", ".~lock.", ".nfs", ".sb-", ".syncthing.");
    // Downloads still being written by browsers and other clients, and editor backups.
    private static final List<String> SKIPPED_SUFFIXES =
            List.of(".crdownload", ".download", ".partial", ".opdownload", ".!ut", ".!qb", ".bc!", ".temp", "~");
    private static final Logger logger = LoggerFactory.getLogger(SharedFiles.class);

    /** A client's private key: a directory holding one is a client's configuration. */
    static final String CLIENT_KEY = "cryptkey.dat";

    private record Key(String name, long size, long mtime) {}

    private final EngineLoop loop;
    private final Path configDir;
    private final Supplier<List<Path>> incomingDirs;
    private final Supplier<List<Path>> privateDirs;
    private final Listener listener;
    private final Map<Key, KnownMet.KnownFile> known = new LinkedHashMap<>();
    private final Map<Hash20, Known2Met.Entry> aich = new LinkedHashMap<>();
    // By master hash, the least recently asked for first: building a tree hashes all of it again.
    private final Map<Hash20, Optional<AichTree>> built = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Hash20, Optional<AichTree>> eldest) {
            return this.size() > MAX_BUILT_TREES;
        }
    };
    private final Map<Hash16, SharedFile> byHash = new LinkedHashMap<>();
    private final Map<Path, SharedFile> byPath = new HashMap<>();
    private final Set<Path> hashing = new LinkedHashSet<>();
    private final ExecutorService hasher = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("njmule-hasher").daemon(true).factory());
    private ScheduledFuture<?> rescan;
    private ScheduledFuture<?> pendingSave;
    private ScheduledFuture<?> pendingScan;
    private boolean rememberDownloaded = true;
    private final Map<Path, WatchKey> watched = new HashMap<>();
    private WatchService watcher;
    private boolean closed;

    private SharedFiles(
            EngineLoop loop,
            Path configDir,
            Supplier<List<Path>> incomingDirs,
            Supplier<List<Path>> privateDirs,
            Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.incomingDirs = Objects.requireNonNull(incomingDirs, "incomingDirs");
        this.privateDirs = Objects.requireNonNull(privateDirs, "privateDirs");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Reads the known files and starts looking at the shared directories.
     *
     * @param privateDirs directories never shared besides the config one, such as the temp one
     */
    public static SharedFiles open(
            EngineLoop loop,
            Path configDir,
            Supplier<List<Path>> incomingDirs,
            Supplier<List<Path>> privateDirs,
            Listener listener) {
        SharedFiles shares = new SharedFiles(loop, configDir, incomingDirs, privateDirs, listener);
        shares.loadKnown();
        try {
            shares.watcher = FileSystems.getDefault().newWatchService();
            Thread.ofVirtual().name("njmule-share-watcher").start(shares::watch);
        } catch (IOException | UnsupportedOperationException e) {
            logger.warn("Changes in the shared directories show at the next rescan only: {}", e.toString());
        }
        shares.rescan = loop.scheduleRepeating("rescan shared files", Duration.ZERO, RESCAN_INTERVAL, shares::scan);
        return shares;
    }

    public Optional<SharedFile> get(Hash16 hash) {
        return Optional.ofNullable(this.byHash.get(hash));
    }

    /** The hashes of the known files, shared now or downloaded before. */
    public Set<Hash16> knownHashes() {
        Set<Hash16> hashes = new HashSet<>();
        for (KnownMet.KnownFile file : this.known.values()) {
            hashes.add(file.fileHash());
        }
        return hashes;
    }

    /** Whether known.met keeps the files no longer shared, which tells the files downloaded before. */
    public void rememberDownloaded(boolean remember) {
        if (this.rememberDownloaded != remember) {
            this.rememberDownloaded = remember;
            this.saveSoon();
        }
    }

    /** The AICH tree of the file shared with {@code hash}, when its block hashes are kept. */
    public Optional<AichTree> aichTree(Hash16 hash) {
        SharedFile file = this.byHash.get(hash);
        Known2Met.Entry entry =
                file == null ? null : file.aichHash().map(this.aich::get).orElse(null);
        if (entry == null) {
            return Optional.empty();
        }
        return this.built.computeIfAbsent(entry.masterHash(), master -> {
            try {
                AichTree tree = AichTree.fromBlockHashes(file.size(), entry.blockHashes());
                return tree.masterHash().equals(master) ? Optional.of(tree) : Optional.empty();
            } catch (IllegalArgumentException e) {
                logger.debug("Unusable AICH hashes for {}: {}", file.name(), e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** The upload priority of the file shared with {@code hash}: AUTO unless one was chosen. */
    public UploadPriority priority(Hash16 hash) {
        SharedFile file = this.byHash.get(hash);
        KnownMet.KnownFile record = file == null ? null : this.known.get(key(file));
        if (record == null) {
            return UploadPriority.AUTO;
        }
        return record.uploadPriority().isPresent()
                ? priority(record.uploadPriority().getAsLong())
                : UploadPriority.AUTO;
    }

    /**
     * Renames a shared file in its directory; its hashes and priority stay known under the new name. Empty for a file
     * not shared.
     *
     * @throws java.nio.file.FileAlreadyExistsException when the directory has a file of that name
     * @throws IllegalArgumentException for a name never shared, such as that of a backup or of a hidden file
     */
    public Optional<SharedFile> rename(Hash16 hash, String newName) throws IOException {
        SharedFile file = this.byHash.get(hash);
        if (file == null) {
            return Optional.empty();
        }
        String name = PathSanitizer.fileName(newName);
        if (name.isEmpty() || name.equals(file.name())) {
            return Optional.of(file);
        }
        // The scans skip such a file: renamed so, it would be unshared a moment later.
        if (name.startsWith(".") || isSkipped(name)) {
            throw new IllegalArgumentException("a file named " + name + " is not shared");
        }
        Path target = file.path().resolveSibling(name);
        if (target.equals(file.path())) {
            // Only the case changes, which a system that ignores it does not see as a move: through another name.
            Path step = AtomicFiles.tmpPath(target);
            Files.move(file.path(), step);
            try {
                Files.move(step, target);
            } catch (IOException e) {
                Files.move(step, file.path());
                throw e;
            }
        } else {
            Files.move(file.path(), target);
        }
        SharedFile renamed = new SharedFile(
                target, name, file.size(), file.hash(), file.partHashes(), file.aichHash(), mtime(target));
        KnownMet.KnownFile known = this.known.remove(key(file));
        this.known.put(key(renamed), known == null ? record(renamed) : renamed(known, renamed));
        this.unshare(file);
        this.share(renamed, Optional.empty());
        this.saveSoon();
        return Optional.of(renamed);
    }

    /** Sets the upload priority of a shared file, kept in known.met; {@code false} for a file not shared. */
    public boolean setPriority(Hash16 hash, UploadPriority priority) {
        SharedFile file = this.byHash.get(hash);
        if (file == null) {
            return false;
        }
        Key key = key(file);
        KnownMet.KnownFile record = this.known.getOrDefault(key, record(file));
        TagName name = TagName.id(FileTag.ULPRIORITY);
        List<Tag> tags = new ArrayList<>();
        for (Tag tag : record.tags()) {
            if (!tag.name().equals(name)) {
                tags.add(tag);
            }
        }
        tags.add(new Tag.IntTag(name, priorityCode(priority)));
        this.known.put(key, new KnownMet.KnownFile(record.mtime(), record.fileHash(), record.partHashes(), tags));
        this.saveSoon();
        return true;
    }

    public List<SharedFile> all() {
        return List.copyOf(this.byHash.values());
    }

    /** The directories shared alone, besides the incoming one. */
    public List<Path> directories() {
        return this.readDirectories(SHARED_DIRS);
    }

    /** The directories shared with their subdirectories. */
    public List<Path> trees() {
        return this.readDirectories(SHARED_TREES);
    }

    private List<Path> readDirectories(String name) {
        Path file = this.configDir.resolve(name);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        try {
            for (String entry : TextFiles.entries(Files.readAllBytes(file))) {
                try {
                    dirs.add(Path.of(entry));
                } catch (IllegalArgumentException e) {
                    logger.warn("Ignoring the shared directory {}", entry);
                }
            }
        } catch (IOException e) {
            logger.warn("Cannot read {}", file, e);
        }
        return dirs;
    }

    /**
     * Shares {@code directory} too, from now on.
     *
     * @throws IllegalArgumentException for a private directory (see {@link #isPrivate})
     */
    public void addDirectory(Path directory) throws IOException {
        this.addDirectory(directory, false);
    }

    /**
     * Shares {@code directory} too, from now on, with its subdirectories (also those made later) or alone.
     *
     * @throws IllegalArgumentException for a private directory (see {@link #isPrivate})
     */
    public void addDirectory(Path directory, boolean subdirectories) throws IOException {
        Path dir = directory.toAbsolutePath().normalize();
        if (this.isPrivate(dir)) {
            throw new IllegalArgumentException("refusing to share the private directory " + dir);
        }
        this.change(SHARED_DIRS, dir, !subdirectories);
        this.change(SHARED_TREES, dir, subdirectories);
        this.scan();
    }

    /** Stops sharing {@code directory}, as shared alone or with its subdirectories. */
    public void removeDirectory(Path directory) throws IOException {
        Path dir = directory.toAbsolutePath().normalize();
        this.change(SHARED_DIRS, dir, false);
        this.change(SHARED_TREES, dir, false);
        this.scan();
    }

    private void change(String name, Path dir, boolean listed) throws IOException {
        List<Path> dirs = new ArrayList<>(this.readDirectories(name));
        boolean changed = listed ? !dirs.contains(dir) && dirs.add(dir) : dirs.remove(dir);
        if (changed) {
            List<String> lines = new ArrayList<>();
            dirs.forEach(d -> lines.add(d.toString()));
            AtomicFiles.writeBytes(this.configDir.resolve(name), TextFiles.writeLines(lines));
        }
    }

    /**
     * Remembers the hashes of another client's known files, so the same files are not hashed again; returns how many
     * were new. Only the AICH trees of those files are kept.
     */
    public int addKnown(List<KnownMet.KnownFile> files, List<Known2Met.Entry> trees) {
        Set<Hash20> wanted = new HashSet<>();
        int added = 0;
        for (KnownMet.KnownFile file : files) {
            Optional<String> name = file.name();
            if (name.isEmpty() || file.size().isEmpty() || !file.isConsistent()) {
                continue;
            }
            Key key = new Key(name.get(), file.size().getAsLong(), file.mtime());
            if (this.known.putIfAbsent(key, file) == null) {
                added++;
                file.aichHash().ifPresent(wanted::add);
            }
        }
        for (Known2Met.Entry tree : trees) {
            if (wanted.contains(tree.masterHash())) {
                this.aich.putIfAbsent(tree.masterHash(), tree);
            }
        }
        if (added > 0) {
            this.saveSoon();
            this.scan();
        }
        return added;
    }

    /** A finished download: its hashes are known, so it is shared without hashing it again. */
    public void finished(Path file, PartFile download) {
        long mtime;
        try {
            mtime = mtime(file);
            this.share(
                    new SharedFile(
                            file,
                            String.valueOf(file.getFileName()),
                            download.size(),
                            download.hash(),
                            download.partHashes(),
                            download.aichHash(),
                            mtime),
                    Optional.empty());
        } catch (IOException e) {
            logger.warn("Cannot share {}", file, e);
            return;
        }
        this.hashFinished(file, mtime, download);
    }

    // The download checked the parts, not the AICH blocks: hashing the file once more gives its tree, and with it the
    // recovery data other clients may ask for.
    private void hashFinished(Path file, long mtime, PartFile download) {
        if (this.hashing.add(file)) {
            this.loop.whenDone(
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return FileHasher.hash(file);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            },
                            this.hasher),
                    (result, failure) -> this.treeOfFinished(file, mtime, download, result, failure));
        }
    }

    private void treeOfFinished(Path file, long mtime, PartFile download, FileHasher.Result result, Throwable failure) {
        if (failure != null && !this.closed && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            // Renamed before it could be read: the copy shared now is hashed instead.
            this.hashing.remove(file);
            SharedFile current = this.byHash.get(download.hash());
            if (current != null && !current.path().equals(file)) {
                this.hashFinished(current.path(), current.mtime(), download);
            }
            return;
        }
        if (failure == null && !result.ed2k().fileHash().equals(download.hash())) {
            this.hashing.remove(file);
            logger.warn("{} changed since its download finished", file);
            return;
        }
        if (failure == null
                && download.aichHash().isPresent()
                && !result.aich().map(AichTree::masterHash).equals(download.aichHash())) {
            logger.warn("The AICH hash of the link of {} was wrong; the file's own is kept", download.name());
        }
        this.hashed(file, mtime, result, failure);
    }

    /** Looks at the directories again: new files are hashed, missing ones unshared. */
    public void scan() {
        if (this.closed) {
            return;
        }
        Set<Path> dirs = new LinkedHashSet<>();
        for (Path incoming : this.incomingDirs.get()) {
            dirs.add(incoming.toAbsolutePath().normalize());
        }
        for (Path dir : this.directories()) {
            dirs.add(dir.toAbsolutePath().normalize());
        }
        for (Path tree : this.trees()) {
            this.walk(tree.toAbsolutePath().normalize(), dirs);
        }
        this.watch(dirs);
        Set<Path> seen = new LinkedHashSet<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            if (this.isPrivate(dir)) {
                logger.warn("Not sharing the private directory {}", dir);
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path file : files) {
                    try {
                        this.consider(file, seen);
                    } catch (IOException e) {
                        // A file gone or locked meanwhile leaves the others alone; a locked one stays shared.
                        if (!(e instanceof NoSuchFileException) && this.byPath.containsKey(file)) {
                            seen.add(file);
                        }
                        logger.debug("Cannot look at {}: {}", file, e.toString());
                    }
                }
            } catch (IOException | DirectoryIteratorException e) {
                logger.warn("Cannot list the shared directory {}", dir, e);
            }
        }
        for (SharedFile file : List.copyOf(this.byPath.values())) {
            if (!seen.contains(file.path())) {
                this.unshare(file);
            }
        }
    }

    // The directories of a tree, without hidden or private ones and without following links. A junction is walked as a
    // directory, so only into a directory of the tree not walked yet: never out of the tree, nor round in a loop.
    private void walk(Path root, Set<Path> dirs) {
        if (!Files.isDirectory(root)) {
            return;
        }
        Path top = real(root);
        Set<Path> walked = new HashSet<>();
        try {
            Files.walkFileTree(root, Set.of(), MAX_TREE_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path real = real(dir);
                    boolean hidden;
                    try {
                        hidden = !dir.equals(root) && Files.isHidden(dir);
                    } catch (IOException e) {
                        // A directory that cannot be read leaves the rest of the tree alone.
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (hidden
                            || !real.startsWith(top)
                            || !walked.add(real)
                            || (!dir.equals(root) && SharedFiles.this.isPrivate(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    dirs.add(dir);
                    return dirs.size() >= MAX_TREE_DIRS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.warn("Cannot walk the shared directory {}", root, e);
        }
    }

    // Each shared directory is watched; the watch of one no longer shared ends.
    private void watch(Set<Path> dirs) {
        if (this.watcher == null) {
            return;
        }
        for (Iterator<Map.Entry<Path, WatchKey>> it = this.watched.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, WatchKey> entry = it.next();
            if (!dirs.contains(entry.getKey()) || !entry.getValue().isValid()) {
                entry.getValue().cancel();
                it.remove();
            }
        }
        for (Path dir : dirs) {
            if (!this.watched.containsKey(dir) && Files.isDirectory(dir)) {
                try {
                    this.watched.put(
                            dir,
                            dir.register(
                                    this.watcher,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_DELETE,
                                    StandardWatchEventKinds.ENTRY_MODIFY));
                } catch (IOException | ClosedWatchServiceException e) {
                    logger.debug("Cannot watch {}: {}", dir, e.toString());
                }
            }
        }
    }

    // On a thread of its own: each change asks the engine for a scan.
    private void watch() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = this.watcher.take();
                key.pollEvents();
                key.reset();
                this.loop.post(this::changed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // Closed with the shared files.
        }
    }

    // A burst of changes, such as a file being copied, gives one scan, a moment after the last of them.
    private void changed() {
        if (this.closed) {
            return;
        }
        if (this.pendingScan != null) {
            this.pendingScan.cancel(false);
        }
        this.pendingScan = this.loop.schedule("scan changed shared files", WATCH_DELAY, this::scan);
    }

    @Override
    public void close() {
        this.closed = true;
        if (this.rescan != null) {
            this.rescan.cancel(false);
        }
        if (this.pendingScan != null) {
            this.pendingScan.cancel(false);
        }
        if (this.watcher != null) {
            try {
                this.watcher.close();
            } catch (IOException e) {
                logger.debug("Cannot close the watcher of the shared directories", e);
            }
        }
        this.hasher.shutdownNow();
        this.save();
    }

    private void consider(Path file, Set<Path> seen) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String name = String.valueOf(file.getFileName());
        if (!attrs.isRegularFile()
                || attrs.size() == 0
                || attrs.size() > Ed2k.MAX_FILE_SIZE
                || Files.isHidden(file)
                || isSkipped(name)) {
            return;
        }
        seen.add(file);
        long mtime = attrs.lastModifiedTime().toMillis() / 1000;
        SharedFile shared = this.byPath.get(file);
        if (shared != null && shared.size() == attrs.size() && shared.mtime() == mtime) {
            return;
        }
        KnownMet.KnownFile record = this.known.get(new Key(name, attrs.size(), mtime));
        if (record != null && record.isConsistent()) {
            this.share(
                    new SharedFile(
                            file, name, attrs.size(), record.fileHash(), record.partHashes(), record.aichHash(), mtime),
                    Optional.empty());
            return;
        }
        if (this.hashing.add(file)) {
            this.loop.whenDone(
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return FileHasher.hash(file);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            },
                            this.hasher),
                    (result, failure) -> this.hashed(file, mtime, result, failure));
        }
    }

    private void hashed(Path file, long mtime, FileHasher.Result result, Throwable failure) {
        this.hashing.remove(file);
        if (this.closed) {
            return;
        }
        if (failure != null) {
            logger.warn("Cannot hash {}: {}", file, failure.getMessage());
            return;
        }
        Optional<Known2Met.Entry> tree = result.aich().map(t -> new Known2Met.Entry(t.masterHash(), t.blockHashes()));
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            // Renamed or gone while hashed: the copy shared now, if any, gets the AICH tree.
            SharedFile current = this.byHash.get(result.ed2k().fileHash());
            if (current != null && current.size() == result.ed2k().size()) {
                this.share(
                        new SharedFile(
                                current.path(),
                                current.name(),
                                current.size(),
                                current.hash(),
                                current.partHashes(),
                                result.aich().map(AichTree::masterHash),
                                current.mtime()),
                        tree);
            }
            return;
        }
        this.share(
                new SharedFile(
                        file,
                        String.valueOf(file.getFileName()),
                        result.ed2k().size(),
                        result.ed2k().fileHash(),
                        result.ed2k().partHashes(),
                        result.aich().map(t -> t.masterHash()),
                        mtime),
                tree);
    }

    private void share(SharedFile file, Optional<Known2Met.Entry> tree) {
        SharedFile old = this.byPath.put(file.path(), file);
        if (old != null && !old.hash().equals(file.hash())) {
            this.forget(old);
        }
        boolean fresh = !this.byHash.containsKey(file.hash());
        this.byHash.put(file.hash(), file);
        Key key = new Key(file.name(), file.size(), file.mtime());
        KnownMet.KnownFile known = this.known.get(key);
        if (known == null) {
            this.known.put(key, record(file));
            this.saveSoon();
        } else if (file.aichHash().isPresent() && !known.aichHash().equals(file.aichHash())) {
            // A finished download comes back with its AICH hash once hashed again; its other tags stay.
            this.known.put(key, withAich(known, file.aichHash().get()));
            this.saveSoon();
        }
        tree.ifPresent(t -> {
            this.aich.put(t.masterHash(), t);
            this.built.remove(t.masterHash());
            this.saveSoon();
        });
        if (fresh) {
            logger.debug("Sharing {}", file.path());
            this.listener.shared(file);
        }
    }

    private void unshare(SharedFile file) {
        this.byPath.remove(file.path());
        this.forget(file);
    }

    // A path that went or changed: its content stays shared from another copy, if there is one.
    private void forget(SharedFile old) {
        if (!old.equals(this.byHash.get(old.hash()))) {
            return;
        }
        for (SharedFile other : this.byPath.values()) {
            if (other.hash().equals(old.hash()) && !other.path().equals(old.path())) {
                this.byHash.put(old.hash(), other);
                return;
            }
        }
        this.byHash.remove(old.hash());
        this.listener.unshared(old);
    }

    private static Key key(SharedFile file) {
        return new Key(file.name(), file.size(), file.mtime());
    }

    // The values other clients write too, so an imported known.met keeps its priorities.
    private static int priorityCode(UploadPriority priority) {
        return switch (priority) {
            case LOW -> 0;
            case NORMAL -> 1;
            case HIGH -> 2;
            case RELEASE -> 3;
            case VERY_LOW -> 4;
            case AUTO -> 5;
        };
    }

    private static UploadPriority priority(long code) {
        return switch ((int) code) {
            case 0 -> UploadPriority.LOW;
            case 2 -> UploadPriority.HIGH;
            case 3 -> UploadPriority.RELEASE;
            case 4 -> UploadPriority.VERY_LOW;
            case 5 -> UploadPriority.AUTO;
            default -> UploadPriority.NORMAL;
        };
    }

    private static KnownMet.KnownFile withAich(KnownMet.KnownFile known, Hash20 aich) {
        List<Tag> tags = new ArrayList<>();
        for (Tag tag : known.tags()) {
            if (!tag.name().isId() || tag.name().id() != FileTag.AICH_HASH) {
                tags.add(tag);
            }
        }
        tags.add(new Tag.StringTag(TagName.id(FileTag.AICH_HASH), aich.toBase32()));
        return new KnownMet.KnownFile(known.mtime(), known.fileHash(), known.partHashes(), tags);
    }

    // The record of a renamed file: its name and date change, its other tags stay.
    private static KnownMet.KnownFile renamed(KnownMet.KnownFile known, SharedFile file) {
        List<Tag> tags = new ArrayList<>();
        for (Tag tag : known.tags()) {
            if (!tag.name().isId() || tag.name().id() != FileTag.FILENAME) {
                tags.add(tag);
            }
        }
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), file.name()));
        return new KnownMet.KnownFile(file.mtime(), known.fileHash(), known.partHashes(), tags);
    }

    private static KnownMet.KnownFile record(SharedFile file) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), file.name()));
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), file.size()));
        file.aichHash().ifPresent(h -> tags.add(new Tag.StringTag(TagName.id(FileTag.AICH_HASH), h.toBase32())));
        tags.add(new Tag.IntTag(TagName.id(FileTag.LASTSHARED), System.currentTimeMillis() / 1000));
        return new KnownMet.KnownFile(file.mtime(), file.hash(), file.partHashes(), tags);
    }

    private void loadKnown() {
        Path met = this.configDir.resolve(KNOWN_MET);
        if (Files.exists(met) || Files.exists(AtomicFiles.bakPath(met.toAbsolutePath()))) {
            try {
                for (KnownMet.KnownFile file : AtomicFiles.read(met, in -> KnownMet.read(in.readAllBytes()))) {
                    Optional<String> name = file.name();
                    if (name.isPresent() && file.size().isPresent()) {
                        this.known.put(new Key(name.get(), file.size().getAsLong(), file.mtime()), file);
                    }
                }
            } catch (IOException e) {
                logger.warn("Cannot read {}; files will be hashed again", met, e);
            }
        }
        Path met2 = this.configDir.resolve(KNOWN2_MET);
        if (Files.exists(met2)) {
            try {
                for (Known2Met.Entry entry : Known2Met.read(Files.readAllBytes(met2))) {
                    this.aich.put(entry.masterHash(), entry);
                }
            } catch (IOException e) {
                logger.warn("Cannot read {}", met2, e);
            }
        }
    }

    private void saveSoon() {
        if (this.pendingSave == null || this.pendingSave.isDone()) {
            this.pendingSave = this.loop.schedule("save known files", SAVE_DELAY, this::save);
        }
    }

    private void save() {
        try {
            List<KnownMet.KnownFile> kept = new ArrayList<>();
            for (KnownMet.KnownFile file : this.known.values()) {
                if (this.rememberDownloaded || this.byHash.containsKey(file.fileHash())) {
                    kept.add(file);
                }
            }
            AtomicFiles.writeBytes(this.configDir.resolve(KNOWN_MET), KnownMet.write(kept));
            AtomicFiles.writeBytes(
                    this.configDir.resolve(KNOWN2_MET), Known2Met.write(List.copyOf(this.aich.values())));
        } catch (IOException e) {
            logger.warn("Cannot save the known files", e);
        }
    }

    /**
     * Whether the files of {@code dir} must never be shared: the user's home directory, our configuration and temp
     * directories and what lies below them, and any directory holding a client's private key (another client's
     * configuration).
     */
    boolean isPrivate(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        // Through a link or a junction, the directory itself may be a private one.
        Path real = real(d);
        List<Path> roots = new ArrayList<>(this.privateDirs.get());
        roots.add(this.configDir);
        for (Path root : roots) {
            Path r = root.toAbsolutePath().normalize();
            if (d.startsWith(r) || real.startsWith(r)) {
                return true;
            }
        }
        String home = System.getProperty("user.home");
        Path h = home == null ? null : Path.of(home).toAbsolutePath().normalize();
        return (h != null && (d.equals(h) || real.equals(h))) || Files.exists(d.resolve(CLIENT_KEY));
    }

    // The path with its links and junctions resolved; as given when that cannot be done.
    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static boolean isSkipped(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SKIPPED.contains(lower)
                || SKIPPED_PREFIXES.stream().anyMatch(lower::startsWith)
                || SKIPPED_SUFFIXES.stream().anyMatch(lower::endsWith)
                || lower.endsWith(".lnk")
                || lower.endsWith(PartFile.DATA_SUFFIX)
                || lower.endsWith(PartFile.MET_SUFFIX)
                || lower.endsWith(".tmp")
                || lower.endsWith(".bak");
    }

    private static long mtime(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toMillis() / 1000;
    }
}
