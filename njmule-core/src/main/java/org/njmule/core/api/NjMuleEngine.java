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
package org.njmule.core.api;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.UnaryOperator;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.Schedule;
import org.njmule.core.engine.Engine;

/**
 * Facade of the engine for every front end. Methods are thread-safe; commands run on the engine thread and complete
 * their futures there, events are delivered on a dedicated thread.
 */
public interface NjMuleEngine extends AutoCloseable {

    /** How long a cancelled download can be taken back with {@link #restoreDownload}. */
    Duration CANCEL_UNDO_TIME = Duration.ofSeconds(15);

    /** Creates the directories, loads the preferences and starts the engine. */
    static NjMuleEngine start(EngineConfig config) throws IOException {
        return Engine.start(config);
    }

    AppDirectories directories();

    /** Latest committed preferences; never blocks. */
    Preferences preferences();

    /** Applies, validates and persists a change; the future fails if the result is invalid. */
    CompletableFuture<Preferences> updatePreferences(UnaryOperator<Preferences> change);

    Flow.Publisher<EngineEvent> events();

    /** The scheduler's rules; never blocks. */
    Schedule schedule();

    /** Saves new rules and applies them at once. */
    CompletableFuture<Schedule> updateSchedule(Schedule schedule);

    /** The rule whose limits apply now, if any; never blocks. */
    Optional<Schedule.Rule> activeRule();

    /** Adds the servers of a {@code server.met} (e.g. eMule's); completes with how many were new. */
    CompletableFuture<Integer> addServers(Path serverMet);

    /**
     * Adds the servers of the {@code server.met} at {@code url}, fetched over http or https from a public address;
     * completes with how many were new.
     */
    CompletableFuture<Integer> addServers(URI url);

    /**
     * Replaces the IP filter with the list at {@code url} (ipfilter.dat, PeerGuardian or P2B form, maybe zipped),
     * fetched like a server list; completes with how many ranges it has.
     */
    CompletableFuture<Integer> updateIpFilter(URI url);

    /** The known servers, in the order of the list. */
    CompletableFuture<List<ServerInfo>> servers();

    /** Completes with {@code false} for an unknown server. */
    CompletableFuture<Boolean> removeServer(ServerAddress server);

    /** The server.met addresses of {@code addresses.dat}, which the update at start takes. */
    CompletableFuture<List<URI>> serverListAddresses();

    /** Replaces the addresses of {@code addresses.dat}. */
    CompletableFuture<Void> setServerListAddresses(List<URI> addresses);

    /** Removes the known ones of {@code servers}; completes with how many. */
    CompletableFuture<Integer> removeServers(Collection<ServerAddress> servers);

    /** Adds a server to the list without connecting; completes with {@code false} when known or not usable. */
    CompletableFuture<Boolean> addServer(ServerAddress server);

    /** Sets the order a server is tried in; completes with {@code false} for an unknown server. */
    CompletableFuture<Boolean> setServerPriority(ServerAddress server, ServerInfo.Priority priority);

    /**
     * Puts a server in the static list ({@code staticservers.dat}), whose servers are kept, or takes it out; completes
     * with {@code false} for an unknown server.
     */
    CompletableFuture<Boolean> setStaticServer(ServerAddress server, boolean isStatic);

    /** Connections, rates and counts at this moment. */
    CompletableFuture<EngineStatus> status();

    /** The clients we upload to, then those waiting in our queue. */
    CompletableFuture<List<UploadInfo>> uploads();

    /** The search tabs left open at the last exit, when they are kept ({@link Preferences#storeSearches()}). */
    CompletableFuture<List<SavedSearch>> savedSearches();

    /** Keeps {@code searches} for the next start, in place of those kept before. */
    CompletableFuture<Void> saveSearches(List<SavedSearch> searches);

    /** The files downloaded or cancelled before, as remembered now. */
    CompletableFuture<FileHistory> fileHistory();

    /** The search results marked as spam. */
    CompletableFuture<SpamMarks> spamMarks();

    /** Marks {@code results} as spam, or clears them; completes with the marks now. */
    CompletableFuture<SpamMarks> markSpam(List<SearchResult> results, boolean isSpam);

    /** The sources of our downloads. */
    CompletableFuture<List<SourceInfo>> sources();

    /** The addresses refused uploads for a while. */
    CompletableFuture<List<BanInfo>> bans();

    /** Lifts the ban on the IPv4 address {@code ip}; completes with {@code false} when it was not banned. */
    CompletableFuture<Boolean> unban(String ip);

