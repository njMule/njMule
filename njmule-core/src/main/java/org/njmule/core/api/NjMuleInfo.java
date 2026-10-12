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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;

/**
 * Application identity, version and the legal notices the GPL asks interactive programs to show;
 * shared by every front end so that the wording is identical everywhere.
 */
public final class NjMuleInfo {

    public static final String NAME = "njMule";
    public static final String TAGLINE = "Next generation Java Mule";
    public static final String APP_ID = "org.njmule.njMule";
    public static final String HOMEPAGE = "https://github.com/salvatore-coppola/njMule";
    public static final String LICENSE_ID = "GPL-3.0-or-later";
    public static final String COPYRIGHT =
            "Copyright (C) 2026 Salvatore Coppola and the njMule contributors";

    private static final String UNKNOWN_VERSION = "0.0.0-unknown";
    private static final String VERSION = loadVersion();

    private NjMuleInfo() {}

    /** Semantic Versioning version of this build. */
    public static String version() {
        return VERSION;
    }

    /**
     * The start-up notice recommended by the GPL ("How to Apply These Terms").
     *
     * @param warrantyHint how to show the warranty disclaimer, e.g. {@code --warranty}
     * @param licenseHint how to show the license, e.g. {@code --license}
     */
    public static String startupNotice(String warrantyHint, String licenseHint) {
        String nl = System.lineSeparator();
        return NAME
                + " "
                + VERSION
                + " - "
                + TAGLINE
                + nl
                + COPYRIGHT
                + nl
                + "This program comes with ABSOLUTELY NO WARRANTY; for details use '"
                + warrantyHint
                + "'."
                + nl
                + "This is free software, and you are welcome to redistribute it under certain"
                + " conditions; use '"
                + licenseHint
                + "' for details.";
    }

    /** Full GPL v3 text, bundled next to this class (unambiguous on the class path). */
    public static String licenseText() {
        return readResource("LICENSE")
                .orElse(
                        "GNU General Public License v3.0 or later:"
                                + " https://www.gnu.org/licenses/gpl-3.0");
    }

    /** The NOTICE file: copyright, attribution and third-party components. */
    public static String noticeText() {
        return readResource("NOTICE").orElse(COPYRIGHT);
    }

    /** GPL sections 15 (Disclaimer of Warranty) and 16 (Limitation of Liability). */
    public static String warrantyText() {
        String license = licenseText();
        int start = license.indexOf("15. Disclaimer of Warranty.");
        int end = license.indexOf("17. Interpretation of Sections 15 and 16.");
        return start >= 0 && end > start
                ? license.substring(start, end).strip()
                : "THERE IS NO WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW."
                        + " See sections 15 and 16 of the GNU General Public License.";
    }

    private static String loadVersion() {
        try (InputStream in = NjMuleInfo.class.getResourceAsStream("build-info.properties")) {
            if (in == null) {
                return UNKNOWN_VERSION;
            }
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("version", "").strip();
            return version.isEmpty() || version.startsWith("${") ? UNKNOWN_VERSION : version;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<String> readResource(String name) {
        try (InputStream in = NjMuleInfo.class.getResourceAsStream(name)) {
            return in == null
                    ? Optional.empty()
                    : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
