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
package org.njmule.gui.infra;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javafx.scene.paint.Color;

/**
 * The colours of the system's high contrast scheme, which replace the theme's while it is on. Windows tells them
 * through the platform preferences; the other systems have no such scheme there.
 */
record HighContrast(
        Color window,
        Color text,
        Color face,
        Color faceText,
        Color gray,
        Color highlight,
        Color highlightText,
        Color link) {

    private static final String ON = "Windows.SPI.HighContrast";
    private static final String COLOUR = "Windows.SysColor.";

    /** The scheme the preferences tell, or nothing while high contrast is off or its colours are unknown. */
    static Optional<HighContrast> of(Map<String, ?> preferences) {
        Optional<Color> window = colour(preferences, "COLOR_WINDOW");
        Optional<Color> text = colour(preferences, "COLOR_WINDOWTEXT");
        if (!Boolean.TRUE.equals(preferences.get(ON)) || window.isEmpty() || text.isEmpty()) {
            return Optional.empty();
        }
        Color highlight = colour(preferences, "COLOR_HIGHLIGHT").orElse(text.get());
        return Optional.of(new HighContrast(
                window.get(),
                text.get(),
                colour(preferences, "COLOR_3DFACE").orElse(window.get()),
                colour(preferences, "COLOR_BTNTEXT").orElse(text.get()),
                colour(preferences, "COLOR_GRAYTEXT").orElse(text.get()),
                highlight,
                colour(preferences, "COLOR_HIGHLIGHTTEXT").orElse(window.get()),
                colour(preferences, "COLOR_HOTLIGHT").orElse(highlight)));
    }

    /** Whether a change of the preference {@code key} may change the scheme. */
    static boolean concerns(String key) {
        return key.equals(ON) || key.startsWith(COLOUR);
    }

    /** Whether the scheme is a dark one: light text on a dark window. */
    boolean dark() {
        return this.window.getBrightness() < this.text.getBrightness();
    }

    /** A stylesheet that gives njMule's system tokens ({@code -nj-sys-*}) the scheme's colours. */
    String stylesheet() {
        String css = ".root.high-contrast {\n"
                + token("window", this.window)
                + token("text", this.text)
                + token("face", this.face)
                + token("face-text", this.faceText)
                + token("gray", this.gray)
                + token("highlight", this.highlight)
                + token("highlight-text", this.highlightText)
                + token("link", this.link)
                + "}\n";
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<Color> colour(Map<String, ?> preferences, String name) {
        return preferences.get(COLOUR + name) instanceof Color c ? Optional.of(c) : Optional.empty();
    }

    private static String token(String name, Color colour) {
        byte[] rgb = {
            (byte) Math.round(colour.getRed() * 255),
            (byte) Math.round(colour.getGreen() * 255),
            (byte) Math.round(colour.getBlue() * 255)
        };
        return "    -nj-sys-" + name + ": #" + HexFormat.of().formatHex(rgb) + ";\n";
    }
}
