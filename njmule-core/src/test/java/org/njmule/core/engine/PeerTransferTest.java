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
import java.net.ServerSocket;
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
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
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

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void oneNjMuleDownloadsWhatAnotherShares() throws Exception {
        byte[] data = new byte[12_000_000];
        new Random(9).nextBytes(data);
        Arrays.fill(data, 1_000_000, 4_000_000, (byte) 7);
        Path uploaderRoot = this.dir.resolve("uploader");
        Path incoming = Files.createDirectories(uploaderRoot.resolve("incoming"));
        Files.write(incoming.resolve("shared.bin"), data);
        NjMuleEngine uploader = this.engine(uploaderRoot);
        SharedFileInfo shared = awaitShared(uploader);
        assertThat(shared.size()).isEqualTo(data.length);

        Path downloaderRoot = this.dir.resolve("downloader");
        NjMuleEngine downloader = this.engine(downloaderRoot);
        BlockingQueue<EngineEvent> events = events(downloader);
        Ed2kLink.FileLink link =
                (Ed2kLink.FileLink) Ed2kLink.parse(shared.link().toUri()
                        + "|sources,127.0.0.1:"
                        + uploader.preferences().tcpPort()
                        + "|/");
        DownloadInfo added = downloader.addDownload(link).get(10, TimeUnit.SECONDS);

        Path file = completed(events, added, downloader, uploader).file();
        assertThat(Files.readAllBytes(file)).isEqualTo(data);

        uploader.close();
        downloader.close();
        // Both proved their identity to each other, so the transfer counts for credits.
        ClientsMet.Credit atUploader = onlyCredit(uploaderRoot);
        ClientsMet.Credit atDownloader = onlyCredit(downloaderRoot);
        assertThat(atUploader.uploaded()).isGreaterThan(5_000_000);
        assertThat(atUploader.publicKey().length()).isPositive();
        assertThat(atDownloader.downloaded()).isGreaterThan(5_000_000);
        assertThat(atDownloader.publicKey().length()).isPositive();
    }

    @Test
    void anotherNjMuleSeesOurFilesOnlyWhenThePreferencesSaySo() throws Exception {
        Path uploaderRoot = this.dir.resolve("browsed");
        Path incoming = Files.createDirectories(uploaderRoot.resolve("incoming"));
        Files.write(incoming.resolve("film.mkv"), new byte[300_000]);
        NjMuleEngine browsed = this.engine(uploaderRoot);
        SharedFileInfo shared = awaitShared(browsed);
        NjMuleEngine browser = this.engine(this.dir.resolve("browser"));
        ServerAddress address =
                new ServerAddress("127.0.0.1", browsed.preferences().tcpPort());

        assertThat(browser.browseSharedFiles(address).get(30, TimeUnit.SECONDS))
                .as("nobody sees them by default")
                .isEmpty();

        browsed.updatePreferences(p -> p.toBuilder()
                        .sharedFilesVisibility(SharedFilesVisibility.EVERYBODY)
                        .build())
                .get(10, TimeUnit.SECONDS);
        assertThat(browser.browseSharedFiles(address).get(30, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.hash()).isEqualTo(shared.hash());
                    assertThat(f.name()).isEqualTo("film.mkv");
                    assertThat(f.size()).isEqualTo(300_000);
                });
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
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\n");
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
