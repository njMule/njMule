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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javafx.scene.control.ComboBox;
import org.njmule.gui.infra.GuiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An editable box for a web address that offers the ones used before, the latest first; an address is remembered once
 * it worked. FX thread only.
 */
public final class UrlBox {

    private static final Logger logger = LoggerFactory.getLogger(UrlBox.class);
    // The files eMule keeps its box histories in, copied by the import.
    private static final Map<String, String> EMULE_HISTORIES =
            Map.of("serverMet", "AC_ServerMetURLs.dat", "ipFilter", "AC_IPFilterUpdateURLs.dat");

    private final GuiSettings settings;
    private final String history;
    private final ComboBox<String> box = new ComboBox<>();
    private final Input<URI> input;

    /** A box whose addresses are kept as {@code history} in the interface settings. */
    public UrlBox(Gui gui, String history, String name) {
        this.settings = gui.settings();
        this.history = history;
        this.seed(gui.directories().config());
        this.box.setEditable(true);
        this.box.setMaxWidth(Double.MAX_VALUE);
        this.box.getItems().setAll(this.settings.history(history));
        this.input = new Inputs(gui).url(this.box.getEditor(), name).quietWhenEmpty();
    }

    // An empty history starts with what was typed in eMule's box, when its profile was imported.
    private void seed(Path config) {
        String file = EMULE_HISTORIES.get(this.history);
        if (file == null || !this.settings.history(this.history).isEmpty()) {
            return;
        }
        Path path = config.resolve(file);
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            List<String> entries = emuleHistory(Files.readAllBytes(path));
            for (String entry : entries.reversed()) {
                this.settings.remember(this.history, entry);
            }
            this.settings.save();
        } catch (IOException e) {
            logger.debug("Cannot read {}: {}", path, e.toString());
        }
    }

    /** The lines of an eMule box history: UTF-16LE after a byte order mark, newest first. */
    static List<String> emuleHistory(byte[] data) {
        if (data.length < 2 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xFE) {
            return List.of();
        }
        return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE)
                .lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .distinct()
                .limit(GuiSettings.MAX_HISTORY)
                .toList();
    }

    public ComboBox<String> node() {
        return this.box;
    }

    public Input<URI> input() {
        return this.input;
    }

    /** Remembers {@code url} at the top of the list. */
    public void worked(URI url) {
        this.settings.remember(this.history, url.toString());
        try {
            this.settings.save();
        } catch (IOException e) {
            logger.warn("Cannot save the addresses used: {}", e.toString());
        }
        String text = this.box.getEditor().getText();
        this.box.getItems().setAll(this.settings.history(this.history));
        this.box.getEditor().setText(text);
    }
}
