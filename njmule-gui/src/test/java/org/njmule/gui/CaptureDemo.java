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
package org.njmule.gui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.FileHistory;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadNote;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.SavedSearch;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.Schedule;

/** Example data for the capture tour: free files and documentation addresses (RFC 5737) only. */
final class CaptureDemo {

    static final SearchId LIVE_SEARCH = new SearchId(77);

    private CaptureDemo() {}

    static Hash16 hash(String text) {
        try {
            return Hash16.of(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // A part map with the given share done, some parts being requested and sources per part.
    static PartMap parts(String name, long size, double done, int maxSources) {
        Random random = new Random(name.hashCode());
        int count = (int) ((size + PartMap.PART_SIZE - 1) / PartMap.PART_SIZE);
        List<PartMap.Span> missing = new ArrayList<>();
        List<PartMap.Span> requested = new ArrayList<>();
        List<Integer> sources = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long start = i * PartMap.PART_SIZE;
            long end = Math.min(size, start + PartMap.PART_SIZE);
            boolean have = random.nextDouble() < done;
            if (!have) {
                if (random.nextDouble() < 0.08 && requested.size() < 4) {
                    long mid = start + (end - start) / 2;
                    requested.add(new PartMap.Span(mid, end));
                    missing.add(new PartMap.Span(mid, end));
                } else {
                    missing.add(new PartMap.Span(start, end));
                }
            }
            sources.add(maxSources == 0 ? 0 : random.nextInt(maxSources + 1));
        }
        return new PartMap(size, merge(missing), requested, sources);
    }

    private static List<PartMap.Span> merge(List<PartMap.Span> spans) {
        List<PartMap.Span> out = new ArrayList<>();
        for (PartMap.Span s : spans) {
            if (!out.isEmpty() && out.getLast().end() == s.start()) {
                out.set(out.size() - 1, new PartMap.Span(out.getLast().start(), s.end()));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static DownloadInfo download(
            String name,
            long size,
            double done,
            DownloadInfo.Status status,
            DownloadPriority priority,
            int category,
            int sources,
            int transferring,
            long rate,
            boolean preview) {
        PartMap parts =
                done >= 1 ? new PartMap(size, List.of(), List.of(), List.of()) : parts(name, size, done, sources);
        long missing =
                parts.missing().stream().mapToLong(s -> s.end() - s.start()).sum();
        return new DownloadInfo(
                hash(name),
                name,
                size,
                size - missing,
                status,
                priority,
                category,
                sources,
                transferring,
                rate,
                parts,
                preview,
                false);
    }

    static List<DownloadInfo> downloads() {
        return List.of(
                download(
                        "lumenos-4.2-amd64-dvd.iso",
                        3_994_091_520L,
                        0.62,
                        DownloadInfo.Status.DOWNLOADING,
                        DownloadPriority.HIGH,
                        1,
                        48,
                        6,
                        412_300,
                        false),
                download(
                        "Harbor.Lights.2024.1080p.x264.mkv",
                        1_245_822_976L,
                        0.88,
                        DownloadInfo.Status.DOWNLOADING,
                        DownloadPriority.AUTO,
                        2,
                        23,
                        3,
                        186_400,
                        true),
                download(
                        "Paper_Boats_2160p_60fps.mp4",
                        712_934_400L,
                        0.12,
                        DownloadInfo.Status.DOWNLOADING,
                        DownloadPriority.NORMAL,
                        2,
                        9,
                        0,
                        0,
                        false),
                download(
                        "Pinecone_Office_7.3_Win_x64.msi",
                        361_234_432L,
                        1.0,
                        DownloadInfo.Status.COMPLETE,
                        DownloadPriority.NORMAL,
                        0,
                        0,
                        0,
                        0,
                        false),
                download(
                        "Glass_Orchard_4k.mov",
                        6_442_450_944L,
                        0.34,
                        DownloadInfo.Status.PAUSED,
                        DownloadPriority.LOW,
                        2,
                        31,
                        0,
                        0,
                        false),
                download(
                        "Studio_Sessions_-_Complete_Collection.zip",
                        2_147_483_648L,
                        0.05,
                        DownloadInfo.Status.DOWNLOADING,
                        DownloadPriority.NORMAL,
                        3,
                        2,
                        1,
                        21_800,
                        false),
                download(
                        "openatlas-20260901-articles.xml.bz2",
                        5_153_960_755L,
                        0.0,
                        DownloadInfo.Status.DOWNLOADING,
                        DownloadPriority.AUTO,
                        0,
                        0,
                        0,
                        0,
                        false),
                download(
                        "highland-trails-2026.pbf",
                        2_040_109_465L,
                        0.47,
                        DownloadInfo.Status.STOPPED,
                        DownloadPriority.NORMAL,
                        0,
                        14,
                        0,
                        0,
                        false),
                download(
                        "The_Lantern_Keeper_-_audiobook.zip",
                        943_718_400L,
                        0.71,
                        DownloadInfo.Status.ERROR,
                        DownloadPriority.NORMAL,
                        3,
                        5,
                        0,
                        0,
                        false),
                download(
                        "lumenos-5.0-beta-desktop-amd64.iso",
                        6_291_456_000L,
                        0.20,
                        DownloadInfo.Status.NO_SPACE,
                        DownloadPriority.HIGH,
                        1,
                        120,
                        0,
                        0,
                        false),
                download(
                        "Northern_Meadow_2022_1080p.mkv",
                        1_503_238_553L,
                        0.95,
                        DownloadInfo.Status.DOWNLOADING,
                        DownloadPriority.HIGH,
                        2,
                        17,
                        4,
                        702_000,
                        true));
    }

    static List<Category> categories() {
        return List.of(
                new Category(
                        1,
                        "Systems",
                        Optional.empty(),
                        "Disk images",
                        2,
                        DownloadPriority.NORMAL,
                        "*.iso|*.img",
                        false),
                new Category(
                        2,
                        "Films",
                        Optional.empty(),
                        "Short films",
                        4,
                        DownloadPriority.NORMAL,
                        "*.mkv|*.mp4|*.mov",
                        false),
                new Category(
                        3,
                        "Music and audiobooks",
                        Optional.empty(),
                        "",
                        6,
                        DownloadPriority.LOW,
                        "*.mp3|*.flac|*.zip",
                        false));
    }

    static List<UploadInfo> uploads() {
        List<UploadInfo> list = new ArrayList<>(List.of(
                new UploadInfo(
                        "192.0.2.17:4662",
                        hash("u1"),
                        Optional.of("Sandro_88"),
                        "eMule v0.70b",
                        "Harbor.Lights.2024.1080p.x264.mkv",
                        UploadInfo.State.UPLOADING,
                        48_234_496,
                        41_200,
                        0),
                new UploadInfo(
                        "198.51.100.5:5662",
                        hash("u2"),
                        Optional.empty(),
                        "aMule 2.3.3",
                        "lumenos-4.2-amd64-dvd.iso",
                        UploadInfo.State.UPLOADING,
                        12_582_912,
                        23_900,
                        0),
                new UploadInfo(
                        "203.0.113.33:4672",
                        hash("u3"),
                        Optional.of("Owl"),
                        "eMule Community v0.72",
                        "Pinecone_Office_7.3_Win_x64.msi",
                        UploadInfo.State.UPLOADING,
                        120_586_240,
                        33_000,
                        0)));
        String[] nicks = {"marta.r", "", "Ranger", "penguin", "", "Fog", "bookworm"};
        String[] software = {
            "eMule v0.70b", "aMule 2.3.3", "eMule v0.50a", "njMule 0.1.0", "Shareaza 2.7", "eMule v0.70b", "aMule 2.3.2"
        };
        String[] files = {
            "Harbor.Lights.2024.1080p.x264.mkv",
            "lumenos-4.2-amd64-dvd.iso",
            "Paper_Boats_2160p_60fps.mp4",
            "Pinecone_Office_7.3_Win_x64.msi",
            "Studio_Sessions_-_Complete_Collection.zip",
            "highland-trails-2026.pbf",
            "The_Lantern_Keeper_-_audiobook.zip"
        };
        for (int i = 0; i < nicks.length; i++) {
            list.add(new UploadInfo(
                    "203.0.113." + (60 + i) + ":" + (4662 + i),
                    hash("q" + i),
                    nicks[i].isEmpty() ? Optional.empty() : Optional.of(nicks[i]),
                    software[i],
                    files[i],
                    UploadInfo.State.QUEUED,
                    0,
                    0,
                    300L + i * 977L));
        }
        return list;
    }

    // Most sources give the name of the link, some another form of it, a few none yet.
    private static Optional<String> reportedName(String name, int n) {
        return switch (n % 4) {
            case 0, 1 -> Optional.of(name);
            case 2 -> Optional.of(name.replace(' ', '_').replace('-', '.'));
            default -> Optional.empty();
        };
    }

    static List<SourceInfo> sources() {
        List<SourceInfo> list = new ArrayList<>();
        List<DownloadInfo> downloads = downloads();
        String[] nicks = {"Fox", "", "Kiwi", "", "astro_nauta", "Lynx", "Heron", ""};
        int n = 0;
        for (DownloadInfo d : List.of(downloads.get(0), downloads.get(1), downloads.get(10))) {
            int partCount = (int) ((d.size() + PartMap.PART_SIZE - 1) / PartMap.PART_SIZE);
            for (SourceInfo.State state : SourceInfo.State.values()) {
                boolean a4af = state == SourceInfo.State.A4AF;
                list.add(new SourceInfo(
                        d.hash(),
                        d.name(),
                        "198.51.100." + (20 + n) + ":" + (4662 + n),
                        nicks[n % nicks.length].isEmpty() ? Optional.empty() : Optional.of(nicks[n % nicks.length]),
                        n % 3 == 0 ? "eMule v0.70b" : n % 3 == 1 ? "aMule 2.3.3" : "eMule Community v0.72",
                        state,
                        state == SourceInfo.State.QUEUED ? 12 + n * 7 : 0,
                        state == SourceInfo.State.DOWNLOADING ? 31_457_280L + n * 1_000_000L : 0,
                        state == SourceInfo.State.WAITING ? 0 : (partCount * (n % 5 + 1)) / 6,
                        state == SourceInfo.State.WAITING ? 0 : partCount,
                        a4af ? Optional.of(downloads.get(2).hash()) : Optional.empty(),
                        a4af ? downloads.get(2).name() : "",
                        reportedName(d.name(), n)));
                n++;
            }
        }
        return list;
    }

    static List<BanInfo> bans() {
        return List.of(new BanInfo("192.0.2.200", 5400), new BanInfo("198.51.100.250", 840));
    }

    static ServerInfo server(int i) {
        String[][] s = {
            {"Lighthouse", "Stable, no fake files", "203.0.113.10:4661"},
            {"North Wind", "Fast searches, obfuscation", "203.0.113.11:4232"},
            {"Southwest", "Verified files only", "198.51.100.40:4661"},
            {"Nordlicht", "Deutscher Server", "192.0.2.44:7111"},
            {"Northeast", "", "198.51.100.41:5687"},
            {"Aurora Hub", "Low ID friendly", "203.0.113.12:4184"},
            {"Sirocco", "Test server", "192.0.2.45:4661"},
            {"Mistral", "Silent for days", "192.0.2.46:4661"}
        };
        long[] users = {412_345, 287_904, 97_320, 54_201, 12_880, 8_410, 1_204, 0};
        return new ServerInfo(
                s[i][2],
                s[i][0],
                s[i][1],
                users[i],
                users[i] * 61,
                users[i] == 0 ? 0 : (users[i] / 1000 + 1) * 1100,
                i == 7 ? 4 : i == 6 ? 1 : 0,
                i < 2 ? ServerInfo.Priority.HIGH : i == 6 ? ServerInfo.Priority.LOW : ServerInfo.Priority.NORMAL,
                i == 7 ? 0 : 24 + i * 13,
                users[i] * 70,
                users[i] * 90,
                users[i] / 5,
                i % 2 == 0 ? "17.15" : "17.13",
                i != 4,
                i < 3);
    }

    static List<ServerInfo> servers() {
        List<ServerInfo> list = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            list.add(server(i));
        }
        return list;
    }

    static KadInfo kad() {
        return new KadInfo(
                true,
                1243,
                873,
                false,
                "8F3C2A1D90B44E6E9A0C7E51D2B3F4A5",
                "198.51.100.23",
                4672,
                false,
                true,
                KadInfo.Buddy.NONE);
    }

    static EngineStatus status() {
        return status(1_322_500, 98_100);
    }

    // The status with the rates of this moment, in bytes a second.
    static EngineStatus status(long down, long up) {
        return new EngineStatus(
                Optional.of(server(0)),
                0x17643C6AL,
                false,
                true,
                Optional.of(kad()),
                down,
                up,
                2_576_980_378L,
                640_942_080L,
                11,
                3,
                7,
                26,
                442_381_959_168L,
                308_163_100_672L,
                214,
                3 * 3600 + 42 * 60,
                1_911L * 3600,
                "198.51.100.23",
                4662,
                true);
    }

    static List<KadContactInfo> kadContacts() {
        Random random = new Random(7);
        List<KadContactInfo> list = new ArrayList<>();
        // As many as the Kad state says: most far away, a few at every closer distance.
        for (int i = 0; i < 1243; i++) {
            byte[] id = new byte[16];
            random.nextBytes(id);
            String hex = java.util.HexFormat.of().withUpperCase().formatHex(id);
            java.math.BigInteger random128 = new java.math.BigInteger(1, id);
            java.math.BigInteger distance = i < 1083
                    ? random128
                    : random128.shiftRight((i - 1083) / 10 + 6).setBit(127 - ((i - 1083) / 10 + 5));
            list.add(new KadContactInfo(
                    hex,
                    "198.51.100." + random.nextInt(250) + ":" + (4000 + random.nextInt(60000)),
                    random.nextInt(5),
                    String.format("%032X", distance),
                    8 + random.nextInt(2),
                    random.nextInt(4) != 0));
        }
        return list;
    }

    static List<KadSearchInfo> kadSearches() {
        return List.of(
                new KadSearchInfo("lumenos", KadSearchInfo.Kind.KEYWORD, 37),
                new KadSearchInfo(hash("lumenos-4.2-amd64-dvd.iso").toString(), KadSearchInfo.Kind.SOURCES, 12),
                new KadSearchInfo("8F3C2A1D90B44E6E9A0C7E51D2B3F4A5", KadSearchInfo.Kind.NODE, 9),
                new KadSearchInfo(hash("Harbor.Lights.2024.1080p.x264.mkv").toString(), KadSearchInfo.Kind.NOTES, 2),
                new KadSearchInfo("harbor", KadSearchInfo.Kind.STORE_KEYWORD, 4),
                new KadSearchInfo(
                        hash("Pinecone_Office_7.3_Win_x64.msi").toString(), KadSearchInfo.Kind.STORE_SOURCE, 6));
    }

    static List<SharedFileInfo> shared() {
        List<SharedFileInfo> list = new ArrayList<>();
        String[][] files = {
            {"Systems", "lumenos-3.9-amd64-netinst.iso", "662700032"},
            {"Systems", "lumenos-server-4.2-x86_64.iso", "2343567360"},
            {"Systems", "tidewater-desktop-2.1-64bit.iso", "2985123840"},
            {"Systems", "quartz-rolling-2026.10.01-x86_64.iso", "1288490188"},
            {"Films", "Copper_Kite_1080p.avi", "854589440"},
            {"Films", "Salt_and_Ember_2015_1080p.mkv", "1395864371"},
            {"Films", "The_Quiet_Valley_1968.mp4", "1103101952"},
            {"Music", "Aurora_Quartet_-_Night_Sketches.flac.zip", "377487360"},
            {"Music", "Elm_Street_Ensemble_-_Nocturnes.zip", "214958080"},
            {"Music", "Studio_Sessions_-_Weather_Report.mp3", "7340032"},
            {"Books", "Mara_Vale_-_The_Long_Road_Home.epub", "1572864"},
            {"Books", "The_Lantern_Keeper.pdf", "4823449"},
            {"Books", "OpenAtlas_-_Selection_2026.zim", "4294967296"},
            {"Software", "Pinecone_Office_7.3_Win_x64.msi", "361234432"},
            {"Software", "PixelLoom-3.2-setup.exe", "350224384"},
            {"Software", "Vectorly-1.6-x64.msi", "135266304"},
            {"Software", "Meshworks-2.8-windows-x64.msi", "382730240"},
            {"Maps", "highland-trails-north-2026.pbf", "1288490188"}
        };
        UploadPriority[] priorities = UploadPriority.values();
        for (int i = 0; i < files.length; i++) {
            list.add(new SharedFileInfo(
                    hash(files[i][1]),
                    files[i][1],
                    Long.parseLong(files[i][2]),
                    Path.of("D:\\Shared", files[i][0], files[i][1]),
                    priorities[(i * 5) % priorities.length]));
        }
        return list;
    }

    static List<SharedDirectory> sharedDirectories() {
        return List.of(
                new SharedDirectory(Path.of("D:\\Shared"), true),
                new SharedDirectory(Path.of("E:\\Archive\\Music"), false),
                new SharedDirectory(Path.of("E:\\Archive\\Books"), true));
    }

    static List<Friend> friends() {
        return List.of(
                new Friend(hash("f1"), "Owl", Optional.of(new ServerAddress("203.0.113.33", 4672))),
                new Friend(hash("f2"), "marta.r", Optional.of(new ServerAddress("203.0.113.60", 4662))),
                new Friend(hash("f3"), "Ranger", Optional.empty()));
    }

    private static SearchResult result(
            String name, long size, int sources, int complete, String type, SearchResult.Media media) {
        return new SearchResult(hash(name), name, size, sources, complete, Optional.ofNullable(type), media);
    }

    static List<SearchResult> systemResults() {
        return List.of(
                result("lumenos-4.2-amd64-dvd.iso", 3_994_091_520L, 214, 180, "Iso", SearchResult.Media.NONE),
                result("lumenos-4.2-amd64-netinst.iso", 702_545_920L, 512, 498, "Iso", SearchResult.Media.NONE),
                result("lumenos-4.2-arm64-netinst.iso", 593_494_016L, 87, 80, "Iso", SearchResult.Media.NONE),
                result("lumenos-3.9-amd64-netinst.iso", 662_700_032L, 301, 290, "Iso", SearchResult.Media.NONE),
                result("lumenos-live-4.2-amd64-maple.iso", 3_481_272_320L, 45, 31, "Iso", SearchResult.Media.NONE),
                result("lumenos-live-4.2-amd64-birch.iso", 3_212_836_864L, 52, 40, "Iso", SearchResult.Media.NONE),
                result("LumenOS_Handbook_4_en.pdf", 9_437_184L, 33, 33, "Doc", SearchResult.Media.NONE),
                result("lumenos-4-cheatsheet.png", 1_258_291L, 12, 12, "Image", SearchResult.Media.NONE),
                result("lumenos 4 ULTIMATE PRO EDITION.exe", 2_457_600L, 3, 0, "Pro", SearchResult.Media.NONE),
                result(
                        "lumenos-installer-tutorial.mp4",
                        156_237_824L,
                        8,
                        2,
                        "Video",
                        new SearchResult.Media("", "", "Installing LumenOS 4", 1_260, 2_400, "h264")),
                result("lumenos-4.2-i386-netinst.iso", 650_117_120L, 19, 17, "Iso", SearchResult.Media.NONE),
                result("lumenos-4.2-amd64-dvd.iso.torrent", 77_824L, 4, 4, null, SearchResult.Media.NONE));
    }

    static List<SearchResult> musicResults() {
        return List.of(
                result(
                        "Aurora_Quartet_-_Night_Sketches_-_Prelude.flac",
                        31_457_280L,
                        41,
                        41,
                        "Audio",
                        new SearchResult.Media("Aurora Quartet", "Night Sketches", "Prelude", 263, 1_010, "flac")),
                result(
                        "Elm_Street_Ensemble_-_Nocturne_No2.mp3",
                        7_340_032L,
                        120,
                        118,
                        "Audio",
                        new SearchResult.Media("Elm Street Ensemble", "Nocturnes", "Nocturne No. 2", 271, 320, "mp3")),
                result(
                        "Elm_Street_Ensemble_-_Four_Winds_-_Spring.ogg",
                        12_582_912L,
                        66,
                        60,
                        "Audio",
                        new SearchResult.Media("Elm Street Ensemble", "The Four Winds", "Spring", 612, 160, "vorbis")),
                result(
                        "Lena_Hart_-_Moonlit_Sonata.mp3",
                        14_680_064L,
                        95,
                        90,
                        "Audio",
                        new SearchResult.Media("Lena Hart", "Sonatas", "Moonlit", 917, 128, "mp3")),
                result(
                        "Studio_Sessions_-_Slow_Waltz_1.flac",
                        26_214_400L,
                        23,
                        20,
                        "Audio",
                        new SearchResult.Media(
                                "Studio Sessions", "Quiet Pieces", "Slow Waltz No. 1", 189, 1_100, "flac")),
                result("night_music_collection_2026.zip", 1_073_741_824L, 6, 1, "Arc", SearchResult.Media.NONE));
    }

    static List<SearchResult> liveResults() {
        return List.of(
                result(
                        "Paper_Boats_2160p_60fps.mp4",
                        712_934_400L,
                        77,
                        70,
                        "Video",
                        new SearchResult.Media("", "", "Paper Boats", 634, 8_900, "h264")),
                result(
                        "Paper_Boats_1080p_surround.avi",
                        928_514_048L,
                        140,
                        133,
                        "Video",
                        new SearchResult.Media("", "", "Paper Boats", 596, 12_000, "mpeg4")),
                result(
                        "paper_boats_480p_stereo.ogg",
                        157_286_400L,
                        29,
                        25,
                        "Video",
                        new SearchResult.Media("", "", "Paper Boats", 596, 2_000, "theora")),
                result(
                        "PaperBoats_soundtrack.flac",
                        104_857_600L,
                        11,
                        11,
                        "Audio",
                        new SearchResult.Media("Jonas Reed", "Paper Boats OST", "Main theme", 418, 1_000, "flac")));
    }

    static List<SavedSearch> savedSearches() {
        return List.of(
                new SavedSearch("lumenos", new SearchRequest("lumenos", SearchRequest.Scope.GLOBAL), systemResults()),
                new SavedSearch(
                        "night music", new SearchRequest("night music", SearchRequest.Scope.KAD), musicResults()));
    }

    static SpamMarks spam() {
        return new SpamMarks(Set.of(hash("lumenos 4 ULTIMATE PRO EDITION.exe")), Set.of(), Set.of());
    }

    static FileHistory history() {
        return FileHistory.of(Set.of(hash("lumenos-3.9-amd64-netinst.iso")), h -> false);
    }

    static List<LogLine> log() {
        Instant now = Instant.parse("2026-10-15T08:40:00Z");
        return List.of(
                new LogLine(now, LogLine.Level.INFO, "njMule started"),
                new LogLine(now.plusSeconds(1), LogLine.Level.INFO, "26 shared files in 3 folders"),
                new LogLine(now.plusSeconds(2), LogLine.Level.DEBUG, "UPnP, NAT-PMP and PCP: no router answered"),
                new LogLine(now.plusSeconds(3), LogLine.Level.INFO, "Connecting to Lighthouse (203.0.113.10:4661)…"),
                new LogLine(now.plusSeconds(5), LogLine.Level.SUCCESS, "Connected to Lighthouse with a high ID"),
                new LogLine(now.plusSeconds(9), LogLine.Level.INFO, "Kad: connected, 1243 contacts"),
                new LogLine(
                        now.plusSeconds(40),
                        LogLine.Level.WARNING,
                        "Not enough space for lumenos-5.0-beta-desktop-amd64.iso"),
                new LogLine(
                        now.plusSeconds(61),
                        LogLine.Level.ERROR,
                        "The_Lantern_Keeper_-_audiobook.zip: part 41 corrupt, downloaded again"),
                new LogLine(
                        now.plusSeconds(90),
                        LogLine.Level.SUCCESS,
                        "Download completed: Pinecone_Office_7.3_Win_x64.msi"));
    }

    static Schedule schedule() {
        return new Schedule(List.of(
                new Schedule.Rule(
                        "Night",
                        true,
                        EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                        LocalTime.of(1, 0),
                        LocalTime.of(7, 0),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.of(800),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.of(1)),
                new Schedule.Rule(
                        "Office hours",
                        true,
                        EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                        LocalTime.of(9, 0),
                        LocalTime.of(18, 0),
                        OptionalInt.of(30),
                        OptionalInt.of(200),
                        OptionalInt.of(150),
                        OptionalInt.of(100),
                        OptionalInt.of(2),
                        OptionalInt.empty()),
                new Schedule.Rule(
                        "Weekend",
                        false,
                        EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                        LocalTime.of(0, 0),
                        LocalTime.of(0, 0),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        OptionalInt.empty())));
    }

    static ImportReport importReport() {
        return new ImportReport(
                true,
                List.of(
                        new ImportReport.Item(
                                "preferences.ini",
                                ImportReport.Outcome.WOULD_IMPORT,
                                List.of(phrase("nj.import.d.preferences"), phrase("nj.import.d.portsOurs"))),
                        new ImportReport.Item(
                                "server.met",
                                ImportReport.Outcome.WOULD_IMPORT,
                                List.of(phrase("nj.import.d.servers", 214))),
                        new ImportReport.Item(
                                "known.met",
                                ImportReport.Outcome.WOULD_IMPORT,
                                List.of(phrase("nj.import.d.knownFiles", 1208))),
                        new ImportReport.Item(
                                "clients.met",
                                ImportReport.Outcome.WOULD_IMPORT,
                                List.of(phrase("nj.import.d.credits", 3402))),
                        new ImportReport.Item(
                                "cryptkey.dat",
                                ImportReport.Outcome.SKIPPED,
                                List.of(phrase("nj.import.d.ownIdentity"))),
                        new ImportReport.Item(
                                "nodes.dat",
                                ImportReport.Outcome.WOULD_IMPORT,
                                List.of(phrase("nj.import.d.kadContacts", 512))),
                        new ImportReport.Item("ipfilter.dat", ImportReport.Outcome.MISSING, List.of()),
                        new ImportReport.Item(
                                "Category.ini",
                                ImportReport.Outcome.WOULD_IMPORT,
                                List.of(phrase("nj.import.d.categories", 3))),
                        new ImportReport.Item("staticservers.dat", ImportReport.Outcome.FAILED, "line 4 unreadable")),
                false);
    }

    private static ImportReport.Phrase phrase(String key, Object... args) {
        return ImportReport.Phrase.of(key, args);
    }

    // What only events tell: the connection, the log, what the server said and the chats, one of them asking for a
    // CAPTCHA.
    static List<EngineEvent> events() {
        List<EngineEvent> events = new ArrayList<>();
        events.add(new EngineEvent.ServerConnecting(server(0)));
        for (LogLine line : log()) {
            events.add(new EngineEvent.Log(line));
        }
        events.add(new EngineEvent.ServerConnected(server(0), 0x17643C6AL, false, true));
        events.add(new EngineEvent.ServerMessage(server(0), "Welcome to Lighthouse! Rules: no fake files, no spam."));
        events.add(new EngineEvent.MessageReceived(
                "203.0.113.33:4672", hash("f1"), Optional.of("Owl"), "Hi! Do you have the 4K one too?"));
        events.add(new EngineEvent.MessageReceived(
                "203.0.113.33:4672", hash("f1"), Optional.of("Owl"), "Thanks for Harbor Lights :)"));
        events.add(new EngineEvent.MessageReceived(
                "198.51.100.77:4662", hash("x9"), Optional.of("Fog"), "May I add you as a friend?"));
        events.add(new EngineEvent.CaptchaRequested("192.0.2.99:4662", hash("c9"), captcha()));
        return events;
    }

    private static byte[] captcha() {
        BufferedImage img = new BufferedImage(120, 36, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xEEEEEE));
        g.fillRect(0, 0, 120, 36);
        g.setColor(new Color(0x334455));
        g.setFont(g.getFont().deriveFont(22f));
        g.drawString("K7Q2M", 18, 27);
        g.drawLine(5, 20, 115, 12);
        g.dispose();
        ByteArrayOutputStream bmp = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "bmp", bmp);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bmp.toByteArray();
    }

    static Map<String, Engines.Command> commands(Path dir) {
        Map<String, Engines.Command> c = new HashMap<>();
        c.put("downloads", a -> CompletableFuture.completedFuture(downloads()));
        c.put("categories", a -> CompletableFuture.completedFuture(categories()));
        c.put("uploads", a -> CompletableFuture.completedFuture(uploads()));
        c.put("sources", a -> CompletableFuture.completedFuture(sources()));
        c.put("bans", a -> CompletableFuture.completedFuture(bans()));
        c.put("servers", a -> CompletableFuture.completedFuture(servers()));
        c.put("status", a -> CompletableFuture.completedFuture(status()));
        c.put("kad", a -> CompletableFuture.completedFuture(Optional.of(kad())));
        c.put("kadContacts", a -> CompletableFuture.completedFuture(kadContacts()));
        c.put("kadSearches", a -> CompletableFuture.completedFuture(kadSearches()));
        c.put("sharedFiles", a -> CompletableFuture.completedFuture(shared()));
        c.put("sharedDirectories", a -> CompletableFuture.completedFuture(sharedDirectories()));
        c.put("friends", a -> CompletableFuture.completedFuture(friends()));
        c.put("savedSearches", a -> CompletableFuture.completedFuture(savedSearches()));
        c.put("saveSearches", a -> CompletableFuture.completedFuture(null));
        c.put("spamMarks", a -> CompletableFuture.completedFuture(spam()));
        c.put("fileHistory", a -> CompletableFuture.completedFuture(history()));
        c.put("search", a -> CompletableFuture.completedFuture(LIVE_SEARCH));
        c.put("moreResults", a -> CompletableFuture.completedFuture(true));
        c.put("stopSearch", a -> CompletableFuture.completedFuture(null));
        c.put("connect", a -> CompletableFuture.completedFuture(null));
        c.put("disconnect", a -> CompletableFuture.completedFuture(null));
        c.put("connectKad", a -> CompletableFuture.completedFuture(null));
        c.put("disconnectKad", a -> CompletableFuture.completedFuture(null));
        c.put("sendMessage", a -> CompletableFuture.completedFuture(null));
        c.put(
                "peerComments",
                a -> CompletableFuture.completedFuture(List.of(
                        new PeerComment(
                                "Fox",
                                "Harbor.Lights.2024.1080p.x264.mkv",
                                FileComment.of("Great quality, 5.1 audio", 5)),
                        new PeerComment("Kiwi", "Harbor_Lights_1080p.mkv", FileComment.of("Subtitles included", 4)))));
        c.put(
                "kadNotes",
                a -> CompletableFuture.completedFuture(List.of(new KadNote(
                        "astro_nauta", "Harbor.Lights.2024.1080p.x264.mkv", FileComment.of("Verified file", 5)))));
        c.put(
                "searchKadNotes",
                a -> CompletableFuture.completedFuture(List.of(new KadNote(
                        "astro_nauta", "Harbor.Lights.2024.1080p.x264.mkv", FileComment.of("Verified file", 5)))));
        c.put(
                "fileComment",
                a -> CompletableFuture.completedFuture(Optional.of(FileComment.of("Cinema cut, 24 fps", 4))));
        c.put("setFileComment", a -> CompletableFuture.completedFuture(null));
        c.put(
                "browseSharedFiles",
                a -> CompletableFuture.completedFuture(List.of(
                        new SearchResult(
                                hash("a"),
                                "Copper_Kite_1080p.avi",
                                854_589_440L,
                                1,
                                1,
                                Optional.of("Video"),
                                SearchResult.Media.NONE),
                        new SearchResult(
                                hash("b"),
                                "Harbor.Lights.2024.1080p.x264.mkv",
                                1_245_822_976L,
                                1,
                                1,
                                Optional.of("Video"),
                                SearchResult.Media.NONE),
                        new SearchResult(
                                hash("c"),
                                "Aurora_Quartet_-_Night_Sketches_-_Prelude.flac",
                                31_457_280L,
                                1,
                                1,
                                Optional.of("Audio"),
                                SearchResult.Media.NONE),
                        new SearchResult(
                                hash("d"),
                                "The_Lantern_Keeper.pdf",
                                4_823_449L,
                                1,
                                1,
                                Optional.of("Doc"),
                                SearchResult.Media.NONE))));
        c.put("importFromEmule", a -> CompletableFuture.completedFuture(importReport()));
        c.put(
                "serverListAddresses",
                a -> CompletableFuture.completedFuture(List.of(
                        URI.create("https://example.org/ed2k/server.met"),
                        URI.create("https://example.net/lists/server.met"))));
        c.put("friendSlot", a -> CompletableFuture.completedFuture(Optional.of(hash("f1"))));
        c.put("downloadData", a -> CompletableFuture.completedFuture(Optional.of(dir.resolve("data.part"))));
        return c;
    }
}
