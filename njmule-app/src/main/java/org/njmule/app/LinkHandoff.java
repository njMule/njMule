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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands ed2k links to the njMule that runs with the same configuration, so that a second start (a link clicked in a
 * browser) passes them on instead of failing on the instance lock. The running one listens on a loopback port and
 * writes it, with a random secret, into the configuration directory; only who can read that file can hand over links.
 */
public final class LinkHandoff implements AutoCloseable {

    /** The file in the configuration directory with the port and the secret. */
    public static final String FILE_NAME = "handoff";

    static final int MAX_LINKS = 256;
    static final int MAX_LINE = 64 * 1024;

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
        try (Socket client = new Socket()) {
            client.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), Math.toIntExact(TIMEOUT.toMillis()));
            client.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
            Writer out = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
            out.write(fields[1] + "\n");
            for (String link : links) {
                out.write(link.strip() + "\n");
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

    /** Listens for later starts; {@code receiver} gets the links of each, maybe none, on a handoff thread. */
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

    private void acceptLoop() {
        while (!this.socket.isClosed()) {
            try (Socket client = this.socket.accept()) {
                client.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
                this.serve(client);
            } catch (IOException | RuntimeException e) {
                if (!this.socket.isClosed()) {
                    logger.debug("Handoff connection failed: {}", e.toString());
                }
            }
        }
    }

    private void serve(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String token = readLine(in);
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
        for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
            if (links.size() < MAX_LINKS && line.toLowerCase(Locale.ROOT).startsWith("ed2k://")) {
                links.add(line);
            }
        }
        this.receiver.accept(links);
        OutputStream out = client.getOutputStream();
        out.write((OK + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // A line of at most MAX_LINE characters; longer ones end the exchange.
    private static String readLine(BufferedReader in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != -1; c = in.read()) {
            if (c == '\n') {
                return line.toString().strip();
            }
            if (line.length() >= MAX_LINE) {
                throw new IOException("handoff line too long");
            }
            line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString().strip();
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
