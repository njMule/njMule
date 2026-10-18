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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes njmule-update.json, the description of a release that njMule offers as an update once it is signed: its
 * version, its notes (the release's section of the changelog; a "### Security" heading marks the release critical)
 * and its packages with their sizes and SHA-256 hashes. A single-file program has no JSON library: its few strings are
 * escaped here as RFC 8259 asks.
 *
 * <p>Usage: {@code java tools/UpdateManifest.java <dist> <version> <notes.md> > dist/njmule-update.json}.
 */
public final class UpdateManifest {

    /** What a package's file name tells: the platform it runs on and its kind, as njMule's update check names them. */
    private record Kind(Pattern name, String platform, String kind) {
        Kind(String regex, String platform, String kind) {
            this(Pattern.compile(regex), platform, kind);
        }
    }

    private static final List<Kind> KINDS = List.of(
            new Kind("njmule-.+-windows-x64\\.msi", "windows-x64", "msi"),
            new Kind("njmule-.+-windows-x64\\.zip", "windows-x64", "zip"),
            new Kind("njmule_.+_amd64\\.deb", "linux-x64", "deb"),
            new Kind("njmule_.+_arm64\\.deb", "linux-arm64", "deb"),
            new Kind("njmule-.+\\.x86_64\\.rpm", "linux-x64", "rpm"),
            new Kind("njmule-.+\\.aarch64\\.rpm", "linux-arm64", "rpm"),
            new Kind("njmule-.+-linux-x64-static\\.tar\\.xz", "linux-x64", "static-tar"),
            new Kind("njmule-.+-linux-x64\\.tar\\.xz", "linux-x64", "tar"),
            new Kind("njmule-.+-linux-arm64\\.tar\\.xz", "linux-arm64", "tar"),
            new Kind("njmule-.+-macos-arm64\\.dmg", "macos-arm64", "dmg"),
            new Kind("njmule-.+-synology-x86_64\\.spk", "linux-x64", "spk"));

    private UpdateManifest() {}

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args.length != 3) {
            System.err.println("Usage: java tools/UpdateManifest.java <dist> <version> <notes.md>");
            System.exit(2);
        }
        Path dist = Path.of(args[0]);
        String notes = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8).strip();
        boolean critical = notes.lines().anyMatch(l -> l.strip().equals("### Security"));
        List<String> assets = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> all = Files.list(dist)) {
            files = all.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            for (Kind k : KINDS) {
                if (k.name().matcher(name).matches()) {
                    assets.add("    {\"platform\": " + string(k.platform()) + ", \"kind\": " + string(k.kind())
                            + ", \"name\": " + string(name) + ", \"size\": " + Files.size(file) + ", \"sha256\": "
                            + string(sha256(file)) + "}");
                    break;
                }
            }
        }
        if (assets.isEmpty()) {
            System.err.println("No packages in " + dist);
            System.exit(1);
        }
        System.out.print("{\n  \"schema\": 1,\n  \"version\": " + string(args[1]) + ",\n  \"published\": "
                + string(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()) + ",\n  \"critical\": " + critical
                + ",\n  \"notes\": " + string(notes) + ",\n  \"assets\": [\n" + String.join(",\n", assets)
                + "\n  ]\n}\n");
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String string(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
