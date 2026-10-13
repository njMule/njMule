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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.files.hash.FileHasher;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.Known2Met;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The complete files we share: those of the incoming directory and of the shared directories, not
 * their subdirectories. Hashes are remembered in {@code known.met} (and the AICH trees in {@code
 * known2_64.met}) by name, size and modification time, so unchanged files are not hashed again.
 * Engine thread only; hashing runs on a thread of its own and reports back.
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

    /** The directories are looked at again this often. */
    public static final Duration RESCAN_INTERVAL = Duration.ofMinutes(10);

    private static final Duration SAVE_DELAY = Duration.ofSeconds(10);
    private static final Set<String> SKIPPED =
            Set.of("desktop.ini", "thumbs.db", ".ds_store", "folder.jpg");
    private static final Logger LOG = LoggerFactory.getLogger(SharedFiles.class);

    /** A client's private key: a directory holding one is a client's configuration. */
    static final String CLIENT_KEY = "cryptkey.dat";

    private record Key(String name, long size, long mtime) {}

    private final EngineLoop loop;
    private final Path configDir;
    private final Supplier<Path> incomingDir;
    private final Supplier<List<Path>> privateDirs;
    private final Listener listener;
    private final Map<Key, KnownMet.KnownFile> known = new LinkedHashMap<>();
    private final Map<Hash20, Known2Met.Entry> aich = new LinkedHashMap<>();
    private final Map<Hash16, SharedFile> byHash = new LinkedHashMap<>();
    private final Map<Path, SharedFile> byPath = new HashMap<>();
    private final Set<Path> hashing = new LinkedHashSet<>();
    private final ExecutorService hasher =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("njmule-hasher").daemon(true).factory());
    private ScheduledFuture<?> rescan;
    private ScheduledFuture<?> pendingSave;
    private boolean closed;

    private SharedFiles(
            EngineLoop loop,
            Path configDir,
            Supplier<Path> incomingDir,
            Supplier<List<Path>> privateDirs,
            Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.incomingDir = Objects.requireNonNull(incomingDir, "incomingDir");
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
            Supplier<Path> incomingDir,
            Supplier<List<Path>> privateDirs,
            Listener listener) {
        SharedFiles shares = new SharedFiles(loop, configDir, incomingDir, privateDirs, listener);
        shares.loadKnown();
        shares.rescan =
                loop.scheduleRepeating(
                        "rescan shared files", Duration.ZERO, RESCAN_INTERVAL, shares::scan);
        return shares;
    }

    public Optional<SharedFile> get(Hash16 hash) {
        return Optional.ofNullable(this.byHash.get(hash));
    }

    public List<SharedFile> all() {
        return List.copyOf(this.byHash.values());
    }

    /** The directories shared besides the incoming one. */
    public List<Path> directories() {
        Path file = this.configDir.resolve(SHARED_DIRS);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        try {
            for (String entry : TextFiles.entries(Files.readAllBytes(file))) {
                try {
                    dirs.add(Path.of(entry));
                } catch (IllegalArgumentException e) {
                    LOG.warn("Ignoring the shared directory {}", entry);
                }
            }
        } catch (IOException e) {
            LOG.warn("Cannot read {}", file, e);
        }
        return dirs;
    }

    /**
     * Shares {@code directory} too, from now on.
     *
     * @throws IllegalArgumentException for a private directory (see {@link #isPrivate})
     */
    public void addDirectory(Path directory) throws IOException {
        Path dir = directory.toAbsolutePath().normalize();
        if (this.isPrivate(dir)) {
            throw new IllegalArgumentException("refusing to share the private directory " + dir);
        }
        List<Path> dirs = new ArrayList<>(this.directories());
        if (!dirs.contains(dir)) {
            dirs.add(dir);
            List<String> lines = new ArrayList<>();
            dirs.forEach(d -> lines.add(d.toString()));
            AtomicFiles.writeBytes(
                    this.configDir.resolve(SHARED_DIRS), TextFiles.writeLines(lines));
        }
        this.scan();
    }

    /**
     * Remembers the hashes of another client's known files, so the same files are not hashed again;
     * returns how many were new. Only the AICH trees of those files are kept.
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
        try {
            long mtime = mtime(file);
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
            LOG.warn("Cannot share {}", file, e);
        }
    }

    /** Looks at the directories again: new files are hashed, missing ones unshared. */
    public void scan() {
        if (this.closed) {
            return;
        }
        Set<Path> dirs = new LinkedHashSet<>();
        dirs.add(this.incomingDir.get().toAbsolutePath().normalize());
        for (Path dir : this.directories()) {
            dirs.add(dir.toAbsolutePath().normalize());
        }
        Set<Path> seen = new LinkedHashSet<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            if (this.isPrivate(dir)) {
                LOG.warn("Not sharing the private directory {}", dir);
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path file : files) {
                    this.consider(file, seen);
                }
            } catch (IOException e) {
                LOG.warn("Cannot list the shared directory {}", dir, e);
            }
        }
        for (SharedFile file : List.copyOf(this.byPath.values())) {
            if (!seen.contains(file.path())) {
                this.unshare(file);
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
        if (this.rescan != null) {
            this.rescan.cancel(false);
        }
        this.hasher.shutdownNow();
        this.save();
    }

    private void consider(Path file, Set<Path> seen) throws IOException {
        BasicFileAttributes attrs =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
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
                            file,
                            name,
                            attrs.size(),
                            record.fileHash(),
                            record.partHashes(),
                            record.aichHash(),
                            mtime),
                    Optional.empty());
            return;
        }
        if (this.hashing.add(file)) {
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return FileHasher.hash(file);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            },
                            this.hasher)
                    .whenComplete(
                            (result, failure) ->
                                    this.loop.post(
                                            () -> this.hashed(file, mtime, result, failure)));
        }
    }

    private void hashed(Path file, long mtime, FileHasher.Result result, Throwable failure) {
        this.hashing.remove(file);
        if (this.closed) {
            return;
        }
        if (failure != null) {
            LOG.warn("Cannot hash {}: {}", file, failure.getMessage());
            return;
        }
        Optional<Known2Met.Entry> tree =
                result.aich().map(t -> new Known2Met.Entry(t.masterHash(), t.blockHashes()));
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
            this.byHash.remove(old.hash());
        }
        boolean fresh = !this.byHash.containsKey(file.hash());
        this.byHash.put(file.hash(), file);
        Key key = new Key(file.name(), file.size(), file.mtime());
        if (!this.known.containsKey(key)) {
            this.known.put(key, record(file));
            this.saveSoon();
        }
        tree.ifPresent(
                t -> {
                    this.aich.put(t.masterHash(), t);
                    this.saveSoon();
                });
        if (fresh) {
            LOG.debug("Sharing {}", file.path());
            this.listener.shared(file);
        }
    }

    private void unshare(SharedFile file) {
        this.byPath.remove(file.path());
        if (this.byHash.get(file.hash()) == file) {
            this.byHash.remove(file.hash());
            this.listener.unshared(file);
        }
    }

    private static KnownMet.KnownFile record(SharedFile file) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), file.name()));
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), file.size()));
        file.aichHash()
                .ifPresent(
                        h ->
                                tags.add(
                                        new Tag.StringTag(
                                                TagName.id(FileTag.AICH_HASH), h.toBase32())));
        tags.add(new Tag.IntTag(TagName.id(FileTag.LASTSHARED), System.currentTimeMillis() / 1000));
        return new KnownMet.KnownFile(file.mtime(), file.hash(), file.partHashes(), tags);
    }

    private void loadKnown() {
        Path met = this.configDir.resolve(KNOWN_MET);
        if (Files.exists(met) || Files.exists(AtomicFiles.bakPath(met.toAbsolutePath()))) {
            try {
                for (KnownMet.KnownFile file :
                        AtomicFiles.read(met, in -> KnownMet.read(in.readAllBytes()))) {
                    Optional<String> name = file.name();
                    if (name.isPresent() && file.size().isPresent()) {
                        this.known.put(
                                new Key(name.get(), file.size().getAsLong(), file.mtime()), file);
                    }
                }
            } catch (IOException e) {
                LOG.warn("Cannot read {}; files will be hashed again", met, e);
            }
        }
        Path met2 = this.configDir.resolve(KNOWN2_MET);
        if (Files.exists(met2)) {
            try {
                for (Known2Met.Entry entry : Known2Met.read(Files.readAllBytes(met2))) {
                    this.aich.put(entry.masterHash(), entry);
                }
            } catch (IOException e) {
                LOG.warn("Cannot read {}", met2, e);
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
            AtomicFiles.writeBytes(
                    this.configDir.resolve(KNOWN_MET),
                    KnownMet.write(List.copyOf(this.known.values())));
            AtomicFiles.writeBytes(
                    this.configDir.resolve(KNOWN2_MET),
                    Known2Met.write(List.copyOf(this.aich.values())));
        } catch (IOException e) {
            LOG.warn("Cannot save the known files", e);
        }
    }

    /**
     * Whether the files of {@code dir} must never be shared: the user's home directory, our
     * configuration and temp directories and what lies below them, and any directory holding a
     * client's private key (another client's configuration).
     */
    boolean isPrivate(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>(this.privateDirs.get());
        roots.add(this.configDir);
        for (Path root : roots) {
            if (d.startsWith(root.toAbsolutePath().normalize())) {
                return true;
            }
        }
        String home = System.getProperty("user.home");
        return (home != null && d.equals(Path.of(home).toAbsolutePath().normalize()))
                || Files.exists(d.resolve(CLIENT_KEY));
    }

    private static boolean isSkipped(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SKIPPED.contains(lower)
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
