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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.PeerComment;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.SharedFilesVisibility;
import org.njmule.core.persist.format.ClientsMet;

@Timeout(120)
class PeerTransferTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();

    private String uploaderSettings = "";
    private Path uploaderRoot;
    private NjMuleEngine uploader;
    private SharedFileInfo shared;
    private byte[] data;
    private FileComment comment;
    private Path downloaderRoot;
    private NjMuleEngine downloader;
    private List<SearchResult> browsed;
    private List<PeerComment> heard;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldDownloadWhatAnotherNjMuleSharesAndCountItForCredits() throws Exception {
        givenAnUploaderSharingRandomBytes("shared.bin", 12_000_000);
        givenADownloader();
        givenTheDownloaderDownloadedTheWholeFileFromTheUploader();

        whenBothClose();

        // Both proved their identity to each other, so the transfer counts for credits.
        thenTheUploaderCreditsTheDownloaderWithMoreUploadedThan(5_000_000);
        thenTheDownloaderCreditsTheUploaderWithMoreDownloadedThan(5_000_000);
    }

    @Test
    void shouldLetAnotherNjMuleSeeOurFilesOnlyWhenThePreferencesSaySo() throws Exception {
        givenAnUploaderSharingZeros("film.mkv", 300_000);
        givenADownloader();
        givenTheDownloaderSeesNoneOfTheUploadersFiles();
        givenTheUploaderShowsItsFilesTo(SharedFilesVisibility.EVERYBODY);

        whenTheDownloaderBrowsesTheUploader();

        thenTheDownloaderSeesOnly("film.mkv", 300_000);
    }

    @Test
    void shouldLetADownloaderHearWhatTheUploaderSaysOfTheFile() throws Exception {
        givenTheUploaderSendsAtMostKiBps(1);
        givenAnUploaderSharingZeros("song.mp3", 3_000_000);
        givenTheUploaderCommentsTheFile("A fine song", 4);
        givenADownloader();
        givenTheDownloaderAddsTheFileWithTheUploaderAsSource();

        whenTheDownloaderWaitsToHearWhatPeersSayOfTheFile();

        thenTheDownloaderHeardOnlyTheUploadersComment();
    }

    private void givenTheUploaderSendsAtMostKiBps(int kibps) {
        this.uploaderSettings = "limits.uploadKiBps=" + kibps + System.lineSeparator();
    }

    private void givenAnUploaderSharingRandomBytes(String name, int size) throws Exception {
        byte[] bytes = new byte[size];
        new Random(9).nextBytes(bytes);
        Arrays.fill(bytes, 1_000_000, 4_000_000, (byte) 7);
        givenAnUploaderSharing(name, bytes);
    }

    private void givenAnUploaderSharingZeros(String name, int size) throws Exception {
        givenAnUploaderSharing(name, new byte[size]);
    }

    private void givenAnUploaderSharing(String name, byte[] bytes) throws Exception {
        this.data = bytes;
        this.uploaderRoot = this.dir.resolve("uploader");
        Path incoming = Files.createDirectories(this.uploaderRoot.resolve("incoming"));
        Files.write(incoming.resolve(name), bytes);
        this.uploader = engine(this.uploaderRoot, this.uploaderSettings);
        this.shared = awaitShared(this.uploader);
        assertThat(this.shared.size()).isEqualTo(bytes.length);
    }

    private void givenTheUploaderCommentsTheFile(String text, int rating) throws Exception {
        this.comment = FileComment.of(text, rating);
        this.uploader.setFileComment(this.shared.hash(), this.comment).get(10, TimeUnit.SECONDS);
        assertThat(this.uploader.fileComment(this.shared.hash()).get(10, TimeUnit.SECONDS))
                .contains(this.comment);
    }

    private void givenTheUploaderShowsItsFilesTo(SharedFilesVisibility visibility) throws Exception {
        this.uploader
                .updatePreferences(
                        p -> p.toBuilder().sharedFilesVisibility(visibility).build())
                .get(10, TimeUnit.SECONDS);
    }

    private void givenADownloader() throws IOException {
        this.downloaderRoot = this.dir.resolve("downloader");
        this.downloader = engine(this.downloaderRoot);
    }

    private void givenTheDownloaderAddsTheFileWithTheUploaderAsSource() throws Exception {
        addTheFileWithTheUploaderAsSource();
    }

    private void givenTheDownloaderDownloadedTheWholeFileFromTheUploader() throws Exception {
        BlockingQueue<EngineEvent> events = events(this.downloader);
        DownloadInfo added = addTheFileWithTheUploaderAsSource();
        Path file = completed(events, added, this.downloader, this.uploader).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(this.data);
    }

    private void givenTheDownloaderSeesNoneOfTheUploadersFiles() throws Exception {
        assertThat(browseTheUploader()).as("nobody sees them by default").isEmpty();
    }

    private void whenBothClose() {
        this.uploader.close();
        this.downloader.close();
    }

    private void whenTheDownloaderBrowsesTheUploader() throws Exception {
        this.browsed = browseTheUploader();
    }

    private void whenTheDownloaderWaitsToHearWhatPeersSayOfTheFile() throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        this.heard = List.of();
        while (this.heard.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
            this.heard = this.downloader.peerComments(this.shared.hash()).get(10, TimeUnit.SECONDS);
        }
    }

    private void thenTheUploaderCreditsTheDownloaderWithMoreUploadedThan(long bytes) throws IOException {
        ClientsMet.Credit atUploader = onlyCredit(this.uploaderRoot);
        assertThat(atUploader.uploaded()).isGreaterThan(bytes);
        assertThat(atUploader.publicKey().length()).isPositive();
    }

    private void thenTheDownloaderCreditsTheUploaderWithMoreDownloadedThan(long bytes) throws IOException {
        ClientsMet.Credit atDownloader = onlyCredit(this.downloaderRoot);
        assertThat(atDownloader.downloaded()).isGreaterThan(bytes);
        assertThat(atDownloader.publicKey().length()).isPositive();
    }

    private void thenTheDownloaderSeesOnly(String name, long size) {
        assertThat(this.browsed).singleElement().satisfies(f -> {
            assertThat(f.hash()).isEqualTo(this.shared.hash());
            assertThat(f.name()).isEqualTo(name);
            assertThat(f.size()).isEqualTo(size);
        });
    }

    private void thenTheDownloaderHeardOnlyTheUploadersComment() {
        assertThat(this.heard)
                .singleElement()
                .satisfies(c -> assertThat(c.comment()).isEqualTo(this.comment));
    }

    private DownloadInfo addTheFileWithTheUploaderAsSource() throws Exception {
        Ed2kLink.FileLink link =
                (Ed2kLink.FileLink) Ed2kLink.parse(this.shared.link().toUri()
                        + "|sources,127.0.0.1:"
                        + this.uploader.preferences().tcpPort()
                        + "|/");
        return this.downloader.addDownload(link).get(10, TimeUnit.SECONDS);
    }

    private List<SearchResult> browseTheUploader() throws Exception {
        ServerAddress address =
                new ServerAddress("127.0.0.1", this.uploader.preferences().tcpPort());
        return this.downloader.browseSharedFiles(address).get(30, TimeUnit.SECONDS);
    }

    private static ClientsMet.Credit onlyCredit(Path root) throws IOException {
        List<ClientsMet.Credit> credits =
                ClientsMet.read(Files.readAllBytes(root.resolve("config").resolve("clients.met")));
        assertThat(credits).hasSize(1);
        return credits.getFirst();
    }

    private static SharedFileInfo awaitShared(NjMuleEngine engine) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            List<SharedFileInfo> files = engine.sharedFiles().get(10, TimeUnit.SECONDS);
            if (!files.isEmpty()) {
                return files.getFirst();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("nothing shared within " + WAIT);
    }

    private static EngineEvent.DownloadCompleted completed(
            BlockingQueue<EngineEvent> events, DownloadInfo download, NjMuleEngine engine, NjMuleEngine source)
            throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            EngineEvent event = events.poll(100, TimeUnit.MILLISECONDS);
            if (event instanceof EngineEvent.DownloadCompleted done
                    && done.download().hash().equals(download.hash())) {
                return done;
            }
            if (event instanceof EngineEvent.DownloadFailed failed) {
                throw new AssertionError("download failed: " + failed.reason());
            }
        }
        throw new AssertionError("no download completed within "
                + WAIT
                + ": "
                + engine.downloads().get(10, TimeUnit.SECONDS)
                + "\ndownloader "
                + ((Engine) engine).describe()
                + "\nuploader "
                + ((Engine) source).describe()
                + "\nthreads:\n"
                + connectionThreads());
    }

    // The stacks of the connection threads, virtual ones included, to see where a transfer hangs.
    private static String connectionThreads() {
        try {
            Path dump = Files.createTempFile("njmule-threads", ".txt");
            Files.delete(dump);
            Path jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd");
            Process process = new ProcessBuilder(
                            jcmd.toString(),
                            Long.toString(ProcessHandle.current().pid()),
                            "Thread.dump_to_file",
                            "-format=text",
                            dump.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            process.waitFor(30, TimeUnit.SECONDS);
            StringBuilder text = new StringBuilder();
            List<String> lines = Files.readAllLines(dump);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("njmule-tcp") || lines.get(i).contains("njmule-engine")) {
                    for (int j = i; j < Math.min(lines.size(), i + 14); j++) {
                        text.append(lines.get(j)).append('\n');
                    }
                }
            }
            return text.toString();
        } catch (IOException | InterruptedException e) {
            return "no thread dump: " + e;
        }
    }

    private NjMuleEngine engine(Path root) throws IOException {
        return engine(root, "");
    }

    private NjMuleEngine engine(Path root, String extra) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        int port = FreePorts.tcp();
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\n" + extra);
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }

    private static BlockingQueue<EngineEvent> events(NjMuleEngine engine) {
        BlockingQueue<EngineEvent> queue = new LinkedBlockingQueue<>();
        engine.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(EngineEvent item) {
                queue.add(item);
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
        return queue;
    }
}
