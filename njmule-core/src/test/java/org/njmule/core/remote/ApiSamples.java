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

import java.net.URI;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.ImportReport;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadNote;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;

/** Records of the engine with every field set, as the API's answers and events carry them. */
final class ApiSamples {

    static final String HASH = "AB".repeat(16);
    static final String USER = "CD".repeat(16);

    private ApiSamples() {}

    static ServerInfo server() {
        return new ServerInfo(
                "203.0.113.10:4661",
                "Lighthouse",
                "Stable",
                412_345,
                25_153_045,
                454_300,
                0,
                ServerInfo.Priority.HIGH,
                24,
                28_864_150,
                37_111_050,
                82_469,
                "17.15",
                true,
                false);
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

    /** A status connected to a server and to Kad, with every field the API may leave out, or connected to neither. */
    static EngineStatus status(boolean connected) {
        return new EngineStatus(
                connected ? Optional.of(server()) : Optional.empty(),
                0x17643C6AL,
                false,
                true,
                connected ? Optional.of(kad()) : Optional.empty(),
                1_322_500,
                98_100,
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
                true,
                connected ? Optional.of("eth9") : Optional.empty(),
                connected ? Optional.of("2001:db8::23") : Optional.empty());
    }

    static DownloadInfo download() {
        return new DownloadInfo(
                Hash16.parseHex(HASH),
                "talk.bin",
                5000,
                1200,
                DownloadInfo.Status.DOWNLOADING,
                DownloadPriority.HIGH,
                1,
                4,
                2,
                30_000,
                PartMap.EMPTY);
    }

    static SourceInfo source() {
        return new SourceInfo(
                Hash16.parseHex(HASH),
                "talk.bin",
                "198.51.100.7:4662",
                Optional.of("someone"),
                "eMule v0.70b",
                SourceInfo.State.QUEUED,
                12,
                0,
                3,
                1,
                Optional.empty(),
                "",
                Optional.of("talk (1).bin"));
    }

    static UploadInfo upload() {
        return new UploadInfo(
                "198.51.100.8:4662",
                Hash16.parseHex(USER),
                Optional.empty(),
                "aMule 2.3.3",
                "talk.bin",
                UploadInfo.State.UPLOADING,
                1_048_576,
                20_000,
                95);
    }

    static SharedFileInfo shared() {
        return new SharedFileInfo(
                Hash16.parseHex(HASH), "talk.bin", 5000, Path.of("shared", "talk.bin"), UploadPriority.VERY_LOW);
    }

    static SharedDirectory directory() {
        return new SharedDirectory(Path.of("shared"), true);
    }

    static SearchResult result() {
        return new SearchResult(
                Hash16.parseHex(HASH),
                "talk.bin",
                5000,
                3,
                -1,
                Optional.of("Pro"),
                new SearchResult.Media("Ann", "Talks", "Opening", 3725, 192, "mp3"),
                SearchResult.Origin.NONE,
                70,
                Optional.empty(),
                List.of(new SearchResult.Name("talk.bin", 2, 1), new SearchResult.Name("talk (2).bin", 1, -1)));
    }

    static Category category() {
        return new Category(
                1, "Talks", Optional.of(Path.of("talks").toAbsolutePath()), "", 0, DownloadPriority.AUTO, "", false);
    }

    static Friend friend() {
        return new Friend(Hash16.parseHex(USER), "Ann", Optional.of(ServerAddress.parse("198.51.100.9:4662")));
    }

    static Schedule schedule() {
        return new Schedule(List.of(new Schedule.Rule(
                "Night",
                true,
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
                LocalTime.of(23, 0),
                LocalTime.of(6, 0),
                OptionalInt.empty(),
                OptionalInt.of(0),
                OptionalInt.of(300),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.of(1))));
    }

    static PortTest portTest() {
        return new PortTest(
                4662,
                4672,
                PortTest.Forwarding.FORWARDED,
                "192.0.2.1",
                PortTest.Reach.OPEN,
                PortTest.Reach.TESTING,
                false);
    }

    static ImportReport importReport() {
        return new ImportReport(
                true,
                List.of(
                        new ImportReport.Item(
                                "server.met",
                                ImportReport.Outcome.WOULD_IMPORT,
                                List.of(
                                        ImportReport.Phrase.of("nj.import.d.servers", 12),
                                        ImportReport.Phrase.of("nj.import.d.added", 3))),
                        new ImportReport.Item("known.met", ImportReport.Outcome.FAILED, "unreadable"),
                        new ImportReport.Item("ipfilter.dat", ImportReport.Outcome.MISSING, "")),
                true);
    }

    static PeerComment peerComment() {
        return new PeerComment("Ann", "talk.bin", FileComment.of("clean copy", 4));
    }

    static KadNote kadNote() {
        return new KadNote("Bob", "talk (2).bin", FileComment.of("fake", 1));
    }

    static BanInfo ban() {
        return new BanInfo("192.0.2.200", 5400);
    }

    static KadContactInfo kadContact() {
        return new KadContactInfo("8F3C2A1D90B44E6E9A0C7E51D2B3F4A5", "198.51.100.40:4672", 2, "8F3C2A1D…", 9, true);
    }

    static KadSearchInfo kadSearch() {
        return new KadSearchInfo("debian", KadSearchInfo.Kind.STORE_KEYWORD, 37);
    }

    static LogLine logLine() {
        return new LogLine(Instant.parse("2026-10-18T08:00:00Z"), LogLine.Level.SUCCESS, "Connected");
    }

    static NewVersion newVersion() {
        return new NewVersion(
                "0.2.0",
                Instant.parse("2026-11-02T10:00:00Z"),
                true,
                "### Added\n\n- Updates.",
                URI.create("https://github.com/njMule/njMule/releases/tag/v0.2.0"),
                Optional.of(new NewVersion.Download(
                        URI.create("https://github.com/njMule/njMule/releases/download/v0.2.0/"
                                + "njmule-0.2.0-windows-x64.msi"),
                        31_719_424,
                        "ab".repeat(32))));
    }

    static UpdateStatus updateStatus() {
        return new UpdateStatus(
                "0.1.0",
                InstallKind.MSI,
                UpdateStatus.Installer.SELF,
                false,
                Optional.of(Instant.parse("2026-11-03T08:00:00Z")),
                Optional.of("HTTP 503 from github.com"),
                Optional.of(newVersion()),
                Optional.of("0.1.5"),
                Optional.of(new UpdateStatus.Progress(1_048_576, 31_719_424)),
                Optional.empty(),
                UpdateStatus.Install.ASKED);
    }

    /** A version downloaded, which the user asked to install now. */
    static UpdateStatus restartingUpdateStatus() {
        return new UpdateStatus(
                "0.1.0",
                InstallKind.WINDOWS_ZIP,
                UpdateStatus.Installer.SELF,
                false,
                Optional.of(Instant.parse("2026-11-03T08:00:00Z")),
                Optional.empty(),
                Optional.of(newVersion()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Path.of("cache", "update", "0.2.0", "njmule-0.2.0-windows-x64.zip")),
                UpdateStatus.Install.RESTART);
    }

    /** A version downloaded, to install when njMule exits. */
    static UpdateStatus atExitUpdateStatus() {
        return new UpdateStatus(
                "0.1.0",
                InstallKind.WINDOWS_ZIP,
                UpdateStatus.Installer.SELF,
                false,
                Optional.of(Instant.parse("2026-11-03T08:00:00Z")),
                Optional.empty(),
                Optional.of(newVersion()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Path.of("cache", "update", "0.2.0", "njmule-0.2.0-windows-x64.zip")),
                UpdateStatus.Install.AT_EXIT);
    }

    /** One event of every kind the engine announces. */
    static List<EngineEvent> events() {
        return List.of(
                new EngineEvent.Started("0.1.0"),
                new EngineEvent.Stopping(),
                new EngineEvent.PreferencesChanged(Preferences.defaults()),
                new EngineEvent.Log(logLine()),
                new EngineEvent.ServerConnecting(server()),
                new EngineEvent.ServerConnected(server(), 0x17643C6AL, false, true),
                new EngineEvent.ServerDisconnected(Optional.of(server()), "closed"),
                new EngineEvent.ServerMessage(server(), "Welcome"),
                new EngineEvent.ServerUpdated(server()),
                new EngineEvent.ServersChanged(14),
                new EngineEvent.SearchResults(new SearchId(7), List.of(result())),
                new EngineEvent.SearchEnded(new SearchId(7), 1),
                new EngineEvent.DownloadAdded(download()),
                new EngineEvent.DownloadProgress(download()),
                new EngineEvent.DownloadCompleted(download(), Path.of("incoming", "talk.bin")),
                new EngineEvent.DownloadFailed(download(), "disk full"),
                new EngineEvent.DownloadRemoved(Hash16.parseHex(HASH)),
                new EngineEvent.DiskSpace(Path.of("temp"), false),
                new EngineEvent.MessageReceived("198.51.100.9:4662", Hash16.parseHex(USER), Optional.empty(), "hi"),
                new EngineEvent.CaptchaRequested("198.51.100.9:4662", Hash16.parseHex(USER), new byte[] {1, 2}),
                new EngineEvent.CaptchaAnswered("198.51.100.9:4662", true),
                new EngineEvent.MessageFailed("198.51.100.9:4662", "no connection"),
                new EngineEvent.UpdateStatusChanged(updateStatus()),
                new EngineEvent.UpdateAvailable(newVersion()),
                new EngineEvent.UpdateReady(newVersion()),
                new EngineEvent.UpdateRestart("0.2.0"));
    }
}
