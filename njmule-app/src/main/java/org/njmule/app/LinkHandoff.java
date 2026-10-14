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
package org.njmule.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands ed2k links to the njMule that runs with the same configuration, so that a second start (a link clicked in a
 * browser) passes them on instead of failing on the instance lock. The running one listens on a loopback port and
 * writes it, with a random secret, into the configuration directory; only who can read that file can hand over links.
 */
public final class LinkHandoff implements AutoCloseable {

    /** What a later start hands over, instead of links, to have the running njMule exit. */
    public static final String EXIT = "njmule:exit";

    /** The file in the configuration directory with the port and the secret. */
    public static final String FILE_NAME = "handoff";

    static final int MAX_LINKS = 256;
    static final int MAX_LINE = 64 * 1024;

    /** How long a later start may take to hand over its links. */
    static final Duration DEADLINE = Duration.ofSeconds(10);

    // The engine's instance lock in the configuration directory.
    private static final String LOCK_FILE = "njmule.lock";
    private static final Duration POLL = Duration.ofMillis(100);

    private static final Logger logger = LoggerFactory.getLogger(LinkHandoff.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String OK = "OK";
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerSocket socket;
    private final Path file;
    private final String content;
    private final byte[] secret;
    private final Consumer<List<String>> receiver;

    private LinkHandoff(
            ServerSocket socket, Path file, String content, byte[] secret, Consumer<List<String>> receiver) {
        this.socket = socket;
        this.file = file;
        this.content = content;
        this.secret = secret;
        this.receiver = receiver;
    }

    /**
     * Gives {@code links}, maybe none, to the instance running with {@code configDir}, which shows itself;
     * {@code false} when none listens, and then this process may start.
     */
    public static boolean deliver(Path configDir, List<String> links) {
        String[] fields;
        try {
            fields = Files.readString(configDir.resolve(FILE_NAME), StandardCharsets.US_ASCII)
                    .strip()
                    .split(" ", -1);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            logger.debug("Cannot read the handoff file: {}", e.toString());
            return false;
        }
        if (fields.length != 2) {
            return false;
        }
        int port;
        try {
            port = Integer.parseInt(fields[0]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (port < 1 || port > 0xFFFF) {
            return false;
        }
        try (Socket client = new Socket()) {
            client.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), Math.toIntExact(TIMEOUT.toMillis()));
            client.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
            Writer out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
            out.write(fields[1] + "\n");
            for (String link : links) {
                String line = link.strip();
                // One link per line: an empty one would end the list, a line break would add a line of its own.
                if (line.isEmpty() || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                    logger.warn("Not handing over an empty link or one with a line break");
                    continue;
                }
                out.write(line + "\n");
            }
            out.write("\n");
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            return OK.equals(in.readLine());
        } catch (IOException e) {
            // A file left by a crash: nobody listens there any more.
            logger.debug("No njMule took the links: {}", e.toString());
            return false;
        }
    }

    /** Waits until the instance running with {@code configDir} has stopped; {@code false} if it still runs. */
    public static boolean awaitGone(Path configDir, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!gone(configDir)) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    // An instance holds the engine's lock of its configuration until it has saved and stopped.
    private static boolean gone(Path configDir) {
        try (FileChannel channel = FileChannel.open(configDir.resolve(LOCK_FILE), StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return false;
            }
            lock.release();
            return true;
        } catch (NoSuchFileException e) {
            return true;
        } catch (OverlappingFileLockException e) {
            // Held in this very process.
            return false;
        } catch (IOException e) {
            // The handoff file goes first when an instance stops.
            return !Files.exists(configDir.resolve(FILE_NAME));
        }
    }

    /**
     * Listens for later starts; {@code receiver} gets the links of each, maybe none, or {@link #EXIT}, on a handoff
     * thread of each, maybe at the same time.
     */
    public static LinkHandoff listen(Path configDir, Consumer<List<String>> receiver) throws IOException {
        Objects.requireNonNull(receiver, "receiver");
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        ServerSocket socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        String content = socket.getLocalPort() + " " + HEX.formatHex(secret) + "\n";
        Path file = configDir.resolve(FILE_NAME);
        try {
            write(file, content);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        LinkHandoff handoff = new LinkHandoff(socket, file, content, secret, receiver);
        Thread.ofVirtual().name("njmule-handoff").start(handoff::acceptLoop);
        return handoff;
    }

    @Override
    public void close() {
        try {
            this.socket.close();
        } catch (IOException e) {
            // Closing anyway.
        }
        try {
            // A later instance may have written its own file meanwhile.
            if (this.content.equals(Files.readString(this.file, StandardCharsets.US_ASCII))) {
                Files.delete(this.file);
            }
        } catch (IOException e) {
            logger.debug("Cannot remove the handoff file: {}", e.toString());
        }
    }

    // Each later start on a thread of its own, so that a slow local program holds up nobody else.
    private void acceptLoop() {
        while (!this.socket.isClosed()) {
            try {
                Socket client = this.socket.accept();
                Thread.ofVirtual().name("njmule-handoff-client").start(() -> this.serveOne(client));
            } catch (IOException | RuntimeException e) {
                if (!this.socket.isClosed()) {
                    logger.debug("Handoff connection failed: {}", e.toString());
                }
            }
        }
    }

    private void serveOne(Socket client) {
        try (client) {
            this.serve(client, System.nanoTime() + DEADLINE.toNanos());
        } catch (IOException | RuntimeException e) {
            if (!this.socket.isClosed()) {
                logger.debug("Handoff connection failed: {}", e.toString());
            }
        }
    }

    private void serve(Socket client, long deadline) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String token = readLine(in, client, deadline);
        byte[] presented;
        try {
            presented = token == null ? new byte[0] : HEX.parseHex(token);
        } catch (IllegalArgumentException e) {
            presented = new byte[0];
        }
        if (!MessageDigest.isEqual(presented, this.secret)) {
            logger.warn("Refused links from a local program without the handoff secret");
            return;
        }
        List<String> links = new ArrayList<>();
        int lines = 0;
        for (String line = readLine(in, client, deadline);
                line != null && !line.isEmpty();
                line = readLine(in, client, deadline)) {
            lines++;
            if (links.size() < MAX_LINKS
                    && (line.equals(EXIT) || line.toLowerCase(Locale.ROOT).startsWith("ed2k://"))) {
                links.add(line);
            }
        }
        // Only a start that hands over nothing else asks this instance to exit.
        if (lines > 1) {
            links.removeIf(EXIT::equals);
        }
        try {
            this.receiver.accept(links);
        } catch (RuntimeException e) {
            // The links reached this instance all the same: the later start must not run beside it.
            logger.warn("Cannot follow the links handed over: {}", e.toString());
        }
        OutputStream out = client.getOutputStream();
        out.write((OK + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // A line of at most MAX_LINE characters before the deadline; longer or later ones end the exchange.
    private static String readLine(BufferedReader in, Socket client, long deadline) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new SocketTimeoutException("handoff too slow");
            }
            client.setSoTimeout(Math.toIntExact(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))));
            int c = in.read();
            if (c == -1) {
                return line.isEmpty() ? null : line.toString().strip();
            }
            if (c == '\n') {
                return line.toString().strip();
            }
            if (line.length() >= MAX_LINE) {
                throw new IOException("handoff line too long");
            }
            line.append((char) c);
        }
    }

    // Only its owner may read the secret: owner-only permissions where the file system has them.
    private static void write(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.deleteIfExists(tmp);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, content, StandardCharsets.US_ASCII);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
