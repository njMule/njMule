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
package org.njmule.core.persist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Rsa;
import org.njmule.core.crypto.RsaKeys;
import org.njmule.core.files.PartFile;
import org.njmule.core.files.hash.Ed2kHashSet;
import org.njmule.core.files.hash.FileHasher;
import org.njmule.core.kad.UInt128;
import org.njmule.core.persist.format.CancelledMet;
import org.njmule.core.persist.format.ClientsMet;
import org.njmule.core.persist.format.KnownMet;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.persist.format.PreferencesDat;
import org.njmule.core.persist.format.PreferencesKadDat;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.persist.format.TextFiles;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Bytes;
import org.njmule.core.util.Ipv4;

/** An eMule profile made up for tests: config, temp, incoming and one shared directory. */
public final class SyntheticEmuleProfile {

    public static final String NICK = "Tester";
    public static final long KAD_UDP_KEY = 305_419_896L;
    public static final int SHARED_SIZE = 70_000;
    public static final int DOWNLOAD_SIZE = 300_000;

    public final Path config;
    public final Path temp;
    public final Path incoming;
    public final Path shared;
    public final Path sharedFile;
    public final byte[] downloadData;
    public final Hash16 downloadHash;
    public final Hash16 halfHash;
    private final byte[] halfData;

    private SyntheticEmuleProfile(Path root, SecureRandom random) throws IOException {
        this.config = Files.createDirectories(root.resolve("config"));
        this.temp = Files.createDirectories(root.resolve("Temp"));
        this.incoming = Files.createDirectories(root.resolve("Incoming"));
        this.shared = Files.createDirectories(root.resolve("Shared"));
        this.sharedFile = this.shared.resolve("shared.bin");
        Files.write(this.sharedFile, randomBytes(random, SHARED_SIZE));
        this.downloadData = randomBytes(random, DOWNLOAD_SIZE);
        this.downloadHash = hash(root, this.downloadData);
        byte[] other = randomBytes(random, DOWNLOAD_SIZE);
        this.halfHash = hash(root, other);
        this.halfData = Arrays.copyOf(other, DOWNLOAD_SIZE);
        Arrays.fill(this.halfData, DOWNLOAD_SIZE / 2, DOWNLOAD_SIZE, (byte) 0);
    }

    private static Hash16 hash(Path root, byte[] data) throws IOException {
        Path scratch = Files.createTempFile(root, "download", ".bin");
        Files.write(scratch, data);
        Hash16 hash = FileHasher.hash(scratch).ed2k().fileHash();
        Files.delete(scratch);
        return hash;
    }

