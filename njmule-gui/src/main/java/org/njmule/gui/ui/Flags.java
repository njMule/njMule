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
package org.njmule.gui.ui;

import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/**
 * The flag of a client's country beside its ISO code, the country's name in the language shown as tooltip: the colours
 * of a flag are never the only cue. The flags are flag-icons' (MIT), rendered to PNG.
 */
public final class Flags {

    private static final double WIDTH = 16;
    private static final double HEIGHT = 12;
    private static final Map<String, Optional<Image>> IMAGES = new ConcurrentHashMap<>();

    private Flags() {}

    /** Shows {@code country}, an ISO 3166-1 code in lower case, on {@code label}; nothing when it is empty. */
    public static void show(Labeled label, Optional<String> country, Locale language) {
        if (country.isEmpty()) {
            label.setText(null);
            label.setGraphic(null);
            label.setTooltip(null);
            return;
        }
        String code = country.get();
        label.setText(code.toUpperCase(Locale.ROOT));
        label.setGraphic(image(code).map(Flags::view).orElse(null));
        label.setTooltip(new Tooltip(name(code, language)));
    }

    /** The name of the country of {@code code} in {@code language}, or the code when the system does not know it. */
    public static String name(String code, Locale language) {
        String upper = code.toUpperCase(Locale.ROOT);
        String name = Locale.of("", upper).getDisplayCountry(language);
        return name.isEmpty() ? upper : name;
    }

    private static ImageView view(Image image) {
        ImageView view = new ImageView(image);
        view.setFitWidth(WIDTH);
        view.setFitHeight(HEIGHT);
        view.setSmooth(true);
        return view;
    }

    // Two lower-case letters only: nothing else of the resources can be asked for.
    private static Optional<Image> image(String code) {
        if (!code.matches("[a-z]{2}")) {
            return Optional.empty();
        }
        return IMAGES.computeIfAbsent(code, c -> {
            URL url = Flags.class.getResource("/org/njmule/gui/flags/" + c + ".png");
            return url == null ? Optional.empty() : Optional.of(new Image(url.toExternalForm()));
        });
    }
}