    /**
     * Connects to {@code server} (added to the list when new), or tries the known servers in turn when empty. The
     * future completes once the attempt has started; events tell the outcome.
     */
    CompletableFuture<Void> connect(Optional<ServerAddress> server);

    CompletableFuture<Void> disconnect();

    /** Starts a search; fails for an invalid expression or without a server connection. */
    CompletableFuture<SearchId> search(SearchRequest request);

    /** Asks the server for more results; completes with {@code false} when it has none. */
    CompletableFuture<Boolean> moreResults(SearchId search);

    CompletableFuture<Void> stopSearch(SearchId search);

    /** Says hello to the client at {@code peer} and completes with its answer. */
    CompletableFuture<PeerInfo> hello(ServerAddress peer);

    /**
     * Starts downloading the file of {@code link} into the temp directory; a file already being downloaded gets the
     * link's sources.
     */
    CompletableFuture<DownloadInfo> addDownload(Ed2kLink.FileLink link);

    /**
     * Adds a download in {@code category}, or in the one its name falls in when empty; {@code paused}, it asks no
     * source until resumed. A download of the file already there keeps its own.
     */
    CompletableFuture<DownloadInfo> addDownload(Ed2kLink.FileLink link, OptionalInt category, boolean paused);

    CompletableFuture<List<DownloadInfo>> downloads();

    /** Completes with {@code false} for an unknown download. */
    CompletableFuture<Boolean> pauseDownload(Hash16 hash);

    CompletableFuture<Boolean> resumeDownload(Hash16 hash);

    /** Pauses the download and forgets its sources; resumed, it looks for them again. */
    CompletableFuture<Boolean> stopDownload(Hash16 hash);

    /** The download categories, in the order of their ids. */
    CompletableFuture<List<Category>> categories();

    /** Replaces the categories; downloads in one that is gone have none. */
    CompletableFuture<Void> setCategories(List<Category> categories);

    /** Puts a download in a category, with its priority; {@link Category#NONE} takes it out of any. */
    CompletableFuture<Boolean> setDownloadCategory(Hash16 hash, int category);

    /**
     * Adds a source the user knows of to a download, at a LAN address too unless the IP filter blocks it; completes
     * with {@code false} for an unknown download or a source refused or known already.
     */
    CompletableFuture<Boolean> addSource(Hash16 download, ServerAddress source);

    /** Fetches the first and last parts of a download first, for a preview, or stops; {@code false} if unknown. */
    CompletableFuture<Boolean> setDownloadPreview(Hash16 hash, boolean previewFirst);

    /**
     * Swaps the A4AF sources of a download, clients asked for another download, to it: each is asked for it next,
     * unless it sends us data or is being asked now. Completes with how many were swapped.
     */
    CompletableFuture<Integer> swapSourcesToDownload(Hash16 hash);

    /**
     * Swaps the clients asked for a download to other downloads they are sources of, which go first for a while.
     * Completes with how many were swapped.
     */
    CompletableFuture<Integer> swapSourcesFromDownload(Hash16 hash);

    /** Has a download take its A4AF sources whenever they are free to move, or stops; {@code false} if unknown. */
    CompletableFuture<Boolean> setDownloadAutoSwap(Hash16 hash, boolean autoSwap);

    /**
     * Swaps one A4AF source of a download to it; {@code false} if it is none, the download does not run or the client
     * cannot move now.
     */
    CompletableFuture<Boolean> swapSourceToDownload(Hash16 download, ServerAddress source);

    /**
     * The data file of a download, which a player can show while it grows; empty for an unknown download and until its
     * first 16 KiB have come.
     */
    CompletableFuture<Optional<Path>> downloadData(Hash16 hash);

    /** Sets how early the download asks for sources and starts transfers, before the others. */
    CompletableFuture<Boolean> setDownloadPriority(Hash16 hash, DownloadPriority priority);

    /**
     * Stops the download; its data is deleted once {@link #CANCEL_UNDO_TIME} has passed, unless
     * {@link #restoreDownload} takes it back meanwhile.
     */
    CompletableFuture<Boolean> cancelDownload(Hash16 hash);

    /**
     * Takes back a download cancelled less than {@link #CANCEL_UNDO_TIME} ago, as it was but for its sources;
     * {@code false} when it is gone.
     */
    CompletableFuture<Boolean> restoreDownload(Hash16 hash);

    /** The files we share: those of the incoming directory and of the shared directories. */
    CompletableFuture<List<SharedFileInfo>> sharedFiles();

