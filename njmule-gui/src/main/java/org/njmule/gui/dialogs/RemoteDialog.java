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
package org.njmule.gui.dialogs;

import atlantafx.base.theme.Styles;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.remote.RemoteEngine;
import org.njmule.gui.infra.RemoteConnections;
import org.njmule.gui.ui.DialogFrame;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects to another njMule through its API: the address and the token are tried, remembered if asked, and the desktop
 * app opens on that njMule in a window of its own.
 */
public final class RemoteDialog {

    private static final Logger logger = LoggerFactory.getLogger(RemoteDialog.class);

    private final Gui gui;
    private final AppDirectories local;
    private final Stage stage = new Stage();
    private final ComboBox<String> address = new ComboBox<>();
    private final PasswordField token = new PasswordField();
    private final CheckBox remember = new CheckBox();
    private final Label state = new Label();
    private final Button connect;

    public RemoteDialog(Gui gui, Window owner) {
        this.gui = gui;
        this.local = gui.model().engine().directories();
        this.stage.initOwner(owner);
        this.stage.initModality(Modality.WINDOW_MODAL);
        this.stage.setTitle(gui.t("nj.remote.title"));
        this.address.setEditable(true);
        this.address.setMaxWidth(Double.MAX_VALUE);
        this.address.setPromptText("http://192.168.1.10:4711/api/v1");
        try {
            RemoteConnections.remembered(this.local.gui())
                    .forEach(a -> this.address.getItems().add(a.toString()));
        } catch (IOException e) {
            logger.warn("Cannot read the remembered connections: {}", e.toString());
        }
        Fx.leftToRight(this.address);
        Fx.leftToRight(this.token);
        this.remember.setText(gui.t("nj.remote.remember"));
        this.remember.setSelected(true);
        Label intro = new Label(gui.t("nj.remote.intro"));
        Fx.wrap(intro);
        Label addressName = new Label(gui.t("nj.remote.address"));
        addressName.getStyleClass().add("dialog-label");
        addressName.setLabelFor(this.address);
        Label tokenName = new Label(gui.t("nj.remote.token"));
        tokenName.getStyleClass().add("dialog-label");
        tokenName.setLabelFor(this.token);
        this.state.getStyleClass().add(Styles.TEXT_MUTED);
        Fx.wrap(this.state);
        this.connect = Fx.primary(Fx.button(gui.t("nj.remote.connect"), Icons.LINK, this::connect));
        Button close = Fx.button(gui.t("IDS_FD_CLOSE"), Icons.CLOSE, this.stage::close);
        close.setCancelButton(true);
        VBox body = new VBox(
                12, intro, new VBox(6, addressName, this.address), new VBox(6, tokenName, this.token), this.remember);
        VBox root = DialogFrame.frame(
                DialogFrame.header(gui, gui.t("nj.remote.title"), this.stage::close),
                body,
                DialogFrame.footer(
                        this.state,
                        DialogFrame.role(close, ButtonBar.ButtonData.CANCEL_CLOSE),
                        DialogFrame.role(this.connect, ButtonBar.ButtonData.OK_DONE)));
        Scene scene = new Scene(root, 560, 380);
        gui.attach(scene);
        this.stage.setScene(scene);
    }

    public void show() {
        this.stage.show();
    }

    /**
     * The API's address as typed: http:// and /api/v1 are added when missing, as in "nas:4711"; empty when it is no
     * address of a web interface.
     */
    static Optional<URI> api(String typed) {
        String text = typed.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (!text.contains("://")) {
            text = "http://" + text;
        }
        try {
            URI uri = new URI(text);
            String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                return Optional.empty();
            }
            String path = uri.getPath() == null
                            || uri.getPath().isEmpty()
                            || uri.getPath().equals("/")
                    ? "/api/v1"
                    : uri.getPath();
            return Optional.of(new URI(scheme, null, uri.getHost(), uri.getPort(), path, null, null));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    // Tried first, so that a wrong address or token is said here rather than by a window that does not open.
    private void connect() {
        Optional<URI> api = api(String.valueOf(this.address.getEditor().getText()));
        String key = this.token.getText().strip();
        if (api.isEmpty() || key.isEmpty()) {
            this.state.setText(this.gui.t("nj.remote.missing"));
            return;
        }
        boolean keep = this.remember.isSelected();
        this.connect.setDisable(true);
        this.state.setText(this.gui.t("nj.remote.connecting"));
        Thread.ofVirtual().name("njmule-remote-connect").start(() -> {
            String outcome;
            boolean opened = false;
            try {
                RemoteEngine.connect(api.get(), key, this.local).close();
                if (keep) {
                    RemoteConnections.remember(this.local.gui(), api.get(), key);
                }
                opened = RemoteConnections.open(api.get(), key, List.of());
                outcome = opened ? "" : this.gui.t("nj.remote.notInstalled");
            } catch (IOException | RuntimeException e) {
                outcome = this.gui.f("nj.remote.failed", e.getMessage());
            }
            boolean done = opened;
            String said = outcome;
            Platform.runLater(() -> {
                this.connect.setDisable(false);
                if (done) {
                    this.stage.close();
                } else {
                    this.state.setText(said);
                }
            });
        });
    }
}