    /** Writes every file of the profile, with one complete download and one half done. */
    public static SyntheticEmuleProfile create(Path root) throws IOException {
        SecureRandom random = new SecureRandom();
        SyntheticEmuleProfile p = new SyntheticEmuleProfile(root, random);
        Files.writeString(
                p.config.resolve("preferences.ini"),
                String.join(
                        "\r\n",
                        "[eMule]",
                        "Nick=" + NICK,
                        "MaxUpload=50",
                        "MaxDownload=65535",
                        "MaxConnections=300",
                        "MaxSourcesPerFile=400",
                        "Reconnect=1",
                        "Autoconnect=0",
                        "RememberCancelledFiles=0",
                        "StoreSearches=0",
                        "AddNewFilesPaused=1",
                        "StartNextFile=2",
                        "NetworkED2K=1",
                        "NetworkKademlia=0",
                        "SecureIdent=1",
                        "UseCreditSystem=1",
                        "CryptLayerSupported=1",
                        "CryptLayerRequested=1",
                        "CryptLayerRequired=0",
                        "Port=61000",
                        "UDPPort=61001",
                        "IncomingDir=" + p.incoming,
                        "TempDir=" + p.temp,
                        "KadUDPKey=" + KAD_UDP_KEY,
                        ""),
                StandardCharsets.ISO_8859_1);
        Files.write(
                p.config.resolve("server.met"),
                ServerMet.write(List.of(
                        new ServerMet.Server(Ipv4.of(10, 1, 2, 3), 4661, List.of()),
                        new ServerMet.Server(Ipv4.of(10, 1, 2, 4), 4242, List.of()))));
        Files.write(
                p.config.resolve("nodes.dat"),
                NodesDat.write(List.of(
                        contact(random, Ipv4.of(10, 2, 0, 1)),
                        contact(random, Ipv4.of(10, 3, 0, 1)),
                        contact(random, Ipv4.of(10, 4, 0, 1)))));
        Ed2kHashSet hashes = FileHasher.hash(p.sharedFile).ed2k();
        Files.write(
                p.config.resolve("known.met"),
                KnownMet.write(List.of(new KnownMet.KnownFile(
                        Files.getLastModifiedTime(p.sharedFile).toMillis() / 1000,
                        hashes.fileHash(),
                        hashes.partHashes(),
                        List.of(
                                new Tag.StringTag(TagName.id(FileTag.FILENAME), "shared.bin"),
                                new Tag.IntTag(TagName.id(FileTag.FILESIZE), SHARED_SIZE))))));
        Files.write(
                p.config.resolve("shareddir.dat"),
                TextFiles.writeLines(
                        List.of(p.shared.toString(), p.root().resolve("gone").toString())));
        Files.write(p.config.resolve("sharedfiles.dat"), TextFiles.writeLines(List.of(p.sharedFile.toString())));
        Files.write(
                p.config.resolve("cancelled.met"), CancelledMet.write(7, List.of(CancelledMet.key(7, Hash16.ZERO))));
        Files.writeString(
                p.config.resolve("ipfilter.dat"),
                "010.000.000.000 - 010.000.000.255 , 000 , test range\n",
                StandardCharsets.US_ASCII);
        Files.write(
                p.config.resolve("preferences.dat"),
                PreferencesDat.write(new PreferencesDat.Contents(
                        PreferencesDat.VERSION, UserHashStore.generate(random), Bytes.EMPTY)));
        RsaKeys.writeKeyFile(p.config.resolve("cryptkey.dat"), Rsa.generate(Rsa.NETWORK_KEY_BITS, random));
        Files.write(
                p.config.resolve("clients.met"),
                ClientsMet.write(List.of(new ClientsMet.Credit(
                        UserHashStore.generate(random), 1_000, 2_000, 1_700_000_000L, Bytes.EMPTY))));
        Files.write(
                p.config.resolve("preferencesKad.dat"),
                PreferencesKadDat.write(new PreferencesKadDat.Contents(0, UInt128.random(random))));
        Files.write(p.config.resolve("key_index.dat"), new byte[] {4, 0, 0, 0});

        PartFile complete = p.download(1, "complete.bin", p.downloadHash);
        complete.written(0, DOWNLOAD_SIZE);
        complete.setStatus(PartFile.Status.PAUSED);
        complete.save();
        Files.write(complete.dataFile(), p.downloadData);
        PartFile half = p.download(2, "half.bin", p.halfHash);
        half.written(0, DOWNLOAD_SIZE / 2);
        half.save();
        Files.write(half.dataFile(), p.halfData);
        return p;
    }

    private Path root() {
        return this.config.getParent();
    }

    private PartFile download(int number, String name, Hash16 hash) {
        return PartFile.create(number, this.temp, name, DOWNLOAD_SIZE, hash, Optional.empty(), List.of());
    }

    /** SHA-256 of every file below the profile, to prove an import left it untouched. */
    public SortedMap<String, String> fingerprint() throws IOException {
        TreeMap<String, String> sums = new TreeMap<>();
        try (Stream<Path> files = Files.walk(this.root())) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                sums.put(
                        this.root().relativize(file).toString(),
                        HexFormat.of().formatHex(sha256(Files.readAllBytes(file)))
                                + "@"
                                + Files.getLastModifiedTime(file).toMillis());
            }
        }
        return sums;
    }

    private static NodesDat.Contact contact(SecureRandom random, int ip) {
        return new NodesDat.Contact(UInt128.random(random), ip, 4672, 4662, 8, 0, NodesDat.UdpKey.NONE, true);
    }

    private static byte[] randomBytes(SecureRandom random, int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        return data;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