    /** Sets how a shared file ranks in the upload queue; completes with {@code false} for a file not shared. */
    CompletableFuture<Boolean> setUploadPriority(Hash16 hash, UploadPriority priority);

    /**
     * Renames a shared file in its directory; completes with {@code false} for a file not shared, and fails when the
     * directory has a file of that name or for a name never shared (a backup, a hidden file).
     */
    CompletableFuture<Boolean> renameSharedFile(Hash16 hash, String name);

    /** Shares the files of {@code directory} too (not of its subdirectories), from now on. */
    CompletableFuture<Void> shareDirectory(Path directory);

    /** Shares the files of {@code directory} too, with those of its subdirectories (also later ones) or alone. */
    CompletableFuture<Void> shareDirectory(Path directory, boolean subdirectories);

    /** Stops sharing {@code directory}, as shared alone or with its subdirectories. */
    CompletableFuture<Void> unshareDirectory(Path directory);

    /** The directories shared besides the incoming one. */
    CompletableFuture<List<SharedDirectory>> sharedDirectories();

    /** Starts Kad from the known contacts, or, when it runs, looks for contacts at once. */
    CompletableFuture<Void> connectKad();

    /** Stops Kad until it is connected again or njMule starts again. */
    CompletableFuture<Void> disconnectKad();

    /** Checks again whether Kad nodes can reach our TCP port; completes with {@code false} when Kad does not run. */
    CompletableFuture<Boolean> recheckKadFirewall();

    /** Asks the Kad node at {@code node} (its UDP port) for contacts to join with. */
    CompletableFuture<Void> kadBootstrap(ServerAddress node);

    /**
     * Adds the Kad contacts of the {@code nodes.dat} at {@code url}, fetched like a server list; completes with how
     * many were new.
     */
    CompletableFuture<Integer> addKadContacts(URI url);

    /** Our comment on a file, which the clients downloading it hear. */
    CompletableFuture<Optional<FileComment>> fileComment(Hash16 file);

    /** Keeps our comment on a file; an empty one takes it away. */
    CompletableFuture<Void> setFileComment(Hash16 file, FileComment comment);

    /** What the sources of a download said of the file. */
    CompletableFuture<List<PeerComment>> peerComments(Hash16 download);

    /** What Kad nodes said of a download or shared file, as far as searching Kad found it. */
    CompletableFuture<List<KadNote>> kadNotes(Hash16 file);

    /**
     * Searches Kad for what nodes said of a download or shared file, or joins the search running for it; completes with
     * all the notes known once it ends, and fails when Kad is not connected or cannot search for the file now.
     */
    CompletableFuture<List<KadNote>> searchKadNotes(Hash16 file);

    /** The friend that has the friend slot, served before anyone else, if any. */
    CompletableFuture<Optional<Hash16>> friendSlot();

    /**
     * Gives the friend slot to {@code friend}, or takes it away with empty, for this run; completes with {@code false}
     * for one who is not a friend.
     */
    CompletableFuture<Boolean> setFriendSlot(Optional<Hash16> friend);

    /**
     * The files the client listening at {@code client} shares, as it lists them when asked; fails when it does not show
     * them or cannot be reached.
     */
    CompletableFuture<List<SearchResult>> browseSharedFiles(ServerAddress client);

    /** The contacts of the Kad routing table, closest first; none while Kad does not run. */
    CompletableFuture<List<KadContactInfo>> kadContacts();

    /** The Kad lookups running now. */
    CompletableFuture<List<KadSearchInfo>> kadSearches();

    /** The state of Kad, empty when it does not run. */
    CompletableFuture<Optional<KadInfo>> kad();

    /**
     * Takes what njMule can use from the eMule profile in {@code emuleConfigDir}, which is only read; completes with
     * what happened to each file.
     */
    CompletableFuture<ImportReport> importFromEmule(Path emuleConfigDir, ImportOptions options);

    /**
     * Sends {@code text} to the client listening at {@code to}; the answer comes as an event. A CAPTCHA's solution is
     * sent the same way.
     */
    CompletableFuture<Void> sendMessage(ServerAddress to, String text);

    CompletableFuture<List<Friend>> friends();

    /** Adds a friend, or updates its name and address. */
    CompletableFuture<Void> addFriend(Friend friend);

    /** Completes with {@code false} for an unknown friend. */
    CompletableFuture<Boolean> removeFriend(Hash16 userHash);

    boolean isRunning();

    /** Stops the engine and saves its state; idempotent. */
    @Override
    void close();
}
