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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the string tables of eMule's resource scripts into UTF-8 resource bundles, so the
 * interface keeps eMule's wording and translations. Mnemonics are dropped and C format specifiers
 * become Java ones.
 *
 * <p>Usage: {@code java tools/Rc2Properties.java <english .rc> <out dir> [<lang>=<.rc>...]}, e.g.
 * {@code ... emule.rc njmule-gui/src/main/resources/org/njmule/gui/i18n it=lang/it_IT.rc}.
 */
public final class Rc2Properties {

    // A comment may follow the string, as translators leave notes there.
    private static final Pattern ENTRY =
            Pattern.compile("^\\s*(IDS_\\w+)\\s*(?:\"((?:[^\"]|\"\")*)\")?\\s*(?://.*)?$");
    private static final Pattern CONTINUATION = Pattern.compile("^\\s*\"((?:[^\"]|\"\")*)\"\\s*(?://.*)?$");
    private static final Pattern PRINTF =
            Pattern.compile("%([-+ 0#]*\\d*(?:\\.\\d+)?)(I64|ll|l|h)?([diuxXsScfgeE])");
    // Chinese, Japanese and Korean texts write the mnemonic as a Latin letter in brackets: 打开文件(&O).
    private static final Pattern BRACKETED_MNEMONIC = Pattern.compile("\\(&[A-Za-z0-9]\\)");
    private static final String BUNDLE = "emule";

    private Rc2Properties() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Rc2Properties <english .rc> <out dir> [<lang>=<.rc>...]");
            System.exit(2);
        }
        Map<String, String> english = read(Path.of(args[0]));
        Path out = Files.createDirectories(Path.of(args[1]));
        write(out.resolve(BUNDLE + ".properties"), english);
        System.out.println(english.size() + " strings in English");
        for (int i = 2; i < args.length; i++) {
            String[] spec = args[i].split("=", 2);
            Map<String, String> translated = read(Path.of(spec[1]));
            translated.keySet().retainAll(english.keySet());
            write(out.resolve(BUNDLE + "_" + spec[0] + ".properties"), translated);
            TreeSet<String> missing = new TreeSet<>(english.keySet());
            missing.removeAll(translated.keySet());
            System.out.println(
                    translated.size() + " strings in " + spec[0] + ", missing: " + missing);
        }
    }

    static Map<String, String> read(Path rc) throws IOException {
        List<String> lines = decode(Files.readAllBytes(rc)).lines().toList();
        Map<String, String> strings = new TreeMap<>();
        boolean inTable = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.equals("STRINGTABLE")) {
                inTable = true;
                continue;
            }
            if (!inTable) {
                continue;
            }
            if (line.equals("END")) {
                inTable = false;
                continue;
            }
            Matcher entry = ENTRY.matcher(lines.get(i));
            if (!entry.matches()) {
                continue;
            }
            String text = entry.group(2);
            if (text == null && i + 1 < lines.size()) {
                Matcher next = CONTINUATION.matcher(lines.get(i + 1));
                if (next.matches()) {
                    text = next.group(1);
                    i++;
                }
            }
            if (text != null) {
                strings.put(entry.group(1), convert(text));
            }
        }
        return strings;
    }

    /** Unescapes a resource string, drops mnemonics and turns C specifiers into Java ones. */
    static String convert(String raw) {
        String text = BRACKETED_MNEMONIC.matcher(raw).replaceAll("");
        StringBuilder plain = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (c == '"' && next == '"') {
                plain.append('"');
                i++;
            } else if (c == '\\' && next != 0) {
                plain.append(
                        switch (next) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            default -> next;
                        });
                i++;
            } else if (c == '&') {
                if (next == '&') {
                    plain.append('&');
                    i++;
                }
            } else {
                plain.append(c);
            }
        }
        Matcher printf = PRINTF.matcher(plain);
        StringBuilder java = new StringBuilder();
        while (printf.find()) {
            char kind = printf.group(3).charAt(0);
            String converted =
                    switch (kind) {
                        case 'i', 'u' -> "d";
                        case 'S' -> "s";
                        default -> String.valueOf(kind);
                    };
            printf.appendReplacement(
                    java, Matcher.quoteReplacement("%" + printf.group(1) + converted));
        }
        printf.appendTail(java);
        return java.toString().replace("\r", "");
    }

    // The scripts are UTF-8 (with or without BOM); older ones may still be in a Windows code page.
    private static String decode(byte[] data) {
        int start = data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                        && (data[2] & 0xFF) == 0xBF
                ? 3
                : 0;
        ByteBuffer bytes = ByteBuffer.wrap(data, start, data.length - start);
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(data, start, data.length - start, Charset.forName("windows-1252"));
        }
    }

    private static void write(Path file, Map<String, String> strings) throws IOException {
        List<String> out = new ArrayList<>();
        // REUSE-IgnoreStart: the header of the generated files, not of this one.
        out.add("# SPDX-FileCopyrightText: 2002-2026 Merkur and the eMule contributors");
        out.add("# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors");
        out.add("# SPDX-License-Identifier: GPL-3.0-or-later");
        // REUSE-IgnoreEnd
        out.add("#");
        out.add("# Interface strings converted from eMule's string tables by tools/Rc2Properties.java;");
        out.add("# regenerate instead of editing.");
        for (Map.Entry<String, String> e : strings.entrySet()) {
            out.add(e.getKey() + "=" + escape(e.getValue()));
        }
        Files.writeString(file, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                case ' ' -> escaped.append(i == 0 ? "\\ " : " ");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
